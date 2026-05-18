package com.dongwoo.stresscasdeep.service;

import com.dongwoo.stresscasdeep.domain.Reservation;
import com.dongwoo.stresscasdeep.repository.ReservationRepository;
import com.dongwoo.stresscasdeep.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 시나리오 5 (CAS) — Rollback storm.
 *
 * 30% 확률로 CAS hold + reservation INSERT 후 강제 예외 → rollback.
 * 검증:
 *  - PostgreSQL 이 rollback 시 UPDATE seat (status='HELD') 도 atomic 하게 복구하는지
 *  - reservation INSERT 도 rollback 되는지
 *  - orphan (seat=HELD 인데 reservation 없음) 없는지
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
        int updated = seatRepository.casHold(seatId);
        if (updated == 0) {
            throw new SeatNotAvailableException("seat " + seatId + " already HELD");
        }

        Reservation saved;
        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            saved = reservationRepository.save(reservation);
            // flush 강제 — INSERT 가 DB 에 도달했는지 확인 (rollback 검증 대상).
            reservationRepository.flush();
        } catch (DataIntegrityViolationException e) {
            seatRepository.casRelease(seatId);
            throw new SeatNotAvailableException("seat " + seatId + " UNIQUE conflict");
        }

        // INSERT 완료 후 예외 발생 → seat UPDATE + reservation INSERT 모두 rollback 되어야 함.
        if (ThreadLocalRandom.current().nextDouble() < failRate) {
            rollbackCount.incrementAndGet();
            throw new IntentionalFailure("intentional rollback after reservation insert");
        }
        return saved;
    }
}
