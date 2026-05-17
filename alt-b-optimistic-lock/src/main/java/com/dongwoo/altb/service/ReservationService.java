package com.dongwoo.altb.service;

import com.dongwoo.altb.domain.Reservation;
import com.dongwoo.altb.domain.Seat;
import com.dongwoo.altb.domain.SeatStatus;
import com.dongwoo.altb.repository.ReservationRepository;
import com.dongwoo.altb.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 대안 B — 낙관적 락 (@Version) + retry loop.
 *
 * 동작:
 *  1. findById (락 없음) → seat 로드
 *  2. status != AVAILABLE 이면 reject (retry 무의미)
 *  3. seat.hold() → save → JPA가 UPDATE ... WHERE id=? AND version=? 발사
 *  4. version mismatch → ObjectOptimisticLockingFailureException
 *  5. 최대 MAX_RETRIES 회 retry (이게 retry storm의 본체)
 *
 * 매진 시나리오 함정:
 *  - 1건 성공 → seat.status = HELD
 *  - 99건이 OptimisticLockException 받고 retry
 *  - retry 시점에 다시 findById → status=HELD 보고 reject (retry storm 자체는 wasted)
 *  - 측정 포인트: 99건이 각각 K번씩 reload+UPDATE 시도 → DB 부하 99*K
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final int MAX_RETRIES = 10;

    /** 전체 스레드가 쌓아올린 retry 시도 횟수 (성공 attempt 제외, OptimisticLock retry만 count). */
    private final AtomicInteger totalRetryAttempts = new AtomicInteger();
    /** MAX_RETRIES 소진 후 포기한 스레드 수. */
    private final AtomicInteger gaveUpCount = new AtomicInteger();
    /** seat status가 AVAILABLE이 아니어서 reject된 스레드 수 (retry 도중 포함). */
    private final AtomicInteger statusRejectCount = new AtomicInteger();

    public AtomicInteger getTotalRetryAttempts() { return totalRetryAttempts; }
    public AtomicInteger getGaveUpCount() { return gaveUpCount; }
    public AtomicInteger getStatusRejectCount() { return statusRejectCount; }

    public void resetCounters() {
        totalRetryAttempts.set(0);
        gaveUpCount.set(0);
        statusRejectCount.set(0);
    }

    /**
     * Public entry — retry loop wrapper.
     * 내부 reserveOnce()는 REQUIRES_NEW로 매 attempt마다 새 트랜잭션 열어서
     * OptimisticLockException 발생 후에도 다음 attempt가 깨끗한 컨텍스트로 시작.
     */
    public Reservation reserve(Long seatId, String userId) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return reserveOnce(seatId, userId);
            } catch (ObjectOptimisticLockingFailureException e) {
                totalRetryAttempts.incrementAndGet();
                if (attempt == MAX_RETRIES) {
                    gaveUpCount.incrementAndGet();
                    throw new SeatNotAvailableException(
                            "seat " + seatId + " gave up after " + MAX_RETRIES + " optimistic retries");
                }
                // 즉시 재시도 (backoff 없음 — retry storm 입증이 목적)
            }
        }
        throw new IllegalStateException("unreachable");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserveOnce(Long seatId, String userId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + seatId));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            statusRejectCount.incrementAndGet();
            throw new SeatNotAvailableException("seat " + seatId + " status=" + seat.getStatus());
        }

        seat.hold();
        seatRepository.saveAndFlush(seat); // flush 강제 → OptimisticLockException 즉시 발생

        Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
        return reservationRepository.save(reservation);
    }

    public static class SeatNotAvailableException extends RuntimeException {
        public SeatNotAvailableException(String message) {
            super(message);
        }
    }
}
