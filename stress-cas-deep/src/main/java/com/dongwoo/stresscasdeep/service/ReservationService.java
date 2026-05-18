package com.dongwoo.stresscasdeep.service;

import com.dongwoo.stresscasdeep.domain.Reservation;
import com.dongwoo.stresscasdeep.domain.Seat;
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
 * 기본 reserve (CAS) — atomic UPDATE + partial UNIQUE.
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
        int updated = seatRepository.casHold(seatId);
        if (updated == 0) {
            throw new SeatNotAvailableException("seat " + seatId + " already HELD");
        }
        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            seatRepository.casRelease(seatId);
            throw new SeatNotAvailableException("seat " + seatId + " UNIQUE conflict");
        }
    }

    /**
     * 시나리오 1 (CAS) — JPA persistence context staleness 검증.
     *
     * CAS 는 native UPDATE 이므로 1차 캐시 entity 상태와 무관하게 동작.
     * 같은 tx 에서 먼저 findById() 로 캐시 적재 후 casHold() 호출 → 캐시된 entity 의
     * status 가 stale ('AVAILABLE') 이어도 native UPDATE 는 DB 의 현재 상태로 판단.
     *
     * 검증 목표: 100 동시 호출 시 heldCount=1 유지 (CAS 가 race 차단).
     */
    @Transactional
    public Reservation reserveWithCacheWarmup(Long seatId, String userId) {
        // 1차 cache warmup: persistence context 에 entity 적재.
        Seat cached = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + seatId));

        // 2차 CAS: native UPDATE. 1차 캐시 우회.
        int updated = seatRepository.casHold(seatId);
        if (updated == 0) {
            throw new SeatNotAvailableException(
                    "seat " + seatId + " already HELD (cached snapshot status=" + cached.getStatus() + ")");
        }

        try {
            Reservation reservation = Reservation.create(seatId, userId, HOLD_DURATION);
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            seatRepository.casRelease(seatId);
            throw new SeatNotAvailableException("seat " + seatId + " UNIQUE conflict (cache path)");
        }
    }
}
