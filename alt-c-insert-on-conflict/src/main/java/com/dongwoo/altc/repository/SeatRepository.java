package com.dongwoo.altc.repository;

import com.dongwoo.altc.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 대안 C — INSERT ON CONFLICT DO NOTHING.
 *
 * Seat 상태 갱신은 별도 atomic UPDATE 사용. @Lock 없음.
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * AVAILABLE → HELD 원자적 전이. 영향 행 수 = 1이면 성공, 0이면 race 패배.
     */
    @Modifying
    @Query(value = """
            UPDATE seat
               SET status = 'HELD',
                   updated_at = now()
             WHERE id = :seatId
               AND status = 'AVAILABLE'
            """, nativeQuery = true)
    int markHeldIfAvailable(@Param("seatId") Long seatId);
}
