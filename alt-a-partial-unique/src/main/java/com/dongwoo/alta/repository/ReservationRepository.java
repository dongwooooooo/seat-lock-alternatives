package com.dongwoo.alta.repository;

import com.dongwoo.alta.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
