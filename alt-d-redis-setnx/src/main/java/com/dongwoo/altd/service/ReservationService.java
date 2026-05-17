package com.dongwoo.altd.service;

import com.dongwoo.altd.domain.Reservation;
import com.dongwoo.altd.domain.Seat;
import com.dongwoo.altd.domain.SeatStatus;
import com.dongwoo.altd.lock.RedisDistributedLock;
import com.dongwoo.altd.repository.ReservationRepository;
import com.dongwoo.altd.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 대안 D — Redis SETNX 분산 락.
 *
 * 동작:
 *  1. UUID token 생성
 *  2. SET seat:{id} {token} NX EX 5 → 락 획득 시도
 *  3. 실패 → SeatNotAvailableException (이미 다른 스레드가 처리 중)
 *  4. 성공 → DB에서 seat 조회 + AVAILABLE 검증 + HELD 갱신 + reservation insert
 *  5. finally: Lua script로 token이 같을 때만 DEL (TTL 만료 후 누군가가 잡은 락을 잘못 해제하지 않도록)
 *
 * Kleppmann fencing token 미적용 → 단일 노드 단계에서만 안전.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final RedisDistributedLock lock;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final int LOCK_TTL_SECONDS = 5;

    /** 락 획득 실패 카운트 (NX 가 null 반환). */
    private final AtomicInteger lockAcquireFailCount = new AtomicInteger();
    /** seat 상태가 AVAILABLE 이 아니어서 reject된 카운트 (락은 잡았지만 이미 누가 HELD로 만든 경우). */
    private final AtomicInteger statusRejectCount = new AtomicInteger();
    /** unlock 시 token mismatch 횟수 (TTL 만료 후 다른 소유자가 잡고 있던 경우). */
    private final AtomicInteger unlockMissCount = new AtomicInteger();

    public AtomicInteger getLockAcquireFailCount() { return lockAcquireFailCount; }
    public AtomicInteger getStatusRejectCount() { return statusRejectCount; }
    public AtomicInteger getUnlockMissCount() { return unlockMissCount; }

    public void resetCounters() {
        lockAcquireFailCount.set(0);
        statusRejectCount.set(0);
        unlockMissCount.set(0);
    }

    public Reservation reserve(Long seatId, String userId) {
        String key = lockKey(seatId);
        String token = UUID.randomUUID().toString();

        if (!lock.tryLock(key, token, LOCK_TTL_SECONDS)) {
            lockAcquireFailCount.incrementAndGet();
            throw new SeatNotAvailableException("seat " + seatId + " — lock busy");
        }
        try {
            return reserveInTx(seatId, userId);
        } finally {
            if (!lock.unlock(key, token)) {
                unlockMissCount.incrementAndGet();
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserveInTx(Long seatId, String userId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + seatId));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            statusRejectCount.incrementAndGet();
            throw new SeatNotAvailableException("seat " + seatId + " status=" + seat.getStatus());
        }

        seat.hold();
        seatRepository.save(seat);

        Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
        return reservationRepository.save(reservation);
    }

    public static String lockKey(Long seatId) {
        return "seat:" + seatId;
    }

    public static class SeatNotAvailableException extends RuntimeException {
        public SeatNotAvailableException(String message) {
            super(message);
        }
    }
}
