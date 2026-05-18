# CAS vs 비관적 락 채택안 비교 — 9 시나리오 실측

본 문서는 좌석 동시 선점 차단의 두 채택안을 **동일 조건 9 시나리오** (정합성 3 + 운영 위험 6) 로 실측한 비교 결과다.

- 이전 채택: **비관적 락 + partial UNIQUE** (`stress-baseline` / `stress-baseline-deep`)
- 신규 채택: **CAS (Compare-And-Swap, atomic UPDATE) + partial UNIQUE** (`stress-cas` / `stress-cas-deep`)

## 0. 두 채택안의 메커니즘 차이

| 항목 | 비관적 락 (이전 채택) | CAS (신규 채택) |
|---|---|---|
| 1차 차단 | `SELECT ... FOR UPDATE` + status 검사 | `UPDATE seat SET status='HELD' WHERE id=? AND status='AVAILABLE'` (affected=1?) |
| 2차 (최후) | partial UNIQUE index on `reservation(seat_id) WHERE status IN ('HELD','PAID')` | 동일 |
| 락 보유 시간 | tx 전체 (BEGIN → COMMIT) | UPDATE 실행 (~1ms). 단 row write lock 자체는 commit 까지 보유 |
| 도메인 응집도 | `seat.hold()` 비즈니스 메서드 거침 | native UPDATE (메서드 우회) |
| lock-free? | No (SELECT FOR UPDATE 명시) | **Lock-free 명시 아님.** atomic UPDATE 도 row lock 사용. UPDATE 가 빨라 보유 시간이 짧을 뿐 |
| deadlock 가능성 | multi-row 시 발생 | multi-row 시 발생 (단 빈도 ↓) |

### 코드 비교

비관적 락:
```java
@Transactional
public Reservation reserve(Long seatId, String userId) {
    Seat seat = seatRepository.findByIdForUpdate(seatId)  // SELECT FOR UPDATE (락 획득)
            .orElseThrow(...);
    if (seat.getStatus() != SeatStatus.AVAILABLE) {
        throw new SeatNotAvailableException(...);
    }
    seat.hold();
    seatRepository.save(seat);  // UPDATE
    try {
        return reservationRepository.save(Reservation.create(...));  // INSERT
    } catch (DataIntegrityViolationException e) {
        throw new SeatNotAvailableException(...);  // partial UNIQUE 최후 그물
    }
}
```

CAS:
```java
@Transactional
public Reservation reserve(Long seatId, String userId) {
    int updated = seatRepository.casHold(seatId);  // atomic UPDATE
    if (updated == 0) {
        throw new SeatNotAvailableException(...);
    }
    try {
        return reservationRepository.save(Reservation.create(...));  // INSERT
    } catch (DataIntegrityViolationException e) {
        seatRepository.casRelease(seatId);  // partial UNIQUE 위반 시 status 복구
        throw new SeatNotAvailableException(...);
    }
}
```

## 1. 정합성 시나리오 (B-1, B-2, B-3) 비교

### B-1. Hot Seat 1000 동시

- **사용자 행동**: BTS 콘서트 11:00:00 정각, R열 1번 (VIP) 좌석에 1000명이 동시에 클릭
- **서버 부위**: ReservationService.reserve() → (비관적: SELECT FOR UPDATE / CAS: atomic UPDATE)
- **무엇 때문에**: 1번 row 에 1개의 락. 1명만 commit, 나머지 999명은 거절
- **사용자가 보는 결과**: 1명은 결제 페이지로, 999명은 "이미 선점됨" 메시지

| 지표 | 비관적 락 | CAS | 차이 |
|---|---|---|---|
| success | 1 | 1 | 동일 |
| seatNotAvailable | 999 | 999 | 동일 |
| heldCount (DB) | 1 | 1 | 동일 |
| p50 (ms) | 335 | 126 | **-62%** |
| p95 (ms) | 570 | 183 | **-68%** |
| p99 (ms) | 586 | 192 | **-67%** |
| max (ms) | 645 | 208 | **-68%** |
| throughput (ops/s) | 1490 | 4219 | **+183%** |

**의미**: race 정합성은 동일 (heldCount=1). 락 보유 시간이 tx 전체 → ~1ms 로 단축되면서 hot seat 대기 큐가 짧아짐. 사용자 응답 시간 평균 335ms → 126ms (62% 감소).

### B-2. Distributed 1000 좌석 × 2000 동시 (hot 20% 집중)

- **사용자 행동**: 콘서트 오픈, 2000명 중 400명이 1번 좌석에, 1600명은 1~1000번 분산 클릭
- **서버 부위**: 좌석별 row 가 다르므로 분산. 1번 좌석만 직렬화
- **무엇 때문에**: hot seat 400명 + 분산 1600명. CAS 가 hot seat 회전을 빠르게 처리
- **사용자가 보는 결과**: 분산 좌석은 거의 모두 성공, hot 1번 좌석은 1명만 성공

