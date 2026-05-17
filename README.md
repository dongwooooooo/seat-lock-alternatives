# seat-lock-alternatives

[ticketing](https://github.com/dongwooooooo/ticketing) §5 (좌석 동시 선점 차단) 의 **6가지 대안 + 채택 베이스라인 + 추가 부하 입증**을 실제 코드와 측정으로 검증한 레포.

## 결론 요약

- **6가지 대안 (A~F)** : 좌석당 정합성은 모두 달성되지만 각자 운영상 단점이 있다 → 기각.
- **채택**: 비관적 락 + partial UNIQUE (2단 방어, alt-z = stress-baseline 모듈).
- **그러나** Stage 2 베이스라인 단독으로는 50K 좌석 × 20만 동시 환경에서 풀 고갈·deadlock·lock timeout·starvation·connection leak 5종이 운영급 부하에서 재현됨 → **Stage 3 (대기열) 진입 정당화**.

## 실험 시나리오 (대안 비교 공통)

- 좌석 1번에 동시 100건의 예매 요청
- `ExecutorService(100)` + `CountDownLatch` 시작 게이트
- 합격선: 정확히 1건만 통과 (heldCount = 1, 좌석 중복 없음)

## 대안 측정 결과

| 디렉터리 | 대안 | success | rejected | heldCount | elapsedMs | 채택 여부 | 핵심 기각 사유 |
|---|---|---|---|---|---|---|---|
| [alt-a-partial-unique](alt-a-partial-unique/) | A. partial UNIQUE 단독 (락 없음) | 1 | 99 | 1 | 138 | 기각 | 99건 모두 트랜잭션 BEGIN+UPDATE+INSERT까지 도달, 23505 violation 로그 폭주 |
| [alt-b-optimistic-lock](alt-b-optimistic-lock/) | B. 낙관적 락 (@Version) | 1 | 99 | 1 | 122 | 기각 | hot seat 시나리오에서 retry storm — 99건이 SELECT+UPDATE 반복 |
| [alt-c-insert-on-conflict](alt-c-insert-on-conflict/) | C. INSERT ON CONFLICT DO NOTHING | 1 | 99 | 1 | 175 | 기각 | JPA 우회 + persistence-context 캐시 불일치 위험 + native SQL 종속 |
| [alt-d-redis-setnx](alt-d-redis-setnx/) | D. Redis SETNX 분산 락 | 1 | 99 | 1 | 267 | 기각 (Stage 4 이월) | 단일 노드에서 over-engineering. Kleppmann fencing 없으면 GC pause에 이중 HELD |
| [alt-e-skip-locked](alt-e-skip-locked/) (시나리오 1: 특정 좌석) | E. SELECT FOR UPDATE SKIP LOCKED | 1 | 99 | 1 | 87 | 기각 (시나리오 mismatch) | 본 도메인은 "특정 좌석" 요청 — SKIP된 99건이 false negative ("매진" 응답) |
| [alt-e-skip-locked](alt-e-skip-locked/) (시나리오 2: 임의 좌석) | E. 작업 큐 디스패치 패턴 | 100 | 900 | 100 | 497 | 적용 | 다른 도메인(작업 큐)에는 적합. 본 도메인엔 부적합 |
| [alt-f-single-writer-queue](alt-f-single-writer-queue/) | F. 단일 작성자 큐 | 1 | 99 | 1 | 180 | 기각 (Stage 3 이월) | worker 1개 직렬 처리로 throughput hard cap (1000 좌석 1000 스레드 = 493 ops/sec, p99=1979ms) |
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

베이스라인은 race 정합성은 보장하지만 다음 운영급 부하 모드들에 취약:
- **풀 고갈** — 외부 결제 호출이 트랜잭션 안에 들어가는 순간 즉시 발동
- **Deadlock** — 좌석 묶음/이동/교환 기능 추가 시 통계적으로 다수 발생
- **Lock timeout** — 2초 대기 거절 정책 없으면 사용자 대기 폭주
- **Starvation** — critical section 안 외부 호출 = 좌석당 처리량 0.5 ops/sec
- **Connection leak** — 단일 close 누락 경로로 인스턴스 영구 다운

→ Stage 3 (대기열) 진입: 클라이언트가 backend를 직접 호출하지 않고 대기열을 거치도록 한다.
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
