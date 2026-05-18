package com.dongwoo.stresscasdeep;

import com.dongwoo.stresscasdeep.domain.ReservationStatus;
import com.dongwoo.stresscasdeep.repository.ReservationRepository;
import com.dongwoo.stresscasdeep.service.SeatNotAvailableException;
import com.dongwoo.stresscasdeep.service.SlowReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시나리오 4 (CAS) — Long-running tx starvation.
 *
 * Critical section 내부에서 외부 API 호출 시뮬레이션 (sleep 2s).
 *  - 50 threads, 동일 좌석. CAS 도 commit 까지 row write lock 보유.
 *  - winner 의 2초 tx 동안 나머지 49 thread 의 CAS UPDATE 가 row lock wait
 *  - winner commit 후 나머지 thread 의 UPDATE 는 status='HELD' 이므로 affected=0 → SeatNotAvailable
 *
 * 비관적 락 (p99=2068ms) 와 비교 측정.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LongTxStarvationStressTest {

    @Autowired SlowReservationService slowService;
    @Autowired ReservationRepository reservationRepository;

    @Test
    @DisplayName("Long tx (2s in critical section) + 50 threads (CAS) — starvation 측정")
    void long_running_tx_starvation() throws Exception {
        Long seatId = 40L;
        int threadCount = 50;
        long sleepInTxMs = 2000L;
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        StressMetrics m = new StressMetrics();

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                long started = System.currentTimeMillis();
                try {
                    ready.countDown();
                    start.await();
                    slowService.reserveSlow(seatId, "u-" + idx, sleepInTxMs);
                    m.success.incrementAndGet();
                } catch (SeatNotAvailableException e) {
                    m.seatNotAvailable.incrementAndGet();
                } catch (Exception e) {
                    m.classifyException(e);
                } finally {
                    m.record(System.currentTimeMillis() - started);
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(120, TimeUnit.SECONDS);
        executor.shutdownNow();
        long elapsed = System.currentTimeMillis() - t0;

        long heldCount = reservationRepository.findBySeatId(seatId).stream()
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .count();

        StressMetrics.Snapshot snap = m.snapshot(
                "Test 4 (CAS): Long-running tx starvation (sleep 2s after CAS)",
                elapsed, threadCount);
        snap.print();
        System.out.println("config: pool=30, threadCount=" + threadCount + ", sleepInTx=" + sleepInTxMs + "ms");
        System.out.println("heldCount=" + heldCount);
        System.out.println("verdict: p99=" + snap.p99() + "ms");

        assertEquals(1L, heldCount, "exactly 1 HELD even under starvation");
        assertEquals(1, m.success.get(), "exactly 1 success");
        assertTrue(snap.p99() > 0, "p99 measured");
    }
}