| 지표 | 비관적 락 | CAS | 차이 |
|---|---|---|---|
| success | 808 | 808 | 동일 |
| seatNotAvailable | 1192 | 1192 | 동일 |
| p50 (ms) | 938 | 547 | **-42%** |
| p95 (ms) | 1184 | 705 | **-40%** |
| p99 (ms) | 1240 | 782 | **-37%** |
| throughput (ops/s) | 1385 | 2250 | **+62%** |

**의미**: 같은 분포 (seed=42 동일) 에서 success/rejection 결과 동일. p99 가 1.24s → 0.78s 로 단축. 사용자 응답 1초 이상 대기 → 1초 이하로 개선.

### B-3. Pool Exhaustion 좌석 100 × 동시 500 (pool=10)

- **사용자 행동**: 500명이 100개 좌석 중 임의 좌석에 동시 클릭
- **서버 부위**: HikariCP `getConnection()` (pool=10) → 좌석 hold → INSERT → COMMIT
- **무엇 때문에**: 풀이 작아 풀 회전 측정. 두 채택안 모두 tx 가 짧아 풀 timeout 발생 안 함
- **사용자가 보는 결과**: 좌석당 1명씩 성공, 나머지는 "이미 선점됨"

| 지표 | 비관적 락 | CAS | 차이 |
|---|---|---|---|
| success | 99 | 99 | 동일 |
| connectionTimeout | 0 | 0 | 동일 |
| p99 (ms) | 123 | 106 | -14% |
| throughput (ops/s) | 3268 | 3571 | +9% |

**의미**: 풀 한계 이내 부하에서는 두 채택안 모두 안전. 비교 차이가 가장 작은 시나리오.

## 2. 운영 위험 시나리오 (C-1 ~ C-6) 비교

### C-1. JPA persistence-context staleness

- **사용자 행동**: 같은 좌석 10번에 100명 동시 클릭. 단 코드 경로가 먼저 `findById()` 캐시 적재 후 락 또는 CAS
- **서버 부위**: ReservationService.reserveWithCacheWarmup() — JPA 캐시 후 락/CAS
- **무엇 때문에**: 비관적 락은 Hibernate 가 캐시된 entity 에도 SELECT FOR UPDATE 발행 (lock upgrade). CAS 는 native UPDATE 라 캐시 영향 없음
- **사용자가 보는 결과**: 1명 성공, 99명은 "이미 선점됨"

| 지표 | 비관적 락 | CAS | 차이 |
|---|---|---|---|
| success | 1 | 1 | 동일 |
| heldCount | 1 | 1 | 동일 |
| p99 (ms) | 91 | 72 | -21% |
| elapsed (ms) | 99 | 75 | -24% |

**의미**: 두 채택안 모두 cache staleness 영향 없이 race 차단. CAS 가 native UPDATE 라 약간 빠름.

### C-2. Deadlock (multi-row + 잘못된 ordering)

- **사용자 행동**: 좌석 20번 + 21번 묶음 예약 시도. 60명이 동시, 30명은 A→B 순서, 30명은 B→A 순서. 중간 50ms 외부 호출 시뮬
- **서버 부위**: DeadlockReservationService.lockSeatAThenB / lockSeatBThenA — 좌석 락 2개 + 50ms sleep
- **무엇 때문에**: 두 thread 가 역순으로 락 진입 → cycle 형성 → PostgreSQL deadlock detector 가 한쪽 abort
- **사용자가 보는 결과**: 일부 성공, 다수는 "예약 처리 중 충돌이 발생했습니다"

| 지표 | 비관적 락 | CAS | 차이 |
|---|---|---|---|
| success | 1 | 23 | **+22배** |
| deadlock | 59 | 36 | **-39%** |
| connectionTimeout | 0 | 1 | +1 |
| p99 (ms) | 12523 | 7309 | **-42%** |
| elapsed (s) | 12.5 | 7.3 | **-42%** |

**의미**: **CAS 도 multi-row 시 deadlock 발생.** 단 비관적 락 대비 deadlock 빈도 ↓, success ↑ (UPDATE 가 빠르게 끝나는 thread 가 많아 직렬 처리 비율 증가). 핵심 발견 — **CAS = lock-free 라는 통념은 single-row 일 때만 성립**.

### C-3. Lock wait timeout

