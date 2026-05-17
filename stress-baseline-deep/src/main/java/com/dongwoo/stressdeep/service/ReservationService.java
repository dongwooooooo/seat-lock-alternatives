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
 * 기본 reserve — stress-baseline 과 동일한 정상 로직.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    @PersistenceContext
    private EntityManager em;

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
            throw new SeatNotAvailableException("seat " + seatId + " rejected by partial UNIQUE");
        }
    }

    /**
     * 시나리오 1 — JPA persistence context staleness 검증.
     * 같은 트랜잭션 내에서 먼저 non-lock findById 로 캐시에 적재 후
     * findByIdForUpdate 를 호출. Hibernate가 캐시된 엔티티에 대해서도 SELECT FOR UPDATE
     * 를 발행하는지 (UPGRADE) 확인하기 위함.
     *
     * 결론: Spring Data JPA `@Lock + @Query`는 매번 새 SELECT FOR UPDATE 를 발행.
     */
    @Transactional
    public Reservation reserveWithCacheWarmup(Long seatId, String userId) {
        // 1차: non-lock 조회 → persistence context에 적재.
        seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + seatId));

        // 2차: lock 조회. 이미 캐시에 있어도 FOR UPDATE 발행되어야 함.
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found (lock): " + seatId));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new SeatNotAvailableException("seat " + seatId + " status=" + seat.getStatus());
        }

        seat.hold();
        seatRepository.save(seat);

        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            throw new SeatNotAvailableException("seat " + seatId + " rejected by partial UNIQUE (cache path)");
        }
    }
}
