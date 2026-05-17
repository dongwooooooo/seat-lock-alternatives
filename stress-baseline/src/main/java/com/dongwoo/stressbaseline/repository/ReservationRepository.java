package com.dongwoo.stressbaseline.repository;

import com.dongwoo.stressbaseline.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