- **사용자 행동**: 별도 thread 가 raw JDBC 로 좌석 30번 락을 5초 보유. 그 사이 100명이 좌석 30번 동시 클릭. `SET LOCAL lock_timeout='2s'`
- **서버 부위**: 락/CAS 가 같은 row 의 write lock 대기 → 2초 후 lock_timeout
- **무엇 때문에**: row write lock 은 어느 채택안이든 보유 필요. holder 가 5초 잡고 있으면 둘 다 2초 후 거절
- **사용자가 보는 결과**: 1명 성공 (holder 풀린 후), 다수는 2초 후 "시스템 처리 지연"

| 지표 | 비관적 락 | CAS | 차이 |
|---|---|---|---|
| success | 1 | 1 | 동일 |
| lockTimeout | 56 | 57 | +1 |
| connectionTimeout | 14 | 13 | -1 |
| seatNotAvailable | 29 | 29 | 동일 |
| p99 (ms) | 5088 | 5052 | 동일 |

**의미**: **CAS 도 lock_timeout 발화.** "CAS = lock-free 이므로 lock_timeout 불필요"는 오해. atomic UPDATE 도 row write lock 획득 필요. 두 채택안 결과 거의 동일.

### C-4. Long-running tx starvation

- **사용자 행동**: 좌석 40번에 50명 동시 클릭. winner 의 tx 안에 2초 sleep (외부 API 시뮬)
- **서버 부위**: SlowReservationService.reserveSlow — 락/CAS 후 sleep(2000) → INSERT → COMMIT
- **무엇 때문에**: tx 안에서 외부 호출 = tx 보유 시간 = lock 보유 시간 (commit 까지). winner 외 49명 wait
- **사용자가 보는 결과**: 1명 성공, 49명은 2초 후 "이미 선점됨"

| 지표 | 비관적 락 | CAS | 차이 |
|---|---|---|---|
| success | 1 | 1 | 동일 |
| seatNotAvailable | 49 | 49 | 동일 |
| p50 (ms) | 2055 | 2038 | 동일 |
| p99 (ms) | 2068 | 2061 | 동일 |
| elapsed (s) | 2.07 | 2.06 | 동일 |

**의미**: **CAS 도 starvation 발생.** tx 안 외부 호출 anti-pattern 은 락 보유 시간이 아니라 tx 보유 시간이 본질. CAS 가 UPDATE 자체는 1ms 라도 commit 까지 2초가 걸리면 49명이 그 시간만큼 대기. **외부 호출은 반드시 tx 밖으로 분리 필요** — 두 채택안 모두에 적용.

### C-5. Rollback storm

- **사용자 행동**: 50개 좌석에 각 1명씩 클릭. 30% 확률로 INSERT 후 강제 rollback
- **서버 부위**: FailingReservationService — 락/CAS + INSERT + flush + 30% throw → rollback
- **무엇 때문에**: PostgreSQL 이 tx 종료 시 UPDATE/INSERT 모두 atomic 복구
- **사용자가 보는 결과**: 일부는 성공, 30% 는 의도된 실패. orphan 없음

| 지표 | 비관적 락 | CAS | 차이 |
|---|---|---|---|
| success | 35 | 38 | +9% (확률성) |
| intentionalRollback | 15 | 12 | -20% (확률성) |
| orphanCount | 0 | 0 | 동일 |
| heldSeats | 35 | 38 | (success 와 일치) |

**의미**: 두 채택안 모두 atomicity 보장. CAS UPDATE 도 rollback 시 status 원복. **단일 노드 + 단일 tx 범위에서만 보장** — 분산 확장 시 outbox 패턴 필요 (두 채택안 동일).

### C-6. Connection leak

- **사용자 행동**: 코드 규율 항목. 사용자 행동 아님 — 개발자 실수 시뮬 (`getConnection()` 후 close 누락)
- **서버 부위**: LeakyService.acquireWithoutClose() — HikariCP 풀에서 connection 임대 후 leak
- **무엇 때문에**: 풀 크기만큼 leak 누적 시 풀 영구 고갈. HikariCP 5s leak detection 경고
- **사용자가 보는 결과**: 모든 후속 요청 503 (connection timeout)

| 지표 | 비관적 락 | CAS | 차이 |
|---|---|---|---|
| probe timeout | 5/5 | 5/5 | 동일 |
| probe success | 0 | 0 | 동일 |

**의미**: 채택안과 무관한 코드 규율 항목. 두 채택안 동일하게 단일 leak 경로로 인스턴스 영구 다운.

## 3. 핵심 발견 사항

