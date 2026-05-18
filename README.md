# seat-lock-alternatives

[ticketing](https://github.com/dongwooooooo/ticketing) §5 (좌석 동시 선점 차단) 의 **6가지 대안 + 채택 베이스라인 + 추가 부하 입증**을 실제 코드와 측정으로 검증한 레포.

## 결론 요약

- **6가지 대안 (A~F)** : 좌석당 정합성은 모두 달성되지만 각자 운영상 단점이 있다 → 기각.
- **채택**: 비관적 락 + partial UNIQUE (2단 방어, alt-z = stress-baseline 모듈).
- **그러나** Stage 2 베이스라인 단독으로는 50K 좌석 × 20만 동시 환경에서 풀 고갈·deadlock·lock timeout·starvation·connection leak 5종이 운영급 부하에서 재현됨 → **Stage 3 (대기열) 진입 정당화**.

## 실험 시나리오 (대안 비교 공통)

**사용자 입장 풀이**: BTS 콘서트 11:00:00 정각 오픈, 좌석 100번(맨 앞 정중앙) 클릭 버튼을 100명이 동시에 누른다. 합격선은 "1명만 좌석을 받고, 99명은 즉시 '이미 선점됨' 응답을 받는다 + DB 에는 HELD reservation 이 정확히 1건만 남는다" 이다.

- 좌석 1번에 동시 100건의 예매 요청
- `ExecutorService(100)` + `CountDownLatch` 시작 게이트
- 합격선: 정확히 1건만 통과 (heldCount = 1, 좌석 중복 없음)

## 대안 측정 결과

| 디렉터리 | 대안 | success | rejected | heldCount | elapsedMs | 채택 여부 | 핵심 기각 사유 |
|---|---|---|---|---|---|---|---|
| [alt-a-partial-unique](alt-a-partial-unique/) | A. partial UNIQUE 단독 (락 없음) | 1 | 99 | 1 | 138 | 기각 | 같은 좌석 클릭한 99명 전부가 DB 까지 도달해 PSQL 23505 로그가 폭주, 운영팀 알람 99건 동시 발생 |
| [alt-b-optimistic-lock](alt-b-optimistic-lock/) | B. 낙관적 락 (@Version) | 1 | 99 | 1 | 122 | 기각 | hot 좌석 N개에 사용자 M명 몰리면 retry 가 99*K 로 증폭 — 사용자 응답이 1초+ 지연 |
| [alt-c-insert-on-conflict](alt-c-insert-on-conflict/) | C. INSERT ON CONFLICT DO NOTHING | 1 | 99 | 1 | 175 | 기각 | 사용자 응답은 가장 깔끔하지만 native SQL 이 JPA Repository / EntityManager 1차 캐시를 우회 — 도메인 응집도 하락 |
| [alt-d-redis-setnx](alt-d-redis-setnx/) | D. Redis SETNX 분산 락 | 1 | 99 | 1 | 267 | 기각 (Stage 4 이월) | 단일 노드 단계에서는 Redis 의존성 추가가 과도. 락 잡은 JVM 의 GC pause 가 TTL 보다 길면 fencing 토큰 없이 이중 HELD 위험 |
| [alt-e-skip-locked](alt-e-skip-locked/) (시나리오 1: 특정 좌석) | E. SELECT FOR UPDATE SKIP LOCKED | 1 | 99 | 1 | 87 | 기각 (시나리오 mismatch) | 좌석 100번을 콕 찍어 클릭한 99명에게 "매진" 응답 — 실제로는 winner 가 처리 중이고 매진 아님. 99% false negative |
| [alt-e-skip-locked](alt-e-skip-locked/) (시나리오 2: 임의 좌석) | E. 작업 큐 디스패치 패턴 | 100 | 900 | 100 | 497 | 적용 | "아무 가용 좌석 1개 달라" 시나리오엔 적합. 본 도메인엔 부적합 |
| [alt-f-single-writer-queue](alt-f-single-writer-queue/) | F. 단일 작성자 큐 | 1 | 99 | 1 | 180 | 기각 (Stage 3 이월) | 좌석 충돌 없는 1000명 동시 클릭도 worker 1개가 직렬 처리 — 큐 끝 사용자는 ~2초 대기 (p99 1979ms). 베이스라인의 3배 느림 |
| **stress-baseline** (= 채택 베이스라인) | **Z. 비관적 락 + partial UNIQUE (2단 방어)** | 1 | 999 | 1 | 671 | **채택** | race 차단 OK. 단 부하 한계는 Stage 3로 해결 |

각 대안 디렉터리의 README에 동작 방식 / 장점 / 기각 사유 / 측정 결과 상세 포함.

## 채택 베이스라인의 부하 한계 입증

### stress-baseline (3 시나리오)

| 시나리오 | total | success | seatNotAvailable | connectionTimeout | p99 (ms) | throughput (ops/s) |
|---|---|---|---|---|---|---|
| Hot Seat 1000 동시 | 1000 | 1 | 999 | 0 | 586 | 1490 |
| Distributed 1000×2000 동시 | 2000 | 808 | 1192 | 0 | 1240 | 1385 |
| Pool Exhaustion 500 동시 (pool=10) | 500 | 99 | 401 | 0 | 123 | 3268 |

- race 정합성은 모든 시나리오에서 유지 (heldCount = 1 for hot seat).
- 그러나 p99 latency가 hot seat에서 586ms, distributed에서 1240ms — 사용자 1초+ 대기.
- 풀 고갈은 단일 좌석 hold (외부 호출 없음) 시나리오에선 발생 안 함. 외부 결제 호출이 들어가면 deep-stress 시나리오 4번 (Starvation)에서 즉시 무너짐.

### stress-baseline-deep (6 시나리오 추가 검증)

| # | 실패 모드 | 결과 | 영향 |
|---|---|---|---|
| 1 | JPA persistence-context staleness | PASS (race 차단됨) | 현재 `@Lock + @Query` 패턴 안전 |
| 2 | Deadlock (다중 row + 잘못된 ordering) | **59/60 deadlock**, p99=12.5s | 좌석 묶음 예약 추가 시 즉시 발생 |
| 3 | Lock wait timeout | **56/100 lockTimeout** @ 2초 거절 | 운영 적용 시 `SET LOCAL lock_timeout` 필수 |
| 4 | Long-running tx starvation | p99=2068ms (1 thread만 2s sleep) | 외부 API in tx → hot seat 처리량 0.5 ops/sec |
| 5 | Rollback storm | atomic 유지 (orphan=0) | 단일 노드 OK. 분산 시 outbox 필요 |
| 6 | Connection leak | leak 30개 = pool 100% timeout | 단일 leak 경로로 인스턴스 영구 다운 |

## Stage 3 진입 논리

베이스라인은 race 정합성은 보장하지만 다음 운영급 부하 모드들에 취약하다. 사용자 입장에서 어떻게 노출되는지:

- **풀 고갈** — 사용자가 결제 페이지에서 "결제하기" 를 누르는 순간 PG 호출이 트랜잭션 안에 들어가 다른 좌석을 노리는 사용자 모두에게 connection timeout 응답
- **Deadlock** — 사용자가 "좌석 3장 묶음 예매" 를 누르면 좌석 row 다중 잠금 경로가 활성화 — 본 측정에서 60건 중 59건이 deadlock, p99=12.5s
- **Lock timeout** — 인기 좌석 대기열에서 99명 중 일부는 2초 안에 응답을 못 받고 "잠시 후 다시 시도" 화면 마주침
- **Starvation** — 결제 API 가 평소 2초 걸리는 좌석에서 hot seat 처리량이 0.5 ops/sec 로 떨어져 뒤에 들어온 사용자가 분 단위로 대기
- **Connection leak** — 한 코드 경로의 close 누락만으로 인스턴스 전체가 응답 불가, 전 사용자가 503

→ Stage 3 (대기열) 진입: 사용자가 backend 를 직접 호출하지 않고 대기열을 거치도록 한다 — 화면에 "내 앞 N명" 이 표시되고 backend 가 받을 수 있는 만큼만 통과.
→ Stage 4 (분산 락 + outbox + reconciliation) 진입: 멀티 인스턴스 + 외부 의존 (결제 API 등) 추가 시.

자세한 진입 논리는 [ticketing/docs/stage3-entry-rationale.md](https://github.com/dongwooooooo/ticketing/blob/main/docs/stage3-entry-rationale.md) 참조.

## 실행

```bash
./gradlew :alt-a-partial-unique:test
./gradlew :alt-b-optimistic-lock:test
./gradlew :alt-c-insert-on-conflict:test
./gradlew :alt-d-redis-setnx:test
./gradlew :alt-e-skip-locked:test
./gradlew :alt-f-single-writer-queue:test
./gradlew :stress-baseline:test
./gradlew :stress-baseline-deep:test
./gradlew test  # 전체
```

각 알트 디렉터리의 README에 측정 결과 + 스크린샷 + 실측 토대 결정 근거 포함.
