package com.dongwoo.stressbaseline.service;

import com.dongwoo.stressbaseline.domain.Reservation;
import com.dongwoo.stressbaseline.domain.Seat;
import com.dongwoo.stressbaseline.domain.SeatStatus;
import com.dongwoo.stressbaseline.repository.ReservationRepository;
import com.dongwoo.stressbaseline.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Stage 2 baseline 채택안 — 비관적 락 + partial UNIQUE 이중 방어.
 *
 * 정합성:
 *  - findByIdForUpdate (SELECT FOR UPDATE) 로 row 잠금.
 *  - 동시 요청 중 1건만 트랜잭션 진입, 나머지는 직렬화 또는 차단.
 *  - reservation INSERT 시 partial UNIQUE 위반(23505)을 catch 해서 SeatNotAvailableException 으로 변환.
 *
 * 한계 (이 stress test가 입증할 부분):
 *  - 락 자체는 정합성을 보장하지만, HikariCP 풀이 한정적이면 풀 고갈 발생.
 *  - 락 대기 큐가 길어지면 connection-timeout (SQLTransientConnectionException) 폭증.
 *  - p99 latency가 락 대기시간 + 풀 대기시간 합산으로 폭증.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    @Transactional
    public Reservation reserve(Long seatId, String userId) {
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + seatId));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new SeatNotAvailableException("seat " + seatId + " status=" + seat.getStatus());
        }

        seat.hold();
        seatRepository.save(seat);

        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            // partial UNIQUE index 위반 — 이중 방어 그물.
            throw new SeatNotAvailableException("seat " + seatId + " rejected by partial UNIQUE");
        }
    }

    public static class SeatNotAvailableException extends RuntimeException {
        public SeatNotAvailableException(String message) {
            super(message);
        }
    }
}
