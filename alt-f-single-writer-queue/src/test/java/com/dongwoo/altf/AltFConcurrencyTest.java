package com.dongwoo.altf;

import com.dongwoo.altf.domain.ReservationStatus;
import com.dongwoo.altf.queue.SingleWriterReservationQueue;
import com.dongwoo.altf.repository.ReservationRepository;
import com.dongwoo.altf.service.ReservationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대안 F — 단일 작성자 큐 race 정확성 검증.
 *
 * 가설:
 *  - 정확히 1건만 HELD (worker 직렬화로 race 자체가 사라짐)
 *  - 99건은 SeatNotAvailable로 reject
 *  - 큐 max depth ≈ 100 (모든 요청이 거의 동시에 도착)
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AltFConcurrencyTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SingleWriterReservationQueue queue;
    @Autowired EntityManager em;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc.execute("DELETE FROM reservation");
        jdbc.execute("DELETE FROM seat");
    }

    @Test
    @DisplayName("좌석 1에 동시 100건 → 1건만 HELD + worker 직렬화로 race 차단")
    void single_writer_blocks_oversell() throws Exception {
        Long seatId = insertSeat();
        queue.resetMetrics();

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Long> latenciesNs = Collections.synchronizedList(new ArrayList<>(threadCount));

        long t0 = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    long submitNs = System.nanoTime();
                    reservationService.reserve(seatId, "user-" + idx);
                    latenciesNs.add(System.nanoTime() - submitNs);
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

        long p99Ms = computePercentileMs(latenciesNs, 0.99);
        long avgMs = computeAvgMs(latenciesNs);

        System.out.println("===== ALT-F RACE RESULT =====");
        System.out.println("success=" + success.get());
        System.out.println("rejected=" + rejected.get());
        System.out.println("heldCount=" + heldCount);
        System.out.println("queueMaxDepth=" + queue.getMaxObservedDepth());
        System.out.println("processedCount=" + queue.getProcessedCount());
        System.out.println("elapsedMs=" + elapsedMs);
        System.out.println("successLatencyAvgMs=" + avgMs);
        System.out.println("successLatencyP99Ms=" + p99Ms);
        System.out.println("=============================");

        assertEquals(1, success.get(), "정확히 1건만 성공해야 함");
        assertEquals(99, rejected.get(), "나머지 99건은 reject");
        assertEquals(1L, heldCount, "DB에 HELD 1건만 존재");
        assertTrue(queue.getMaxObservedDepth() > 1,
                "큐에 동시 요청이 쌓여야 함 (직렬화 입증)");
    }

    Long insertSeat() {
        return jdbc.queryForObject(
                "INSERT INTO seat (section_id, seat_no, status) " +
                "VALUES (1, 1, 'AVAILABLE') RETURNING id",
                Long.class);
    }

    private static long computePercentileMs(List<Long> latenciesNs, double percentile) {
        if (latenciesNs.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<>(latenciesNs);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(percentile * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx) / 1_000_000;
    }

    private static long computeAvgMs(List<Long> latenciesNs) {
        if (latenciesNs.isEmpty()) return 0L;
        long sum = 0;
        for (Long ns : latenciesNs) sum += ns;
        return (sum / latenciesNs.size()) / 1_000_000;
    }
}
