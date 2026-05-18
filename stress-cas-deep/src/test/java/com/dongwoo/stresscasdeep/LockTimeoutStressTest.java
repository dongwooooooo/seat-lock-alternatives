package com.dongwoo.stresscasdeep;

import com.dongwoo.stresscasdeep.service.LockTimeoutReservationService;
import com.dongwoo.stresscasdeep.service.SeatNotAvailableException;
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
 * 시나리오 3 (CAS) — Lock wait timeout 환경에서 CAS UPDATE 행동.
 *
 * 별도 thread 가 raw JDBC 로 좌석 락을 5초 잡고 있는 상황 (SELECT FOR UPDATE).
 * CAS UPDATE 도 row write lock 을 획득하려고 하므로 wait. SET LOCAL lock_timeout='2s'
 * 가 적용되면 2초 후 lock_timeout 예외 발화 예상.
 *
 * 비관적 락 시나리오 (56 lockTimeout + 14 connTimeout) 대비 비교 측정.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LockTimeoutStressTest {

    @Autowired LockTimeoutReservationService service;
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("Lock timeout 2s + raw JDBC holder 5s (CAS) — CAS UPDATE 행동 측정")
    void lock_timeout_2s_under_long_holder() throws Exception {
        Long seatId = 30L;
        int threadCount = 100;
        long holderSleepMs = 5000L;

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

        long waitStart = System.currentTimeMillis();
        while (!holderReady.get() && System.currentTimeMillis() - waitStart < 5000) {
            Thread.sleep(10);
        }
        assertTrue(holderReady.get(), "lock holder must acquire lock first");

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

        holder.join(10_000);

        StressMetrics.Snapshot snap = m.snapshot(
                "Test 3 (CAS): Lock wait timeout (2s) — raw-JDBC holder for 5s",
                elapsed, threadCount);
        snap.print();
        System.out.println("config: pool=30, threadCount=" + threadCount + ", lockTimeout=2s, holderDelay=5s");
        System.out.println("verdict: lockTimeout=" + m.lockTimeout.get()
                + " connTimeout=" + m.connectionTimeout.get()
                + " seatNotAvailable=" + m.seatNotAvailable.get()
                + " success=" + m.success.get());

        // 측정만 — 합계 검증.
        int accounted = m.success.get() + m.seatNotAvailable.get() + m.dataIntegrityViolation.get()
                + m.connectionTimeout.get() + m.deadlock.get() + m.lockTimeout.get() + m.otherError.get();
        assertTrue(accounted == threadCount, "every request accounted for, got " + accounted);
    }
}
