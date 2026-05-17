package com.dongwoo.stressdeep.service;

import com.dongwoo.stressdeep.domain.Reservation;
import com.dongwoo.stressdeep.domain.Seat;
import com.dongwoo.stressdeep.domain.SeatStatus;
import com.dongwoo.stressdeep.repository.ReservationRepository;
import com.dongwoo.stressdeep.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 시나리오 3 — lock_timeout 2초 시나리오.
 *
 * Hibernate JPA lock.timeout hint은 PostgreSQL 에 native하게 매핑되지 않음.
 * 따라서 명시적으로 `SET LOCAL lock_timeout = '2s'` 를 발행한 후 SELECT FOR UPDATE 수행.
 * 이는 PG 표준 동작이며, 운영 환경에서도 동일 패턴 사용 가능.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LockTimeoutReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    @Transactional
    public Reservation reserveWithLockTimeout(Long seatId, String userId, long sleepInsideMs)
            throws InterruptedException {
        // PG native lock_timeout 적용. Hibernate QueryHint 보다 확실한 방법.
        em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();

        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + seatId));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new SeatNotAvailableException("seat " + seatId + " status=" + seat.getStatus());
        }

        if (sleepInsideMs > 0) {
            Thread.sleep(sleepInsideMs);
        }

        seat.hold();
        seatRepository.save(seat);

        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            throw new SeatNotAvailableException("seat " + seatId + " rejected by partial UNIQUE");
        }
    }
}
