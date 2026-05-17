package com.dongwoo.altc.repository;

import com.dongwoo.altc.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 대안 C — INSERT ... ON CONFLICT DO NOTHING.
 *
 * partial UNIQUE index uq_reservation_seat_active (seat_id) WHERE status IN ('HELD','PAID')
 * 와 매칭되도록 ON CONFLICT 절도 같은 inference (column + predicate)를 명시한다.
 *
 * 영향 행 수 = 1 이면 INSERT 성공 (race 승자), 0 이면 conflict → race 패자.
 */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO reservation
                (seat_id, user_id, status, expires_at, created_at, updated_at)
            VALUES
                (:seatId, :userId, 'HELD', :expiresAt, now(), now())
            ON CONFLICT (seat_id) WHERE status IN ('HELD','PAID')
            DO NOTHING
            """, nativeQuery = true)
    int insertHeldIfNoConflict(
            @Param("seatId") Long seatId,
            @Param("userId") String userId,
            @Param("expiresAt") LocalDateTime expiresAt);
}
