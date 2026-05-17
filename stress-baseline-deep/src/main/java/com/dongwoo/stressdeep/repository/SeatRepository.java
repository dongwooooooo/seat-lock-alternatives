package com.dongwoo.stressdeep.repository;

import com.dongwoo.stressdeep.domain.Seat;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * Pessimistic write lock (SELECT FOR UPDATE).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);

    /**
     * Pessimistic write lock + lock timeout (2초). 시나리오 3 (lock wait timeout) 용.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000"),
            @QueryHint(name = "javax.persistence.lock.timeout", value = "2000")
    })
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdateWithTimeout(@Param("id") Long id);
}
