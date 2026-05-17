package com.dongwoo.alta.service;

import com.dongwoo.alta.domain.Reservation;
import com.dongwoo.alta.domain.Seat;
import com.dongwoo.alta.domain.SeatStatus;
import com.dongwoo.alta.repository.ReservationRepository;
import com.dongwoo.alta.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 대안 A — partial UNIQUE 인덱스 단독.
 *
 * 차이점 (baseline 대비):
 *  - SeatRepository.findByIdForUpdate (FOR UPDATE) 사용 안 함. 그냥 findById.
 *  - 락 없이 status 체크 → hold() → save 시도.
 *  - reservation INSERT 시 partial UNIQUE 위반(23505)을 catch 해서 SeatNotAvailableException 으로 변환.
 *
 * 결과 가설:
 *  - 100건 중 1건만 성공 (race는 DB가 차단).
 *  - 하지만 99건 모두 트랜잭션을 열고 INSERT를 시도 → DB까지 도달 → 23505 던짐.
 *  - 운영 비용: 99개 트랜잭션 round-trip + 99개 unique violation 로그 + Hikari 커넥션 사용량 증가.
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
        Seat seat = seatRepository.findById(seatId)
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
            // partial UNIQUE index 위반 (PSQL 23505).
            // 락이 없으므로 99건이 모두 여기 도달해 거부됨.
            throw new SeatNotAvailableException("seat " + seatId + " rejected by partial UNIQUE");
        }
    }

    public static class SeatNotAvailableException extends RuntimeException {
        public SeatNotAvailableException(String message) {
            super(message);
        }
    }
}
