package com.dongwoo.alte;

import com.dongwoo.alte.domain.Reservation;
import com.dongwoo.alte.domain.SeatStatus;
import com.dongwoo.alte.repository.ReservationRepository;
import com.dongwoo.alte.repository.SeatRepository;
import com.dongwoo.alte.service.ReservationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시나리오 2 — 임의 가용 좌석 디스패치 (작업 큐 패턴).
 *
 * 100 좌석 시드, 1000 스레드 각자 "아무 가용 좌석이나 달라" 요청.
 *
 * SKIP LOCKED 동작:
 *  - 각 트랜잭션이 락 안 잡힌 AVAILABLE 좌석 중 하나를 잡는다
 *  - 100개 모두 다른 좌석에 분배됨 (락 충돌 회피)
 *  - 100건 성공, 나머지 900건은 좌석 소진으로 empty (true negative)
 *
 * 핵심 측정:
 *  - success = 100 (= min(threadCount, totalSeats))
 *  - 중복 좌석 없음 (각 좌석은 정확히 1번씩 분배)
 *  - 처리량(ops/sec): 락 대기 없는 디스패치 → 높은 throughput
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AltEAnyAvailableSeatTest {

    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired EntityManager em;

    @Test
    @DisplayName("시나리오 2: 좌석 100개에 1000 스레드 임의 요청 → 100건 성공 + 중복 없음")
    void skip_locked_any_available_dispatch() throws Exception {
        // V2__seed.sql이 section_id=1에 100 좌석 시드.
        long seedCount = (long) ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM seat WHERE section_id=1 AND status='AVAILABLE'")
                .getSingleResult()).longValue();
        assertEquals(100L, seedCount, "V2 seed로 100 좌석 AVAILABLE");

        int threadCount = 1000;
        int totalSeats = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger seatNoneAvailable = new AtomicInteger();
        Set<Long> assignedSeats = ConcurrentHashMap.newKeySet();

        long t0 = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    Reservation r = reservationService.reserveAny("user-" + idx);
                    success.incrementAndGet();
                    assignedSeats.add(r.getSeatId());
                } catch (ReservationService.NoSeatAvailableException e) {
                    seatNoneAvailable.incrementAndGet();
                } catch (Exception e) {
                    // 그 외 예외는 디스패치 실패로 간주.
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        long elapsedMs = System.currentTimeMillis() - t0;
        double throughput = success.get() * 1000.0 / Math.max(elapsedMs, 1);

        // 좌석별 reservation 개수 — 중복 검증.
        List<Reservation> reservations = reservationRepository.findAll();
        Set<Long> uniqueSeats = new HashSet<>();
        boolean doubleBooked = false;
        for (Reservation r : reservations) {
            if (!uniqueSeats.add(r.getSeatId())) {
                doubleBooked = true;
            }
        }

        long heldSeatCount = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM seat WHERE section_id=1 AND status='HELD'")
                .getSingleResult()).longValue();

        System.out.println("===== ALT-E SCENARIO 2 RESULT =====");
        System.out.println("threadCount=" + threadCount);
        System.out.println("totalSeats=" + totalSeats);
        System.out.println("success=" + success.get());
        System.out.println("seatNoneAvailable=" + seatNoneAvailable.get());
        System.out.println("uniqueSeatsAssigned=" + assignedSeats.size());
        System.out.println("heldSeatCount(DB)=" + heldSeatCount);
        System.out.println("reservationsTotal=" + reservations.size());
        System.out.println("doubleBooked=" + doubleBooked);
        System.out.println("elapsedMs=" + elapsedMs);
        System.out.printf ("throughput=%.1f ops/sec%n", throughput);
        System.out.println("===================================");

        assertEquals(totalSeats, success.get(),
                "success = min(threadCount, totalSeats) = 100");
        assertEquals((long) totalSeats, heldSeatCount, "DB에 HELD 좌석 100개");
        assertEquals(totalSeats, assignedSeats.size(),
                "100개 좌석 모두 서로 다른 좌석에 분배됨");
        assertEquals(totalSeats, reservations.size(), "reservation 100건 생성");
        assertFalse(doubleBooked, "중복 예약 없음");
        assertTrue(seatNoneAvailable.get() >= threadCount - totalSeats,
                "좌석 소진으로 실패한 건이 900건 이상");
        assertTrue(elapsedMs < 60_000, "60초 내 완료");
    }
}
