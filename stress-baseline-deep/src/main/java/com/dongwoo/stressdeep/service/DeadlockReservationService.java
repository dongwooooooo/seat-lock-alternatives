package com.dongwoo.stressdeep.service;

import com.dongwoo.stressdeep.domain.Reservation;
import com.dongwoo.stressdeep.domain.ReservationStatus;
import com.dongwoo.stressdeep.domain.Seat;
import com.dongwoo.stressdeep.repository.ReservationRepository;
import com.dongwoo.stressdeep.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 시나리오 2 — 다중 row 락 + 잘못된 ordering 으로 deadlock 유발.
 *
 * 두 좌석을 동시에 다루는 경우 (e.g. 좌석 교환, 연결석 예약).
 *  - lockSeatAThenB: seat A → seat B 순서로 락 획득
 *  - lockSeatBThenA: seat B → seat A 순서로 락 획득
 *
 * 두 메서드를 동시에 호출하면 PostgreSQL이 `40P01 deadlock_detected`로 한쪽을 abort.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeadlockReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    @Transactional
    public void lockSeatAThenB(Long seatAId, Long seatBId, String userId) throws InterruptedException {
        Seat a = seatRepository.findByIdForUpdate(seatAId)
                .orElseThrow(() -> new IllegalArgumentException("seat A not found: " + seatAId));
        // deadlock 확률을 높이기 위한 의도적 지연. 실제 운영에서 락 보유 중 외부 호출이 끼는 anti-pattern 시뮬레이션.
        Thread.sleep(50);
        Seat b = seatRepository.findByIdForUpdate(seatBId)
                .orElseThrow(() -> new IllegalArgumentException("seat B not found: " + seatBId));

        // 동시 예약(연결석) 로직.
        if (a.getStatus().name().equals("AVAILABLE")) {
            a.hold();
            seatRepository.save(a);
            reservationRepository.save(Reservation.create(seatAId, userId + "-A", HOLD_DURATION));
        }
        if (b.getStatus().name().equals("AVAILABLE")) {
            b.hold();
            seatRepository.save(b);
            reservationRepository.save(Reservation.create(seatBId, userId + "-B", HOLD_DURATION));
        }
    }

    @Transactional
    public void lockSeatBThenA(Long seatAId, Long seatBId, String userId) throws InterruptedException {
        Seat b = seatRepository.findByIdForUpdate(seatBId)
                .orElseThrow(() -> new IllegalArgumentException("seat B not found: " + seatBId));
        Thread.sleep(50);
        Seat a = seatRepository.findByIdForUpdate(seatAId)
                .orElseThrow(() -> new IllegalArgumentException("seat A not found: " + seatAId));

        if (b.getStatus().name().equals("AVAILABLE")) {
            b.hold();
            seatRepository.save(b);
            reservationRepository.save(Reservation.create(seatBId, userId + "-B", HOLD_DURATION));
        }
        if (a.getStatus().name().equals("AVAILABLE")) {
            a.hold();
            seatRepository.save(a);
            reservationRepository.save(Reservation.create(seatAId, userId + "-A", HOLD_DURATION));
        }
    }
}
