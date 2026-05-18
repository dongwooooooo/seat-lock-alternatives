# Deep Stress (CAS) — Compare-And-Swap 채택안의 운영 위험 6 시나리오

CAS 채택안이 race 정합성을 보장하더라도 운영 환경의 6종 실패 모드 (cache staleness / deadlock / lock timeout / starvation / rollback storm / connection leak) 에서 어떻게 행동하는지 실측. `stress-baseline-deep` 의 비관적 락 결과와 1:1 비교 대상.

전체 결과: **6/6 PASS** — 모든 시나리오가 실행되어 측정 수치 확보. 일부 시나리오는 비관적 락보다 양호, 일부는 동등, 일부는 다른 양상.

## 시나리오 6종

### C-1. JPA persistence-context staleness
**사용자 행동**: 같은 좌석 10번에 100명이 동시 클릭. 단 코드 경로가 먼저 `findById()` 로 cache warmup 한 후 CAS UPDATE.

**서버 부위**: `reserveWithCacheWarmup()` → `findById()` (persistence context 캐시 적재) → `casHold()` (native UPDATE)

**무엇 때문에**: CAS 는 native UPDATE 라 JPA 1차 캐시 entity 의 status 와 무관하게 DB 의 현재 상태로 판단. 캐시된 snapshot 이 stale ('AVAILABLE') 이어도 native UPDATE 는 DB row 의 현재 status 를 확인.

**사용자가 보는 결과**: 1명 성공, 99명은 "이미 선점됨". 응답 시간 평균 57ms (max 72ms).

**측정**:
- threadCount=100, pool=30
- success=1, seatNotAvailable=99, heldCount=1
- elapsed=75ms, p99=72ms

**판정**: PASS. CAS 가 1차 캐시 영향 없이 race 차단. 비관적 락 시나리오 (p99=91ms, elapsed=99ms) 대비 약간 빠름.

### C-2. Deadlock (다중 row CAS + 잘못된 ordering)
**사용자 행동**: 좌석 20번 + 21번 묶음 예약 (연결석 시나리오). 60명이 동시에 시도하되 30명은 A→B 순서로, 30명은 B→A 순서로 CAS UPDATE 발행. 중간에 50ms 외부 호출 시뮬 sleep.

**서버 부위**: `lockSeatAThenB()` / `lockSeatBThenA()` — UPDATE A → sleep(50) → UPDATE B (와 역순)

**무엇 때문에**: CAS atomic UPDATE 도 commit 까지 row write lock 보유. UPDATE A 가 lock A 를 잡은 채 sleep 50ms 동안 lock B 를 기다리는 thread 가 다수 → PostgreSQL deadlock detector 가 cycle 감지해 한쪽 abort.

**사용자가 보는 결과**: 23명 성공, 36명은 "예약 처리 중 충돌이 발생했습니다 (재시도 안내)", 1명 connectionTimeout. p99=7309ms.

**측정**:
- pairCount=30 (=60 threads), seatA=20, seatB=21
- success=23, deadlock=36, connectionTimeout=1
- p99=7309ms, elapsed=7.311s

**판정**: OBSERVED. CAS 도 deadlock 발생. 비관적 락 시나리오 (success=1, deadlock=59) 보다는 deadlock 빈도가 낮지만 (36 vs 59), success 가 23 으로 크게 증가했다는 점이 본질적 차이. CAS 의 UPDATE 가 빠르게 끝나는 thread 가 많아 직렬 처리되는 비율이 늘어남. 단, deadlock 자체는 multi-row CAS 패턴에서도 여전히 발생 — **lock-free 가 multi-row 일 때까지는 보장되지 않음**.

### C-3. Lock wait timeout
**사용자 행동**: 별도 thread 가 raw JDBC 로 좌석 30번 락을 5초 잡고 있는 사이, 100명이 동시에 좌석 30번 클릭. `SET LOCAL lock_timeout='2s'` 적용.

