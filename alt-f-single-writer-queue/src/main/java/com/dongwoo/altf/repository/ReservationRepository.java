package com.dongwoo.altf.repository;

import com.dongwoo.altf.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