| 발견 | 정량 | 시사점 |
|---|---|---|
| Hot seat 처리량 ~3배 | p99 586ms → 192ms (-67%), throughput +183% | CAS 채택 시 hot seat 시나리오에서 사용자 체감 응답 크게 개선 |
| Distributed p99 ~40% 감소 | 1240ms → 782ms | hot 20% 가 섞인 현실 부하에서도 유의미한 개선 |
| Deadlock: lock-free 아님 | 비관적 락 59 → CAS 36 (-39%), 단 0 은 아님 | multi-row CAS 도 deadlock 가능. lock ordering 강제 + 재시도 정책 필요 |
| Lock timeout: 동일 발화 | lockTimeout=56 vs 57, 거의 동일 | CAS = lock-free 라는 통념은 single-row 만. row write lock 자체는 두 채택안 모두 보유 |
| Starvation: 동일 | p99 2068ms vs 2061ms | tx 안 외부 호출은 락 메커니즘 무관 — tx 보유 시간이 본질 |
| Rollback atomicity | 두 채택안 모두 orphan=0 | atomicity 는 PostgreSQL tx 가 보장. 채택안 무관 |
| Connection leak | 두 채택안 모두 100% timeout | 코드 규율 항목. 채택안 무관 |

## 4. 결론 + 채택 결정

### 정합성

두 채택안 모두 hot seat 1000 동시, distributed 2000 동시, pool exhaustion 500 동시 모든 시나리오에서 heldCount=1 (hot) / success=heldCount (distributed) 유지. race 정합성은 **동일하게 보장**.

### 부하 처리

| 시나리오 | CAS 이득 |
|---|---|
| Hot seat 1000 동시 | p99 -67%, throughput +183% (대형 우세) |
| Distributed 2000 동시 | p99 -37%, throughput +62% (중간 우세) |
| Pool exhaustion 500 동시 | p99 -14% (작은 우세) |

### 운영 위험

| 위험 | CAS vs 비관적 락 |
|---|---|
| JPA cache staleness | CAS 가 약간 빠름 (native UPDATE) |
| Deadlock | CAS 가 빈도 ↓ (-39%) 이지만 lock-free 아님 |
| Lock timeout | 동일 — row write lock 보유 |
| Long tx starvation | 동일 — tx 보유 시간이 본질 |
| Rollback atomicity | 동일 — PostgreSQL tx 보장 |
| Connection leak | 동일 — 코드 규율 |

### Trade-off

| 항목 | 비관적 락 | CAS |
|---|---|---|
| 처리량·latency | 낮음 | **+62~183%** |
| 도메인 응집도 | seat.hold() 비즈니스 메서드 통과 | **native UPDATE 가 메서드 우회** (entity 의 상태 변경 의도가 SQL 직접 호출에 흩어짐) |
| 의도 표현력 | JPA 표준 + Lock 어노테이션 | native SQL 주석 + affected-rows 검사 코드 필요 |
| multi-row 처리 | 동일 (lock ordering 필수) | 동일 (lock ordering 필수) |

### 채택 권고

**CAS 채택 권고.** 근거:
- race 정합성은 비관적 락과 동등하며 partial UNIQUE 가 최후 그물로 동일하게 작동
- hot seat 시나리오 (티켓팅 도메인의 본질 부하) 에서 p99 -67%, throughput +183% 개선 — 사용자 체감 응답 시간 직접 영향
- 운영 위험 6 시나리오에서 CAS 가 손해 보는 항목 없음 (동일하거나 약간 우세)
- 도메인 응집도 하락은 ReservationService.reserve() 한 메서드에 국한. seat.hold() 호출은 read-after-write 검증·테스트에서만 사용

### 한계 (두 채택안 공통)

- multi-row 시나리오 (좌석 묶음 예약) 에서 deadlock 가능 → lock ordering 강제 + 재시도 정책 필요
- tx 안 외부 호출 (PG 결제) 은 처리량 0.5 ops/sec/seat 으로 떨어뜨림 → 외부 호출 tx 밖 분리 필요
- Stage 3 (대기열) / Stage 4 (분산 락 + outbox) 진입 논리는 변함 없이 유효

## 측정 환경

- 도구: Spring Boot Test + JUnit 5 + Testcontainers (PostgreSQL 16)
- HW: macOS Darwin 25.2.0 (실측 환경 단일 머신)
- 실행 시간: stress-cas (~7s) + stress-cas-deep (~32s, leak 시나리오의 5s sleep 포함)
- 동일 좌석 분포 (seed=42), 동일 풀 크기 (10 정합성, 30 운영), 동일 partial UNIQUE 스키마

## 첨부 자료

- 비관적 락 채택안: [stress-baseline/](stress-baseline/) · [stress-baseline-deep/](stress-baseline-deep/)
- CAS 채택안: [stress-cas/](stress-cas/) · [stress-cas-deep/](stress-cas-deep/)
- 측정 raw 출력: `stress-cas-test-output.txt` · `stress-cas-deep-test-output.txt`
