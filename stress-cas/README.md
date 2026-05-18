# Stress Test (CAS) — Compare-And-Swap 채택안의 race 정합성 + 부하 한계

## 질문
"비관적 락 (`SELECT FOR UPDATE`) 을 빼고 atomic UPDATE (CAS) 로만 좌석 선점을 막아도 race 정합성이 유지되는가? 그리고 같은 부하에서 비관적 락 대비 처리량·latency 는 어떻게 달라지는가?"

## 답
**race 정합성 유지 + 동일 부하에서 throughput·latency 모두 우세.** 락 보유 시간이 tx 전체 → UPDATE 실행 (~1ms) 으로 줄면서 hot seat 1000 동시 시나리오 p99 가 비관적 락 586ms → CAS 192ms (67% 감소) 로 떨어졌다.

## 채택안 메커니즘 (vs 비관적 락)

| 단계 | 비관적 락 (이전) | CAS (신규) |
|---|---|---|
| 1차 차단 | `SELECT ... FOR UPDATE` + status 검사 | `UPDATE seat SET status='HELD' WHERE id=? AND status='AVAILABLE'` (affected=1?) |
| 2차 (최후 그물) | partial UNIQUE | partial UNIQUE (동일) |
| Row lock 보유 시간 | tx 전체 (BEGIN→COMMIT) | UPDATE 실행 (~1ms) |
| 도메인 응집도 | `seat.hold()` 비즈니스 메서드 거침 | native UPDATE (메서드 우회) |

```java
@Transactional
public Reservation reserve(Long seatId, String userId) {
    int updated = seatRepository.casHold(seatId);
    if (updated == 0) {
        throw new SeatNotAvailableException("seat " + seatId + " already HELD");
    }
    try {
        return reservationRepository.save(Reservation.create(seatId, userId, HOLD_DURATION));
    } catch (DataIntegrityViolationException e) {
        seatRepository.casRelease(seatId);  // partial UNIQUE 위반 시 복구
        throw new SeatNotAvailableException("seat " + seatId + " UNIQUE conflict");
    }
}
```

## 시나리오 3종 (사용자 행동 형식)

### B-1. Hot Seat 1000 동시
- **사용자 행동**: BTS 콘서트 11:00:00 정각, R열 1번 (VIP) 좌석에 1000명이 동시에 클릭
- **서버 부위**: `ReservationService.reserve()` → `SeatRepository.casHold()` (atomic UPDATE seat SET status='HELD' WHERE id=1 AND status='AVAILABLE')
- **무엇 때문에**: PostgreSQL atomic UPDATE 가 row write lock 을 ~1ms 단위로 잡고 풂. 1명만 affected=1, 999명은 affected=0
- **사용자가 보는 결과**: 1명은 결제 페이지로 (~190ms 내), 999명은 "이미 선점됨" 메시지를 평균 126ms (최대 208ms) 내에 받음

### B-2. Distributed 1000 좌석 × 2000 동시 (hot 20% 집중)
- **사용자 행동**: 콘서트 오픈 시점, 2000명 사용자 중 400명이 1번 좌석에 몰리고 1600명은 1~1000번 좌석에 분산 클릭
- **서버 부위**: 좌석별 row 가 다르므로 atomic UPDATE 가 서로 충돌 안 함. 1번 좌석만 직렬화
- **무엇 때문에**: CAS 의 락 보유 시간이 짧아 풀=10 으로도 분산 좌석 처리가 막힘 없이 회전
- **사용자가 보는 결과**: 808명 (전체 좌석 1000개 중 808개 좌석에 1명씩) 성공, 1192명은 "이미 선점됨". p99=782ms

