package com.dongwoo.stressdeep;

import com.dongwoo.stressdeep.domain.ReservationStatus;
import com.dongwoo.stressdeep.repository.ReservationRepository;
import com.dongwoo.stressdeep.service.SeatNotAvailableException;
import com.dongwoo.stressdeep.service.SlowReservationService;
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
 * 시나리오 4 — Long-running tx starvation.
 *
 * Critical section 내부에서 외부 API 호출 시뮬레이션 (Thread.sleep 2s).
 *  - 50 threads, 동일 좌석 → 락이 직렬화되면 총 100초 소요.
 *  - 풀이 충분해도 락 대기 길어짐 + lockTimeout 폭증.
 *
 * 본 테스트는 starvation 영향을 정량 측정.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LongTxStarvationStressTest {

    @Autowired SlowReservationService slowService;
    @Autowired ReservationRepository reservationRepository;

    @Test
    @DisplayName("Long tx (2s in critical section) + 50 threads — starvation 측정")
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
        // 50 threads * 2s sleep 직렬화 시 100s + 락 대기 overhead.
        // lock_timeout=2s 가 적용되면 대부분 lockTimeout 으로 떨어짐.
        done.await(120, TimeUnit.SECONDS);
        executor.shutdownNow();
        long elapsed = System.currentTimeMillis() - t0;

        long heldCount = reservationRepository.findBySeatId(seatId).stream()
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .count();

        StressMetrics.Snapshot snap = m.snapshot(
                "Test 4: Long-running tx starvation (sleep 2s inside lock)",
                elapsed, threadCount);
        snap.print();
        System.out.println("config: pool=30, threadCount=" + threadCount + ", sleepInTx=" + sleepInTxMs + "ms");
        System.out.println("heldCount=" + heldCount);
        System.out.println("verdict: " + (snap.p99() > 1500
                ? "OBSERVED starvation — p99=" + snap.p99() + "ms ≫ baseline (락 직렬화 효과)"
                : "p99=" + snap.p99() + "ms 의외로 빠름"));

        assertEquals(1L, heldCount, "exactly 1 HELD even under starvation");
        assertEquals(1, m.success.get(), "exactly 1 success");
        // 50 thread * 2초 직렬화 = p99이 매우 길어야 함 (lockTimeout이 막지 않으면).
        assertTrue(snap.p99() > 1000,
                "p99 should exceed 1s under starvation. observed=" + snap.p99() + "ms");
    }
}
