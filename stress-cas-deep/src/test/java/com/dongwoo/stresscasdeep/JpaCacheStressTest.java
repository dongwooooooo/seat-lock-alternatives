package com.dongwoo.stresscasdeep;

import com.dongwoo.stresscasdeep.domain.ReservationStatus;
import com.dongwoo.stresscasdeep.repository.ReservationRepository;
import com.dongwoo.stresscasdeep.service.ReservationService;
import com.dongwoo.stresscasdeep.service.SeatNotAvailableException;
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
 * 시나리오 1 (CAS) — JPA persistence-context staleness 검증.
 *
 * CAS 는 native UPDATE 이므로 JPA 1차 캐시와 무관하게 DB 의 현재 상태로 판단.
 * 사용자 행동: 같은 좌석에 100명이 동시 클릭, 단 코드 경로가 먼저 findById() 로
 *  cache warmup 한 후 CAS UPDATE 호출.
 *
 * 가설: heldCount=1 유지. CAS 가 1차 캐시 stale snapshot 영향 받지 않음.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JpaCacheStressTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;

    @Test
    @DisplayName("JPA persistence context staleness (CAS) — non-lock 캐시 후 CAS 동시 호출 100건")
    void jpa_cache_does_not_bypass_cas() throws Exception {
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
                "Test 1 (CAS): JPA cache staleness — reserveWithCacheWarmup",
                elapsed, threadCount);
        snap.print();
        System.out.println("config: pool=30, threadCount=" + threadCount
                + ", path=findById(cached) -> casHold(native UPDATE) (same tx)");
        System.out.println("heldCount=" + heldCount + " (must be 1)");
        System.out.println("verdict: " + (heldCount == 1
                ? "PASS — CAS native UPDATE bypasses JPA cache, race blocked"
                : "FAIL — race leak observed"));

        assertEquals(1L, heldCount, "exactly 1 HELD even with persistence-context warm-up");
        assertEquals(1, m.success.get(), "exactly 1 success");
        assertTrue(m.success.get() + m.seatNotAvailable.get() + m.connectionTimeout.get()
                + m.deadlock.get() + m.lockTimeout.get() + m.otherError.get() == threadCount,
                "every request accounted for");
    }
}
