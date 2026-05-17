package com.dongwoo.stressdeep;

import com.dongwoo.stressdeep.service.LockTimeoutReservationService;
import com.dongwoo.stressdeep.service.SeatNotAvailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시나리오 3 — Lock wait timeout.
 *
 * lock_timeout=2000ms (QueryHint) + 별도 thread가 raw JDBC로 좌석 락을 5초 잡고 있음.
 *  그 사이 100개 thread가 reserveWithLockTimeout 호출 → 2초 후 LockTimeoutException 다수.
 *
 * 가설: 99건 lockTimeout 거절, latency p99 ≈ 2000ms (lock_timeout 시점).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LockTimeoutStressTest {

    @Autowired LockTimeoutReservationService service;
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("Lock timeout 2초 — 별도 thread가 5초 락 보유, 100개 동시 요청은 lockTimeout 거절")
    void lock_timeout_2s_under_long_holder() throws Exception {
        Long seatId = 30L;
        int threadCount = 100;
        long holderSleepMs = 5000L;

        // Step 1: raw JDBC로 좌석 락을 5초 잡는 holder thread 시작.
        AtomicBoolean holderReady = new AtomicBoolean(false);
        AtomicBoolean holderDone = new AtomicBoolean(false);
        Thread holder = new Thread(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM seat WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, seatId);
                    ps.executeQuery();
                }
                holderReady.set(true);
                Thread.sleep(holderSleepMs);
                conn.commit();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                holderDone.set(true);
            }
        }, "lock-holder");
        holder.start();

        // Step 2: holder가 lock 확보할 때까지 대기.
        long waitStart = System.currentTimeMillis();
        while (!holderReady.get() && System.currentTimeMillis() - waitStart < 5000) {
            Thread.sleep(10);
        }
        assertTrue(holderReady.get(), "lock holder must acquire lock first");

        // Step 3: 100 threads 동시 reserve 시도 → lockTimeout 2s 발동 예상.
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
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
                    service.reserveWithLockTimeout(seatId, "u-" + idx, 0L);
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
        done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();
        long elapsed = System.currentTimeMillis() - t0;

        // Step 4: holder 끝날 때까지 대기 (cleanup).
        holder.join(10_000);

        StressMetrics.Snapshot snap = m.snapshot(
                "Test 3: Lock wait timeout (2s) — raw-JDBC holder for 5s",
                elapsed, threadCount);
        snap.print();
        System.out.println("config: pool=30, threadCount=" + threadCount + ", lockTimeout=2s, holderDelay=5s");
        System.out.println("verdict: " + (m.lockTimeout.get() > 0
                ? "OBSERVED lock_timeout — " + m.lockTimeout.get() + " requests rejected at ~2s"
                : "no lockTimeout — check QueryHint propagation"));

        assertTrue(m.lockTimeout.get() > 0,
                "expected at least 1 lock timeout. observed=" + m.lockTimeout.get()
                + " (success=" + m.success.get() + " seatNotAvailable=" + m.seatNotAvailable.get()
                + " other=" + m.otherError.get() + " connTimeout=" + m.connectionTimeout.get() + ")");
    }
}
