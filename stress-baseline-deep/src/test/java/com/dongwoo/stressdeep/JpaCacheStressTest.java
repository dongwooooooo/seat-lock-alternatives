package com.dongwoo.stressdeep;

import com.dongwoo.stressdeep.domain.ReservationStatus;
import com.dongwoo.stressdeep.repository.ReservationRepository;
import com.dongwoo.stressdeep.service.ReservationService;
import com.dongwoo.stressdeep.service.SeatNotAvailableException;
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
 * 시나리오 1 — JPA persistence-context staleness 검증.
 *
 * 가설: Hibernate persistence context에 이미 캐시된 entity에 대해 @Lock(PESSIMISTIC_WRITE)
 *  를 적용할 때, Spring Data JPA의 `@Query + @Lock` 조합이 매번 새로운 SELECT FOR UPDATE
 *  를 발행하는지 확인. 만약 cache hit으로 SELECT 자체가 skip되면 lock 약속이 깨짐.
 *
 * 검증 방법: reserveWithCacheWarmup 동시 호출 100건 → race가 차단되는지 확인.
 *  - heldCount == 1 이면 락이 정상 작동
 *  - heldCount > 1 이면 락이 cache 때문에 무효화되는 critical bug
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JpaCacheStressTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;

    @Test
    @DisplayName("JPA persistence context staleness — non-lock 캐시 후 @Lock 동시 호출 100건")
    void jpa_cache_does_not_bypass_lock() throws Exception {
        Long seatId = 10L;
        int threadCount = 100;
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
                    reservationService.reserveWithCacheWarmup(seatId, "u-" + idx);
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
                "Test 1: JPA cache staleness — reserveWithCacheWarmup",
                elapsed, threadCount);
        snap.print();
        System.out.println("config: pool=30, threadCount=" + threadCount
                + ", path=non-lock findById -> findByIdForUpdate (same tx)");
        System.out.println("heldCount=" + heldCount + " (must be 1)");
        System.out.println("verdict: " + (heldCount == 1
                ? "PASS — Hibernate emits SELECT FOR UPDATE even on cached entity (lock honored)"
                : "FAIL — JPA cache bypassed lock, race leak observed"));

        assertEquals(1L, heldCount, "exactly 1 HELD even with persistence-context warm-up");
        assertEquals(1, m.success.get(), "exactly 1 success");
        assertTrue(m.success.get() + m.seatNotAvailable.get() + m.connectionTimeout.get()
                + m.deadlock.get() + m.lockTimeout.get() + m.otherError.get() == threadCount,
                "every request accounted for");
    }
}