### B-3. Pool Exhaustion 좌석 100 × 동시 500 (pool=10)
- **사용자 행동**: 500명이 100개 좌석 중 임의 좌석에 동시 클릭
- **서버 부위**: HikariCP `getConnection()` (pool=10) → casHold UPDATE → INSERT → COMMIT
- **무엇 때문에**: tx 보유 시간이 매우 짧아 (단순 UPDATE+INSERT) 풀 회전이 빠름. 500 동시여도 connectionTimeout=0
- **사용자가 보는 결과**: 99명 (100좌석 중 99좌석) 성공, 401명은 "이미 선점됨". 100ms 내 응답

## 측정 결과

| 시나리오 | total | success | seatNotAvailable | dataIntegrityViolation | connectionTimeout | p50 | p95 | p99 | max | throughput |
|---|---|---|---|---|---|---|---|---|---|---|
| B-1 Hot Seat 1000 | 1000 | 1 | 999 | 0 | 0 | 126 | 183 | 192 | 208 | 4219.4 ops/s |
| B-2 Distributed 1000×2000 | 2000 | 808 | 1192 | 0 | 0 | 547 | 705 | 782 | 796 | 2249.7 ops/s |
| B-3 Pool Exhaustion 500 (pool=10) | 500 | 99 | 401 | 0 | 0 | 70 | 100 | 106 | 117 | 3571.4 ops/s |

heldCount: Hot=1, Distributed=808 (= success), Pool=99 (= success).
elapsed: B-1 0.237s · B-2 0.889s · B-3 0.140s.

## 비관적 락 채택안 대비 비교

| 시나리오 | 지표 | 비관적 락 | CAS | 차이 |
|---|---|---|---|---|
| B-1 Hot Seat 1000 | p99 (ms) | 586 | 192 | **-67%** |
| B-1 Hot Seat 1000 | throughput (ops/s) | 1490 | 4219 | **+183%** |
| B-2 Distributed 1000×2000 | p99 (ms) | 1240 | 782 | **-37%** |
| B-2 Distributed 1000×2000 | throughput (ops/s) | 1385 | 2250 | **+62%** |
| B-3 Pool Exhaustion 500 | p99 (ms) | 123 | 106 | -14% |
| B-3 Pool Exhaustion 500 | throughput (ops/s) | 3268 | 3571 | +9% |
| 모두 | success (race 정합성) | 동일 | 동일 | 동일 |
| 모두 | heldCount | 1 (hot) | 1 (hot) | 동일 |

### 관찰 포인트

- **race 차단 동일**: Hot Seat 1000 동시에서도 CAS 가 heldCount=1, success=1 유지. atomic UPDATE 가 비관적 락과 동일하게 race 를 자른다.
- **hot seat 처리량 ~3배**: 락 보유 시간이 tx 전체 → ~1ms 로 줄면서 row 회전이 빨라짐. 같은 좌석에 몰린 사용자에게 응답이 빠르게 떨어짐.
- **분산 좌석은 차이 작음**: 좌석별 row 가 다르면 비관적 락도 직렬화 안 됨. 그러나 hot 20% 부분 때문에 여전히 CAS 가 우세.
- **풀 회전 무차이**: B-3 에서 두 채택안 모두 connectionTimeout=0. 부하가 풀 한계를 넘지 않음.

## 실행

```bash
./gradlew :stress-cas:test --info
```

전 테스트 PASS. 실측 elapsed 약 1초 (3 시나리오 합).

## 구성 파일

- `src/main/resources/application.yml` — `maximum-pool-size: 10`, `connection-timeout: 3000` (baseline 과 동일)
- `src/main/resources/db/migration/V1__init.sql` — seat 1000개 + partial UNIQUE index (baseline 과 동일)
- `src/main/java/com/dongwoo/stresscas/service/ReservationService.java` — CAS atomic UPDATE + partial UNIQUE 복구
- `src/main/java/com/dongwoo/stresscas/repository/SeatRepository.java` — `casHold()` / `casRelease()` native query

## 비교 문서

상세 비교는 [../CAS-vs-PESSIMISTIC.md](../CAS-vs-PESSIMISTIC.md) 참조.
