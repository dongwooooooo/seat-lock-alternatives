package com.dongwoo.alte.repository;

import com.dongwoo.alte.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
