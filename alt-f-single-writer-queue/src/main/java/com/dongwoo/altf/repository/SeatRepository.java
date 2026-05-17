package com.dongwoo.altf.repository;

import com.dongwoo.altf.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {
}
