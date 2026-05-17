package com.dongwoo.stressdeep;

import com.dongwoo.stressdeep.service.DeadlockReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시나리오 2 — Deadlock under multi-row locking (잘못된 ordering).
 *
 * 다중 좌석 락 시 ordering 일관성이 없으면 PostgreSQL이 `40P01 deadlock_detected`
 *  로 한쪽 tx를 abort 시킨다.
 *
 * 가설: lockSeatAThenB(A→B) 와 lockSeatBThenA(B→A) 가 동시 다발로 실행되면 deadlock 다수 발생.
 *
 * 의도된 inverted assertion: assertTrue(deadlockCount > 0).
 *  운영에서 발견될 수 있는 critical hidden risk를 입증.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeadlockStressTest {

    @Autowired DeadlockReservationService deadlockService;

    @Test
    @DisplayName("Multi-row lock ordering 불일치 — deadlock 발생 입증")
    void deadlock_under_inconsistent_lock_ordering() throws Exception {
        Long seatA = 20L;
        Long seatB = 21L;
        int pairCount = 30;  // 30쌍 = 60 threads (각 쌍이 A→B와 B→A 동시 실행)
        ExecutorService executor = Executors.newFixedThreadPool(60);
        CountDownLatch ready = new CountDownLatch(pairCount * 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(pairCount * 2);
        StressMetrics m = new StressMetrics();

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < pairCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                long started = System.currentTimeMillis();
                try {
                    ready.countDown();
                    start.await();
                    deadlockService.lockSeatAThenB(seatA, seatB, "ab-" + idx);
                    m.success.incrementAndGet();
                } catch (Exception e) {
                    m.classifyException(e);
                } finally {
                    m.record(System.currentTimeMillis() - started);
                    done.countDown();
                }
            });
            executor.submit(() -> {
                long started = System.currentTimeMillis();
                try {
                    ready.countDown();
                    start.await();
                    deadlockService.lockSeatBThenA(seatA, seatB, "ba-" + idx);
                    m.success.incrementAndGet();
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

        StressMetrics.Snapshot snap = m.snapshot(
                "Test 2: Deadlock — inverted lock ordering",
                elapsed, pairCount * 2);
        snap.print();
        System.out.println("config: pool=30, pairs=" + pairCount + ", seatA=" + seatA + " seatB=" + seatB);
        System.out.println("verdict: " + (m.deadlock.get() > 0
                ? "OBSERVED deadlock — 40P01 fires when lock ordering inconsistent (count="
                  + m.deadlock.get() + ")"
                : "no deadlock — but PG may have serialized via timing"));

        assertTrue(m.deadlock.get() > 0,
                "expected at least 1 deadlock under inverted lock ordering. observed="
                + m.deadlock.get() + " (success=" + m.success.get() + ", other=" + m.otherError.get() + ")");
    }
}
