package com.dongwoo.stresscasdeep.repository;

import com.dongwoo.stresscasdeep.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * CAS 채택안 (Deep) — Compare-And-Swap via atomic UPDATE.
 *
 * 비관적 락 (@Lock + SELECT FOR UPDATE) 제거. 모든 시나리오에서 lock-free.
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Modifying
    @Query(value = "UPDATE seat SET status='HELD' WHERE id=:id AND status='AVAILABLE'", nativeQuery = true)
    int casHold(@Param("id") Long id);

    @Modifying
    @Query(value = "UPDATE seat SET status='AVAILABLE' WHERE id=:id AND status='HELD'", nativeQuery = true)
    int casRelease(@Param("id") Long id);
}
