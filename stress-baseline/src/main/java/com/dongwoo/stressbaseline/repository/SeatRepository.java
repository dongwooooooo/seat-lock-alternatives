package com.dongwoo.stressbaseline.repository;

import com.dongwoo.stressbaseline.domain.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Stage 2 baseline — Pessimistic Lock + partial UNIQUE.
 *
 * 측정 목표:
 *  - race 정합성이 아니라 풀/대기/처리량 측면에서 한계 입증.
 *  - HikariCP maximum-pool-size=10 으로 제한, hot seat 1000 동시 요청 시
 *    9건은 락 대기 직렬화 + 나머지는 풀 acquisition timeout 가능.
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);
}
