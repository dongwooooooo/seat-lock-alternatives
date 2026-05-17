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

/**
 * 시나리오 4 — Long-running tx starvation.
 *
 * Critical section 내부에 Thread.sleep(2000) (외부 API 호출 anti-pattern).
 * 동시 요청 100개 + 풀 작으면 풀 고갈 + 락 직렬화로 starvation.
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
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + seatId));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new SeatNotAvailableException("seat " + seatId + " status=" + seat.getStatus());
        }

        // 외부 API 호출 시뮬레이션 — 트랜잭션 안에서 절대 하면 안 되는 일.
        Thread.sleep(sleepMs);

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
