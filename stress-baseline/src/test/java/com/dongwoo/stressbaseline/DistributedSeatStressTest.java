package com.dongwoo.stressbaseline;

import com.dongwoo.stressbaseline.domain.ReservationStatus;
import com.dongwoo.stressbaseline.repository.ReservationRepository;
import com.dongwoo.stressbaseline.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Distributed seat 시나리오 — 좌석 1000개에 2000 동시 요청 (현실 가까운 분산 부하 + hot 좌석 일부).
 *
 * 가설:
 *  - 좌석별 sharding 으로 hot 외에는 직렬화 안 됨.
 *  - 그러나 동시 2000 > pool 10 → 풀 큐 길이 증가, p99 latency 폭증.
 *  - 일부 connection timeout 발생.
 *  - throughput plateau (pool size로 상한 결정).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DistributedSeatStressTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc.execute("DELETE FROM reservation");
        jdbc.execute("UPDATE seat SET status='AVAILABLE'");
    }

    @Test
    @DisplayName("Distributed 1000 좌석 x 2000 동시 — pool=10 한계로 throughput plateau")
    void distributed_seats_2000_concurrent() throws Exception {
        int seatCount = 1000;
        int threadCount = 2000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        StressMetrics m = new StressMetrics();
        Random rng = new Random(42);

        // 매핑: 80%는 분산, 20%는 같은 hot 좌석 #1 으로 집중 (현실의 hot seat 시뮬레이션).
        long[] targetSeats = new long[threadCount];
        for (int i = 0; i < threadCount; i++) {
            if (rng.nextDouble() < 0.20) {
                targetSeats[i] = 1L;
            } else {
                targetSeats[i] = 1 + rng.nextInt(seatCount);
            }
        }

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            final long seatId = targetSeats[i];
            executor.submit(() -> {
                long started = System.currentTimeMillis();
                try {
                    ready.countDown();
                    start.await();
                    reservationService.reserve(seatId, "u-" + idx);
                    m.success.incrementAndGet();
                } catch (ReservationService.SeatNotAvailableException e) {
                    m.seatNotAvailable.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    m.dataIntegrityViolation.incrementAndGet();
                } catch (DataAccessResourceFailureException e) {
                    m.connectionTimeout.incrementAndGet();
                } catch (Exception e) {
                    String msg = String.valueOf(e.getMessage());
                    Throwable cause = e.getCause();
                    String causeMsg = cause == null ? "" : String.valueOf(cause.getMessage());
                    if (msg.contains("Connection is not available")
                            || msg.contains("HikariPool")
                            || causeMsg.contains("Connection is not available")
                            || causeMsg.contains("HikariPool")) {
                        m.connectionTimeout.incrementAndGet();
                    } else {
                        m.otherError.incrementAndGet();
                    }
                } finally {
                    m.record(System.currentTimeMillis() - started);
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(300, TimeUnit.SECONDS);
        executor.shutdownNow();
        long elapsed = System.currentTimeMillis() - t0;

        long heldCount = reservationRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .count();

        StressMetrics.Snapshot snap = m.snapshot("Distributed 1000 좌석 x 2000 동시", elapsed, threadCount);
        snap.print();
        System.out.println("총 heldCount(전 좌석)=" + heldCount);

        // 관찰: 좌석당 최대 1건 (정합성 유지). 부하는 풀 한계까지 도달 가능.
        assertTrue(heldCount <= seatCount, "held count must not exceed unique seats");
        assertTrue(m.success.get() == heldCount, "success must equal held reservations");
    }
}
