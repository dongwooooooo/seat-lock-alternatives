package com.dongwoo.stresscas.repository;

import com.dongwoo.stresscas.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * CAS 채택안 — Compare-And-Swap pattern via atomic UPDATE.
 *
 * 비관적 락 (SELECT FOR UPDATE) 제거, atomic UPDATE 로 1차 방어선 구축.
 * UPDATE 의 affected-rows 가 1 이면 hold 성공, 0 이면 race loss.
 *
 * Row lock 보유 시간 = UPDATE 실행 시간 (~1ms). 비관적 락 대비 락 보유 시간 크게 감소.
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Modifying
    @Query(value = "UPDATE seat SET status='HELD' WHERE id=:id AND status='AVAILABLE'", nativeQuery = true)
    int casHold(@Param("id") Long id);

    @Modifying
    @Query(value = "UPDATE seat SET status='AVAILABLE' WHERE id=:id AND status='HELD'", nativeQuery = true)
    int casRelease(@Param("id") Long id);
}
