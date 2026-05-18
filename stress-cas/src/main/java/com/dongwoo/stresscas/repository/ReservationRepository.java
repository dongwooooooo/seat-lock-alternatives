package com.dongwoo.stresscas.repository;

import com.dongwoo.stresscas.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
