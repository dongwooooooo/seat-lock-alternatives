package com.dongwoo.alte.repository;

import com.dongwoo.alte.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 대안 E — SELECT FOR UPDATE SKIP LOCKED.
 *
 * 두 가지 native query:
 *  1. findByIdSkipLocked — 특정 좌석 요청 시나리오. 다른 트랜잭션이 락 잡고 있으면 empty.
 *  2. findAnyAvailableSkipLocked — 임의 가용 좌석 요청 시나리오. 락 안 잡힌 좌석 중 하나 반환.
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * 시나리오 1: 특정 좌석.
     * 다른 트랜잭션이 이 행에 락을 걸고 있으면 결과가 비어 있다(SKIP LOCKED).
     * 호출 측은 "락 잡힌 상태" 와 "AVAILABLE이 아닌 상태" 를 구분할 수 없다 → false negative.
     */
    @Query(value = """
            SELECT *
              FROM seat
             WHERE id = :id
               AND status = 'AVAILABLE'
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Seat> findByIdSkipLocked(@Param("id") Long id);

    /**
     * 시나리오 2: 임의 가용 좌석 하나.
     * 락 안 잡힌 AVAILABLE 행 중 하나를 잡아온다. 락 잡힌 행은 건너뛴다.
     * 작업 큐 디스패치 패턴(여러 워커가 서로 다른 좌석을 동시에 처리)에 적합.
     */
    @Query(value = """
            SELECT *
              FROM seat
             WHERE status = 'AVAILABLE'
             FOR UPDATE SKIP LOCKED
             LIMIT 1
            """, nativeQuery = true)
    Optional<Seat> findAnyAvailableSkipLocked();
}
