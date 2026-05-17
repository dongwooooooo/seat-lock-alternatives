package com.dongwoo.altb.repository;

import com.dongwoo.altb.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 대안 B — 낙관적 락 (@Version).
 *
 * @Lock 사용 안 함. 그냥 findById.
 * JPA가 entity의 @Version 필드를 보고 자동으로 UPDATE ... WHERE id=? AND version=? 발사.
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {
}
