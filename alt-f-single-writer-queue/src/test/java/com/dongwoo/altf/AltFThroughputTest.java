package com.dongwoo.altf;

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
 * 대안 F — 단일 작성자 큐 처리량 한계 측정.
 *
 * 가설:
 *  - 서로 다른 1000개 좌석에 1000건 동시 요청 → 모두 성공
 *  - 하지만 worker 1개가 직렬 처리 → throughput hard cap
 *  - 다중 worker 또는 락 기반 대안 대비 명백히 느려야 함
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AltFThroughputTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SingleWriterReservationQueue queue;
    @Autowired EntityManager em;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final int SEAT_COUNT = 1000;

    @BeforeEach
    void resetState() {
        jdbc.execute("DELETE FROM reservation");
        jdbc.execute("DELETE FROM seat");
    }

    @Test
    @DisplayName("1000 좌석 × 1000 스레드 → 직렬 worker 처리량 측정")
    void single_writer_throughput_ceiling() throws Exception {
        List<Long> seatIds = insertSeats(SEAT_COUNT);
        queue.resetMetrics();

        int threadCount = SEAT_COUNT;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Long> latenciesNs = Collections.synchronizedList(new ArrayList<>(threadCount));

        for (int i = 0; i < threadCount; i++) {
            final Long seatId = seatIds.get(i);
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
        long t0 = System.nanoTime();
        start.countDown();
        done.await();
        long elapsedNs = System.nanoTime() - t0;
        executor.shutdown();

        double elapsedSec = elapsedNs / 1_000_000_000.0;
        double throughput = success.get() / elapsedSec;
        long avgMs = computeAvgMs(latenciesNs);
        long p99Ms = computePercentileMs(latenciesNs, 0.99);

        System.out.println("===== ALT-F THROUGHPUT RESULT =====");
        System.out.println("seats=" + SEAT_COUNT);
        System.out.println("threads=" + threadCount);
        System.out.println("success=" + success.get());
        System.out.println("rejected=" + rejected.get());
        System.out.println("queueMaxDepth=" + queue.getMaxObservedDepth());
        System.out.println("elapsedSec=" + String.format("%.3f", elapsedSec));
        System.out.println("throughputOpsPerSec=" + String.format("%.1f", throughput));
        System.out.println("avgLatencyMs=" + avgMs);
        System.out.println("p99LatencyMs=" + p99Ms);
        System.out.println("perItemMs=" + String.format("%.3f", elapsedSec * 1000.0 / success.get()));
        System.out.println("===================================");

        assertEquals(SEAT_COUNT, success.get(), "모든 좌석은 다르므로 1000건 전부 성공");
        assertEquals(0, rejected.get(), "충돌 없음");
        assertTrue(throughput < 5000.0,
                "단일 worker 처리량은 본질적으로 cap이 있음 (현실 측정치=" + throughput + ")");
    }

    List<Long> insertSeats(int count) {
        // 별도 section_id (=99)로 throughput용 좌석 격리.
        // concurrency 테스트와 컨텍스트 공유 시 seat_no 충돌 방지.
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO seat (section_id, seat_no, status) " +
                    "VALUES (99, " + (i + 1) + ", 'AVAILABLE') RETURNING id",
                    Long.class);
            ids.add(id);
        }
        return ids;
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
