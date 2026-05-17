package com.dongwoo.alte.service;

import com.dongwoo.alte.domain.Reservation;
import com.dongwoo.alte.domain.Seat;
import com.dongwoo.alte.repository.ReservationRepository;
import com.dongwoo.alte.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/**
 * 대안 E — SELECT FOR UPDATE SKIP LOCKED.
 *
 * 두 시나리오:
 *  1. reserveSpecific — 특정 좌석. 락 잡힌 경우 empty 반환 → 즉시 reject (false negative).
 *  2. reserveAny — 임의 가용 좌석. 락 잡힌 좌석은 건너뛰고 다른 좌석 선택 → 작업 큐 디스패치.
 *
 * 두 메서드 모두 REQUIRES_NEW로 별개 트랜잭션을 열어
 * 한 스레드의 락이 다른 스레드에 정확히 보이도록 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    /**
     * 시나리오 1: 특정 좌석 요청.
     *
     * SKIP LOCKED 결과가 empty라면:
     *  - 다른 트랜잭션이 락을 잡고 있는 중이거나
     *  - 이미 AVAILABLE이 아니거나
     * 둘 중 어느 쪽인지 SQL 단에서 구분 불가 → 호출 측은 "사용 불가"로 간주.
     * 이게 본 도메인의 false negative.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserveSpecific(Long seatId, String userId) {
        Optional<Seat> opt = seatRepository.findByIdSkipLocked(seatId);
        if (opt.isEmpty()) {
            throw new SeatNotAvailableException(
                    "seat " + seatId + " skipped (locked by another tx or not AVAILABLE)");
        }
        Seat seat = opt.get();
        seat.hold();
        seatRepository.saveAndFlush(seat);

        Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
        return reservationRepository.save(reservation);
    }

    /**
     * 시나리오 2: 임의 가용 좌석 디스패치.
     *
     * 각 트랜잭션이 서로 다른 좌석을 잡는다. 락 잡힌 좌석은 건너뛴다.
     * 100 좌석 / 1000 스레드 → 100건 성공, 나머지는 좌석 소진으로 empty.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserveAny(String userId) {
        Optional<Seat> opt = seatRepository.findAnyAvailableSkipLocked();
        if (opt.isEmpty()) {
            throw new NoSeatAvailableException("no AVAILABLE seat (all locked or sold)");
        }
        Seat seat = opt.get();
        seat.hold();
        seatRepository.saveAndFlush(seat);

        Reservation reservation = Reservation.create(seat.getId(), userId, HOLD_DURATION);
        return reservationRepository.save(reservation);
    }

    public static class SeatNotAvailableException extends RuntimeException {
        public SeatNotAvailableException(String message) {
            super(message);
        }
    }

    public static class NoSeatAvailableException extends RuntimeException {
        public NoSeatAvailableException(String message) {
            super(message);
        }
    }
}
