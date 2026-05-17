package com.dongwoo.altb.repository;

import com.dongwoo.altb.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