**서버 부위**: `reserveWithLockTimeout()` — `SET LOCAL lock_timeout='2s'` → `casHold()`

**무엇 때문에**: CAS UPDATE 도 row write lock 을 획득하려고 wait. 다른 thread 의 `SELECT FOR UPDATE` 가 같은 row 락을 5초 보유 → CAS UPDATE 가 2초 후 PostgreSQL `55P03 lock_timeout` 으로 cancel.

**사용자가 보는 결과**: 1명 성공 (holder 풀린 후), 57명은 2초 후 lock timeout, 13명은 풀 고갈로 connection timeout, 29명은 "이미 선점됨" (holder 풀린 후 winner 가 hold 한 뒤 도착).

**측정**:
- threadCount=100, pool=30, lockTimeout=2s, holderDelay=5s
- success=1, lockTimeout=57, connectionTimeout=13, seatNotAvailable=29
- p50=4053ms, p99=5052ms, elapsed=5.057s

**판정**: OBSERVED. CAS 도 lock_timeout 발화. 비관적 락 시나리오 (success=1, lockTimeout=56, connTimeout=14, seatNotAvailable=29) 와 거의 동일. **CAS 가 lock-free 라는 통념과 달리, row write lock 은 여전히 보유** — UPDATE 가 짧을 뿐 락 자체는 있다.

### C-4. Long-running tx starvation
**사용자 행동**: 좌석 40번에 50명이 동시 클릭. winner 의 트랜잭션 안에 2초 sleep (외부 API 호출 시뮬).

**서버 부위**: `SlowReservationService.reserveSlow()` — `casHold()` → sleep(2000) → INSERT → COMMIT

**무엇 때문에**: CAS UPDATE 도 commit 까지 row write lock 보유. winner 가 lock A 를 잡은 채 2초 sleep → 나머지 49 thread 의 casHold UPDATE 가 같은 row 의 write lock 을 기다림. winner commit 후 affected=0 (이미 HELD) → SeatNotAvailableException.

**사용자가 보는 결과**: 1명 성공, 49명은 2초 후 "이미 선점됨". p99=2061ms.

**측정**:
- threadCount=50, pool=30, sleepInTx=2000ms
- success=1, seatNotAvailable=49, heldCount=1
- p99=2061ms, elapsed=2.065s

**판정**: OBSERVED. CAS 도 starvation 발생. 비관적 락 시나리오 (p99=2068ms) 와 거의 동일. **tx 안에서 외부 호출 anti-pattern 은 CAS 채택안에서도 처리량을 0.5 ops/sec/seat 으로 떨어뜨림**. 락 보유 시간이 짧다는 CAS 의 장점이 tx 보유 시간 (외부 호출 포함) 에는 영향 안 줌.

### C-5. Rollback storm
**사용자 행동**: 좌석 50~99번에 각 1명씩 (50명) 클릭. 30% 확률로 reservation INSERT 후 강제 rollback.

**서버 부위**: `FailingReservationService.reserveSometimesFailing()` — `casHold()` → INSERT → flush → 30% throw → tx rollback

**무엇 때문에**: PostgreSQL 이 tx rollback 시 CAS UPDATE 와 reservation INSERT 모두 atomic 하게 복구. seat status 가 'HELD' → 'AVAILABLE' 로 자동 복귀.

**사용자가 보는 결과**: 38명 성공 (좌석 HELD), 12명은 의도된 실패 (좌석 AVAILABLE 로 복귀). orphan 0건.

**측정**:
- seatCount=50, failRate=0.30
- success=38, intentionalRollback=12
- heldSeats=38, availableSeats=12, reservationRows(HELD)=38, **orphanCount=0**
- elapsed=33ms

**판정**: PASS. CAS UPDATE 도 tx 종료 시 atomic 복구. 비관적 락 시나리오 (success=35, rollback=15, orphan=0) 와 동일 결과. **단일 노드 + 단일 tx 범위에서는 CAS 도 atomicity 보장.**

