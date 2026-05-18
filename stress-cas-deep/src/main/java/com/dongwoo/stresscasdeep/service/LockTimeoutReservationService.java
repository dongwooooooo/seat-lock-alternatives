package com.dongwoo.stresscasdeep.service;

import com.dongwoo.stresscasdeep.domain.Reservation;
import com.dongwoo.stresscasdeep.repository.ReservationRepository;
import com.dongwoo.stresscasdeep.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 시나리오 3 (CAS) — Lock timeout 환경에서 CAS UPDATE 행동.
 *
 * 가설: 별도 thread 가 raw JDBC 로 SELECT FOR UPDATE 락을 5초 잡고 있어도,
 *  CAS UPDATE 는 row lock 자체에는 영향을 받는다 (PostgreSQL row write lock 은
 *  반드시 획득해야 UPDATE 가 진행). SET LOCAL lock_timeout='2s' 가 적용되면
 *  CAS UPDATE 도 2초 후 lock_timeout 예외 발화.
 *
 * 비관적 락 시나리오 (56 lockTimeout + 14 connTimeout) 와 비교 측정.
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
        // 비관적 락 시나리오와 동일한 native lock_timeout 적용.
        em.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();

        int updated = seatRepository.casHold(seatId);
        if (updated == 0) {
            throw new SeatNotAvailableException("seat " + seatId + " already HELD");
        }

        if (sleepInsideMs > 0) {
            Thread.sleep(sleepInsideMs);
        }

        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            seatRepository.casRelease(seatId);
            throw new SeatNotAvailableException("seat " + seatId + " UNIQUE conflict");
        }
    }
}
