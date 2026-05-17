package com.dongwoo.alta;

import com.dongwoo.alta.domain.ReservationStatus;
import com.dongwoo.alta.repository.ReservationRepository;
import com.dongwoo.alta.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 대안 A — partial UNIQUE 단독 검증.
 *
 * 좌석 100에 동시 100건 reserve.
 * 기대:
 *   - heldCount = 1 (DB가 race 차단)
 *   - success = 1, rejected = 99
 *   - 99건 중 대부분이 DataIntegrityViolationException (PSQL 23505)로 분류됨
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AltAConcurrencyTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;

    @Test
    @DisplayName("좌석 100에 동시 100건 → partial UNIQUE 단독으로 1건만 HELD")
    void partial_unique_alone_blocks_oversell() throws Exception {
        Long seatId = 100L;
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger dataIntegrityViolations = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    reservationService.reserve(seatId, "user-" + idx);
                    success.incrementAndGet();
                } catch (ReservationService.SeatNotAvailableException e) {
                    rejected.incrementAndGet();
                    // partial UNIQUE에서 변환된 케이스
                    if (e.getMessage() != null && e.getMessage().contains("partial UNIQUE")) {
                        dataIntegrityViolations.incrementAndGet();
                    }
                } catch (DataIntegrityViolationException e) {
                    rejected.incrementAndGet();
                    dataIntegrityViolations.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                    otherErrors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();
        long elapsed = System.currentTimeMillis() - t0;

        long heldCount = reservationRepository.findAll().stream()
                .filter(r -> r.getSeatId().equals(seatId))
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .count();

        System.out.println("=== ALT-A RESULT ===");
        System.out.println("success=" + success.get()
                + " rejected=" + rejected.get()
                + " DataIntegrityViolation=" + dataIntegrityViolations.get()
                + " otherErrors=" + otherErrors.get()
                + " heldCount=" + heldCount
                + " elapsedMs=" + elapsed);

        assertEquals(1L, heldCount, "DB must have exactly 1 HELD reservation for the seat");
        assertEquals(1, success.get(), "exactly 1 reservation must succeed");
        assertEquals(99, rejected.get(), "remaining 99 must be rejected");
    }
}
