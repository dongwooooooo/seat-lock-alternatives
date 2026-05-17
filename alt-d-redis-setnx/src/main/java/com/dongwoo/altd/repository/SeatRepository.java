package com.dongwoo.altd.repository;

import com.dongwoo.altd.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 대안 D — Redis SETNX 분산 락.
 *
 * @Lock 사용 안 함. partial UNIQUE 없음. version 없음.
 * 동시성은 서비스 레벨에서 Redis 락으로 차단된다.
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {
}
