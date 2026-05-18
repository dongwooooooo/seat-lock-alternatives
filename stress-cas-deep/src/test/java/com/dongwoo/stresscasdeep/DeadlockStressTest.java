package com.dongwoo.stresscasdeep;

import com.dongwoo.stresscasdeep.service.DeadlockReservationService;
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
 * 시나리오 2 (CAS) — Multi-row CAS + 역순 시도.
 *
 * 비관적 락 시나리오는 SELECT FOR UPDATE A → sleep → SELECT FOR UPDATE B (와 역순)
 * 조합이 deadlock 을 일으켰다 (59/60 deadlock).
 *
 * CAS 도 atomic UPDATE 가 row write lock 을 commit 까지 보유하므로 cycle 형성 가능.
 *  - Thread1: UPDATE A (lock A taken) → sleep → UPDATE B 시도 (waits for B's lock)
 *  - Thread2: UPDATE B (lock B taken) → sleep → UPDATE A 시도 (waits for A's lock)
 *  - PG deadlock detector 가 한쪽 abort
 *
 * 본 테스트는 측정만 한다 — CAS 도 deadlock 이 발생하는지, 발생률이 비관적 락 대비
 * 어떻게 다른지 정량화. assertion 은 "총합이 맞아야 한다" 만.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeadlockStressTest {

    @Autowired DeadlockReservationService deadlockService;

    @Test
    @DisplayName("Multi-row CAS + 역순 시도 — deadlock 발생률 측정")
    void deadlock_under_inverted_cas_ordering() throws Exception {
        Long seatA = 20L;
        Long seatB = 21L;
        int pairCount = 30;  // 30쌍 = 60 threads
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
                "Test 2 (CAS): Deadlock — inverted CAS ordering",
                elapsed, pairCount * 2);
        snap.print();
        System.out.println("config: pool=30, pairs=" + pairCount + ", seatA=" + seatA + " seatB=" + seatB);
        System.out.println("verdict: deadlock count=" + m.deadlock.get()
                + " success=" + m.success.get() + " other=" + m.otherError.get()
                + " seatNotAvailable=" + m.seatNotAvailable.get());

        // 측정만 — 어떤 결과든 (deadlock 0 이든 60이든) 합계만 맞으면 PASS.
        int total = pairCount * 2;
        int accounted = m.success.get() + m.seatNotAvailable.get() + m.dataIntegrityViolation.get()
                + m.connectionTimeout.get() + m.deadlock.get() + m.lockTimeout.get() + m.otherError.get();
        assertTrue(accounted == total, "every attempt accounted for, total=" + total + " accounted=" + accounted);
    }
}
