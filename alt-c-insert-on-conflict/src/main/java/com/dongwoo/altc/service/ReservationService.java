package com.dongwoo.altc.service;

import com.dongwoo.altc.domain.Reservation;
import com.dongwoo.altc.domain.ReservationStatus;
import com.dongwoo.altc.repository.ReservationRepository;
import com.dongwoo.altc.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 대안 C — INSERT ... ON CONFLICT DO NOTHING + atomic seat UPDATE.
 *
 * 차이점 (baseline / 대안 A 대비):
 *  - Seat findById + entity dirty-checking 사용 안 함. @Lock 없음.
 *  - JPA repository.save() 없음. 모든 변경은 native query 1발씩.
 *  - 예외 기반 분기(DataIntegrityViolationException)를 쓰지 않음 — 영향 행 수로 판단.
 *
 * 흐름:
 *  1. INSERT ... ON CONFLICT DO NOTHING (reservation)
 *     - 영향 행 = 1 → race 승자
 *     - 영향 행 = 0 → race 패자 (정상 흐름, 예외 던지지 않고 SeatNotAvailableException)
 *  2. UPDATE seat SET status='HELD' WHERE id=? AND status='AVAILABLE'
 *     - 정상 케이스에서 1이 나와야 함. 0이면 다른 트랜잭션이 SOLD 상태로 바꿔놓은 비정상 상태.
 *
 * 가설:
 *  - race 차단 작동 (heldCount = 1).
 *  - 99건은 INSERT 단 1발만 쏘고 0행 반환받음 → 트랜잭션 짧음, 예외 비용 없음.
 *  - 댓가: JPA 추상화 우회. Reservation 엔티티 라이프사이클이 service 코드에서 사라짐.
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
        LocalDateTime expiresAt = LocalDateTime.now().plus(HOLD_DURATION);

        int inserted = reservationRepository.insertHeldIfNoConflict(seatId, userId, expiresAt);
        if (inserted == 0) {
            // ON CONFLICT DO NOTHING 발동. 다른 트랜잭션이 이미 HELD/PAID reservation 보유.
            throw new SeatNotAvailableException("seat " + seatId + " lost INSERT ON CONFLICT race");
        }

        int seatUpdated = seatRepository.markHeldIfAvailable(seatId);
        if (seatUpdated == 0) {
            // reservation INSERT는 성공했는데 seat는 AVAILABLE이 아니다 = 상태 불일치.
            // 운영상으론 reservation을 EXPIRED 처리해 보정해야 하나, 본 검증 범위는 race 차단까지.
            log.warn("seat {} not AVAILABLE after winning insert — possible status drift", seatId);
        }

        // 방금 INSERT한 reservation을 다시 읽어 반환. persistence-context 캐시 어긋남이 있으면
        // 여기서 null이 나오거나 옛 데이터가 잡힘. native INSERT는 EntityManager를 우회하므로
        // 같은 트랜잭션 내에서 JPA가 이 row를 알지 못함 → findAll/Query는 DB까지 다녀와야 함.
        List<Reservation> mine = reservationRepository.findAll().stream()
                .filter(r -> r.getSeatId().equals(seatId))
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .filter(r -> r.getUserId().equals(userId))
                .toList();
        if (mine.isEmpty()) {
            throw new IllegalStateException("inserted reservation not visible after native INSERT");
        }
        return mine.get(0);
    }

    public static class SeatNotAvailableException extends RuntimeException {
        public SeatNotAvailableException(String message) {
            super(message);
        }
    }
}
