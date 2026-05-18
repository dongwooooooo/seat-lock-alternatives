package com.dongwoo.stresscasdeep.service;

import com.dongwoo.stresscasdeep.domain.Reservation;
import com.dongwoo.stresscasdeep.repository.ReservationRepository;
import com.dongwoo.stresscasdeep.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 시나리오 2 (CAS) — 다중 좌석 동시 hold + 잘못된 순서 시도.
 *
 * 비관적 락 시나리오에서는 SELECT FOR UPDATE A → sleep → SELECT FOR UPDATE B
 * (와 역순) 조합이 deadlock 을 유발했다.
 *
 * CAS 는 atomic UPDATE 이므로 row lock 보유 시간이 UPDATE 실행 (~1ms) 으로 매우 짧다.
 * 두 thread 가 좌석 A→B 와 B→A 순서로 UPDATE 를 발행해도 각 UPDATE 가 즉시
 * 완료되므로 cycle 이 형성될 수 없음. PostgreSQL 의 deadlock detector 는 발화하지 않을 것.
 *
 * 동일 조건 (50ms 지연 + 역순 UPDATE) 으로 실측해서 비관적 락 59/60 deadlock 과 비교.
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
        // A 먼저 CAS hold.
        int updatedA = seatRepository.casHold(seatAId);
        // 비관적 락 시나리오와 동일한 50ms 지연.
        Thread.sleep(50);
        int updatedB = seatRepository.casHold(seatBId);

        if (updatedA == 1) {
            reservationRepository.save(Reservation.create(seatAId, userId + "-A", HOLD_DURATION));
        }
        if (updatedB == 1) {
            reservationRepository.save(Reservation.create(seatBId, userId + "-B", HOLD_DURATION));
        }
    }

    @Transactional
    public void lockSeatBThenA(Long seatAId, Long seatBId, String userId) throws InterruptedException {
        // B 먼저 CAS hold.
        int updatedB = seatRepository.casHold(seatBId);
        Thread.sleep(50);
        int updatedA = seatRepository.casHold(seatAId);

        if (updatedB == 1) {
            reservationRepository.save(Reservation.create(seatBId, userId + "-B", HOLD_DURATION));
        }
        if (updatedA == 1) {
            reservationRepository.save(Reservation.create(seatAId, userId + "-A", HOLD_DURATION));
        }
    }
}
