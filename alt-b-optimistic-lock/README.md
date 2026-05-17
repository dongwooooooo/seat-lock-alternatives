# 대안 B — 낙관적 락 (@Version)

## 동작 방식

좌석 row에 `version BIGINT` 컬럼을 추가하고 JPA `@Version`을 붙인다.

수정 시 JPA가 자동으로 다음 SQL을 발사한다.

```sql
UPDATE seat
SET status = ?, version = version + 1, updated_at = ?
WHERE id = ? AND version = ?
```

`WHERE version = ?` 절이 핵심이다. 동시에 두 스레드가 같은 version으로 UPDATE 보내면 한쪽만 affected rows = 1, 나머지는 0건이 되어 Hibernate가 `ObjectOptimisticLockingFailureException`을 던진다.

이 예외를 잡아서 retry 루프(최대 10회) 안에서 다시 시도한다.

## 장점 (이론)

- DB 락(`SELECT FOR UPDATE`) 없이 동시성 차단
- 락 보유 시간 0 → 외부 호출 잠시 들어가도 cascade 발생 안 함
- 충돌 빈도가 낮은 시나리오(좌석 1000개 중 한두 개에만 동시 접근)에서 락 오버헤드 절약

## 기각 사유 (이론) — 매진 시 retry storm

티켓팅의 핵심 시나리오는 "한 좌석에 100명이 동시에 몰림"이다. 이 경우 낙관적 락은 가장 안 좋은 패턴이 된다.

1. 100건이 동시에 `SELECT seat WHERE id=100` → 모두 version=0 으로 entity 로드
2. 100건이 동시에 `UPDATE seat SET status='HELD', version=1 WHERE id=100 AND version=0` 발사
3. 1건만 성공, 99건은 `OptimisticLockException`
4. 99건이 retry → 다시 `SELECT seat WHERE id=100` (이미 HELD) → status 체크에서 reject

retry 한 번마다 SELECT + UPDATE 두 번이 DB에 도달한다. 매진 좌석일수록 retry가 wasted DB 트래픽이 된다.

## 구현 코드 (핵심)

### Seat.java — @Version 필드

```java
@Version
@Column(nullable = false)
private Long version;
```

### ReservationService.java — retry loop

```java
public Reservation reserve(Long seatId, String userId) {
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
        try {
            return reserveOnce(seatId, userId);
        } catch (ObjectOptimisticLockingFailureException e) {
            totalRetryAttempts.incrementAndGet();
            if (attempt == MAX_RETRIES) {
                gaveUpCount.incrementAndGet();
                throw new SeatNotAvailableException("...");
            }
        }
    }
    throw new IllegalStateException("unreachable");
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public Reservation reserveOnce(Long seatId, String userId) {
    Seat seat = seatRepository.findById(seatId).orElseThrow(...);
    if (seat.getStatus() != SeatStatus.AVAILABLE) {
        statusRejectCount.incrementAndGet();
        throw new SeatNotAvailableException("...");
    }
    seat.hold();
    seatRepository.saveAndFlush(seat); // flush 강제 → OLE 즉시 발생
    return reservationRepository.save(Reservation.create(...));
}
```

retry 도중 좌석 status가 이미 HELD로 바뀌어 있으면 status 체크에서 빠르게 reject된다(`statusRejects`로 카운트). 이게 retry storm을 약하게 만든 요인 — 백오프 없이도 좌석 1건 시나리오에서는 99건이 첫 retry에서 status 체크에 걸려 종료된다.

## 실측 결과

100 스레드 동시 진입, MAX_RETRIES = 10, 즉시 retry(백오프 없음).

| 항목 | 값 | 의미 |
|---|---|---|
| success | 1 | 정확히 1건만 통과 (oversell 차단 성공) |
| rejected | 99 | 나머지 99건은 실패 |
| heldCount (DB) | 1 | DB에도 HELD reservation 1건만 존재 |
| **retries (총 재시도 횟수)** | **20** | 99건 중 20번은 OptimisticLock 충돌 후 재시도 |
| statusRejects | 99 | 99건 모두 어느 시점에 status=HELD 보고 reject |
| gaveUp | 0 | MAX_RETRIES 소진까지 간 스레드는 없음 |
| attempts (DB 도달 SELECT 총합) | 120 | success 1 + retries 20 + statusRejects 99 |
| elapsedMs | 122 | 100건 처리에 122ms |

핵심 수치는 `attempts = 120`. 좌석 1건을 잡기 위해 100명이 들어왔는데 DB에는 120번의 SELECT + 21번의 UPDATE 시도가 도달했다. 100건이 들어와서 21건이 UPDATE를 시도했다는 것은 — 좌석이 더 많고 워밍업이 분산된 경우(예: 동시 좌석 50개에 1000명) UPDATE 충돌 횟수가 폭증한다.

좌석 1건 시나리오는 status 체크가 retry storm을 절반쯤 막아준 케이스다. **현실의 hot 좌석 N개 + 사용자 M명 시나리오에서는 status 체크가 늦게 도달해 retry가 K번씩 누적되며 wasted UPDATE가 99*K로 증폭된다.**

![테스트 결과](result.png)

## 결론 — 매진 가정에서 부적합

티켓팅 동시 선점 차단은 "여러 좌석 중 일부 hot 좌석에 사용자가 몰리는" 시나리오다. 낙관적 락은 충돌이 드문 경우에 락 오버헤드를 아끼는 패턴인데, hot 좌석 시나리오는 충돌이 100%다.

낙관적 락 + retry는 매진 시:
- 1건 성공 / 99건이 wasted SELECT + 일부 wasted UPDATE
- 백오프 없으면 DB 부하 폭증
- 백오프 추가하면 latency 증가 + 사용자 대기

채택안(대안 Z: 비관적 락 + partial UNIQUE)은 1건만 SELECT FOR UPDATE 통과, 99건은 락 대기 후 status=HELD 보고 즉시 reject한다. DB 도달 SELECT 100건, UPDATE 1건으로 본 대안보다 효율적이다.

기각.
