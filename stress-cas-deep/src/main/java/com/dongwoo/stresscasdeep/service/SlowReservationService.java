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

/**
 * 시나리오 4 (CAS) — Long-running tx starvation.
 *
 * Critical section 내부에 sleep(2s). CAS 도 UPDATE 후 commit 까지 row write lock 보유.
 * 즉 첫 thread 가 UPDATE 후 2초 sleep 하는 동안 row lock 을 들고 있음 → 후속 thread
 * 의 casHold UPDATE 가 같은 row 를 노리면 block 됨.
 *
 * 단, 1차 UPDATE 가 성공하지 못한 (status != AVAILABLE) thread 는 row lock 을 못
 * 받지만 row 자체에 락이 걸려있으므로 wait. winner commit 후 보통은 affected=0 으로
 * 깨어남 (SeatNotAvailable).
 *
 * 비관적 락 (p99=2068ms) 와 비교 측정.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlowReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    @Transactional
    public Reservation reserveSlow(Long seatId, String userId, long sleepMs) throws InterruptedException {
        int updated = seatRepository.casHold(seatId);
        if (updated == 0) {
            throw new SeatNotAvailableException("seat " + seatId + " already HELD");
        }

        // 외부 API 호출 시뮬레이션 — 트랜잭션 안에서 절대 하면 안 되는 일.
        // CAS 도 commit 전까지는 row lock 보유.
        Thread.sleep(sleepMs);

        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            seatRepository.casRelease(seatId);
            throw new SeatNotAvailableException("seat " + seatId + " UNIQUE conflict");
        }
    }
}
