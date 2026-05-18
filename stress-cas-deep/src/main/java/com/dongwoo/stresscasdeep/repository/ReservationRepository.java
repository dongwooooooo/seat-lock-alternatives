package com.dongwoo.stresscasdeep.repository;

import com.dongwoo.stresscasdeep.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findBySeatId(Long seatId);
}
