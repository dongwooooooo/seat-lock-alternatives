package com.dongwoo.altd.repository;

import com.dongwoo.altd.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
