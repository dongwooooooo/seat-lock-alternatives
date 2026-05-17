package com.dongwoo.altb;

import com.dongwoo.altb.domain.ReservationStatus;
import com.dongwoo.altb.repository.ReservationRepository;
import com.dongwoo.altb.service.ReservationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대안 B — 낙관적 락 (@Version) + retry loop.
 *
 * 가설:
 *  - 정확히 1건만 HELD (oversell 차단은 성공)
 *  - 99건은 retry loop 거치며 wasted UPDATE attempts 누적
 *  - totalRetryAttempts >> 99 (retry storm 입증)
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AltBConcurrencyTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired EntityManager em;

    @Test
    @DisplayName("좌석 1에 동시 100건 → 1건 HELD + 99건 retry storm 측정")
    void optimistic_lock_retry_storm() throws Exception {
        Long seatId = insertSeat();
        reservationService.resetCounters();

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        long t0 = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    reservationService.reserve(seatId, "user-" + idx);
                    success.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
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

        long heldCount = reservationRepository.findAll().stream()
                .filter(r -> r.getSeatId().equals(seatId))
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .count();

        int totalRetries = reservationService.getTotalRetryAttempts().get();
        int gaveUp = reservationService.getGaveUpCount().get();
        int statusRejects = reservationService.getStatusRejectCount().get();

        System.out.println("===== ALT-B RESULT =====");
        System.out.println("success=" + success.get());
        System.out.println("rejected=" + rejected.get());
        System.out.println("heldCount=" + heldCount);
        System.out.println("retries=" + totalRetries);
        System.out.println("attempts=" + (success.get() + totalRetries + statusRejects));
        System.out.println("gaveUp=" + gaveUp);
        System.out.println("statusRejects=" + statusRejects);
        System.out.println("elapsedMs=" + elapsedMs);
        System.out.println("=========================");

        assertEquals(1, success.get(), "정확히 1건만 성공해야 함");
        assertEquals(99, rejected.get(), "나머지 99건은 reject");
        assertEquals(1L, heldCount, "DB에 HELD 1건만 존재");
        assertTrue(totalRetries > 0, "최소 1건은 OptimisticLockException → retry 발생해야 함");
    }

    @Transactional
    Long insertSeat() {
        // V1 스키마에 직접 insert (FK 없음).
        return ((Number) em.createNativeQuery(
                "INSERT INTO seat (section_id, seat_no, status, version) " +
                "VALUES (1, 1, 'AVAILABLE', 0) RETURNING id")
                .getSingleResult()).longValue();
    }
}
