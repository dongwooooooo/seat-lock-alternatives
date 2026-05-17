package com.dongwoo.stressdeep.service;

import com.dongwoo.stressdeep.domain.Reservation;
import com.dongwoo.stressdeep.domain.Seat;
import com.dongwoo.stressdeep.domain.SeatStatus;
import com.dongwoo.stressdeep.repository.ReservationRepository;
import com.dongwoo.stressdeep.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 시나리오 5 — Rollback storm.
 *
 * 30% 확률로 락 획득 + seat.hold() + reservation INSERT 이후에 예외를 던져
 * 트랜잭션을 강제 롤백시킨다. 검증 목표:
 *  - PostgreSQL이 tx 종료 시 락을 자동 해제하는지 (당연히 yes)
 *  - seat.hold() 가 롤백되어 SeatStatus 가 AVAILABLE로 복귀하는지
 *  - 즉 같은 tx 내 모든 변경이 atomic 한지 (orphan 없음)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FailingReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private final AtomicInteger rollbackCount = new AtomicInteger();

    public int getRollbackCount() {
        return rollbackCount.get();
    }

    public static class IntentionalFailure extends RuntimeException {
        public IntentionalFailure(String message) {
            super(message);
        }
    }

    @Transactional
    public Reservation reserveSometimesFailing(Long seatId, String userId, double failRate) {
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + seatId));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new SeatNotAvailableException("seat " + seatId + " status=" + seat.getStatus());
        }

        seat.hold();
        seatRepository.save(seat);

        Reservation saved;
        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            saved = reservationRepository.save(reservation);
            // flush 강제 — INSERT가 DB에 도달했는지 확인 (rollback 검증 대상).
            reservationRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new SeatNotAvailableException("seat " + seatId + " rejected by partial UNIQUE");
        }

        // INSERT 완료 후 예외 발생 → seat.hold() + reservation INSERT 모두 롤백되어야 함.
        if (ThreadLocalRandom.current().nextDouble() < failRate) {
            rollbackCount.incrementAndGet();
            throw new IntentionalFailure("intentional rollback after reservation insert");
        }
        return saved;
    }
}
