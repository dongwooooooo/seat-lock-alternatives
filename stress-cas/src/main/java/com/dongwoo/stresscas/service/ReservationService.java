package com.dongwoo.stresscas.service;

import com.dongwoo.stresscas.domain.Reservation;
import com.dongwoo.stresscas.repository.ReservationRepository;
import com.dongwoo.stresscas.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * CAS 채택안 — Compare-And-Swap (lock-free atomic UPDATE).
 *
 * 정합성:
 *  - 1차: atomic UPDATE seat SET status='HELD' WHERE id=? AND status='AVAILABLE'
 *    affected rows = 1 이면 hold 성공, 0 이면 race loss (다른 사용자가 이미 hold)
 *  - 2차 (최후 그물): partial UNIQUE index 가 reservation INSERT 시 23505 발화
 *
 * 비관적 락 대비:
 *  - 락 보유 시간: tx 전체 → UPDATE 실행 (~1ms) 으로 감소
 *  - 도메인 응집도: seat.hold() 비즈니스 메서드 우회 (native SQL 직접 호출)
 *  - lock-free → deadlock·lock_timeout 발화 자체가 없음
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
        // 1차 CAS: atomic UPDATE
        int updated = seatRepository.casHold(seatId);
        if (updated == 0) {
            throw new SeatNotAvailableException("seat " + seatId + " already HELD");
        }
        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            // 2차 partial UNIQUE 위반 — 최후 방어선. seat status 복구 후 거절.
            seatRepository.casRelease(seatId);
            throw new SeatNotAvailableException("seat " + seatId + " UNIQUE conflict");
        }
    }

    public static class SeatNotAvailableException extends RuntimeException {
        public SeatNotAvailableException(String message) {
            super(message);
        }
    }
}
