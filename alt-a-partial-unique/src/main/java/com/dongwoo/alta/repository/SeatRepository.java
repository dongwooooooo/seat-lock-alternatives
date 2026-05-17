package com.dongwoo.alta.repository;

import com.dongwoo.alta.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 대안 A — partial UNIQUE 단독.
 * @Lock 사용 안 함. 그냥 findById.
 * 동시 100건 요청 모두 트랜잭션을 열고 DB까지 도달, partial UNIQUE가 99건을 23505로 거부.
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {
}