### C-6. Connection leak
**사용자 행동**: 코드 규율 항목. 정상 사용자 행동 아님 — 개발자 실수 시뮬.

**서버 부위**: `LeakyService.acquireWithoutClose()` — `dataSource.getConnection()` 후 close 누락

**무엇 때문에**: HikariCP 가 leak 30회 누적 시 풀 영구 고갈. 추가 `getConnection()` 요청은 5초 connection timeout.

**사용자가 보는 결과**: 모든 후속 요청이 connection timeout (5초 대기 후 503).

**측정**:
- pool=30, leakCount=30 (의도적으로 풀 크기만큼)
- probe success=0, **probe timeout=5/5**
- HikariCP leak detection 5s threshold → ProxyLeakTask 경고 30건 stack trace 로그

**판정**: OBSERVED. CAS 채택안과 무관하게 leak anti-pattern 단일 경로로 인스턴스 영구 다운. 비관적 락 시나리오 (timeout=5/5) 와 동일.

## 종합

| # | 실패 모드 | CAS 결과 | 비관적 락 결과 | 차이 |
|---|---|---|---|---|
| 1 | JPA cache staleness | PASS, race 차단됨, p99=72ms | PASS, p99=91ms | CAS 가 약간 빠름 |
| 2 | Deadlock (multi-row) | OBSERVED, deadlock=36, success=23 | OBSERVED, deadlock=59, success=1 | CAS 가 deadlock ↓ (-39%), success ↑ (23배). 그러나 lock-free 아님 |
| 3 | Lock timeout | OBSERVED, lockTimeout=57 | OBSERVED, lockTimeout=56 | 동일 — CAS 도 row write lock 보유 |
| 4 | Long tx starvation | OBSERVED, p99=2061ms | OBSERVED, p99=2068ms | 동일 — tx 안 외부 호출은 CAS 도 막지 못함 |
| 5 | Rollback storm | PASS, orphan=0 | PASS, orphan=0 | 동일 — atomic tx 보장 |
| 6 | Connection leak | OBSERVED, probe 100% timeout | OBSERVED, probe 100% timeout | 동일 — 코드 규율 항목 |

## 핵심 발견

**CAS 가 우세한 부분**:
- C-2 deadlock: success 1 → 23 (직렬 처리 비율 증가, deadlock 발생률 감소). 단 lock-free 는 아님.
- C-1 cache staleness: native UPDATE 가 JPA 1차 캐시 우회. baseline 보다 약간 빠름.

**CAS 가 변화 없는 부분**:
- C-3 lock timeout: CAS UPDATE 도 row write lock 획득 필요. holder 가 row 락 잡고 있으면 동일하게 wait + lock_timeout 발화.
- C-4 starvation: tx 안 외부 호출은 락 보유 시간이 아니라 tx 보유 시간이 문제. CAS 도 동일.
- C-5 rollback: 단일 tx atomicity 는 두 채택안 모두 보장.
- C-6 leak: 코드 규율 항목.

## 다음 단계

CAS 가 hot-seat 정합성 + 처리량 양쪽에서 비관적 락 대비 우세. 단 다음 한계는 동일:

- **multi-row CAS 는 여전히 deadlock 가능** → 좌석 묶음 예약 추가 시 lock ordering 강제 + 재시도 정책 필요
- **tx 안 외부 호출은 CAS 도 못 막음** → 외부 호출 (PG 결제) 은 반드시 tx 밖으로 분리
- **풀 고갈 / leak 은 채택안 무관** → 코드 규율 + leakDetectionThreshold 유지

Stage 3 (대기열) / Stage 4 (분산) 진입 논리는 그대로 유효.

자세한 비교: [../CAS-vs-PESSIMISTIC.md](../CAS-vs-PESSIMISTIC.md)
