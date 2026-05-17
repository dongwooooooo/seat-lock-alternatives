package com.dongwoo.altc;

import com.dongwoo.altc.domain.ReservationStatus;
import com.dongwoo.altc.domain.SeatStatus;
import com.dongwoo.altc.repository.ReservationRepository;
import com.dongwoo.altc.repository.SeatRepository;
import com.dongwoo.altc.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 대안 C — INSERT ON CONFLICT DO NOTHING 검증.
 *
 * 좌석 100에 동시 100건 reserve.
 * 기대:
 *   - heldCount = 1 (DB가 race 차단)
 *   - success = 1, raceLoser = 99
 *   - 99건은 예외 비용 거의 0 (영향 행 수 0 반환 → SeatNotAvailableException 단순 분기)
 *   - 좌석 status = HELD
 *
 * 추가 검증:
 *   - persistence-context 캐시 어긋남 없는지 — 서비스 끝에서 findAll로 다시 읽어 확인됨.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AltCConcurrencyTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatRepository seatRepository;

    @Test
    @DisplayName("좌석 100에 동시 100건 → INSERT ON CONFLICT로 1건만 HELD")
    void insert_on_conflict_blocks_oversell() throws Exception {
        Long seatId = 100L;
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger raceLoser = new AtomicInteger();
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
                    raceLoser.incrementAndGet();
                } catch (Exception e) {
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

        SeatStatus seatStatus = seatRepository.findById(seatId).orElseThrow().getStatus();

        System.out.println("=== ALT-C RESULT ===");
        System.out.println("success=" + success.get()
                + " raceLoser=" + raceLoser.get()
                + " otherErrors=" + otherErrors.get()
                + " heldCount=" + heldCount
                + " seatStatus=" + seatStatus
                + " elapsedMs=" + elapsed);

        assertEquals(1L, heldCount, "DB must have exactly 1 HELD reservation for the seat");
        assertEquals(1, success.get(), "exactly 1 reservation must succeed");
        assertEquals(99, raceLoser.get(), "remaining 99 must be ON CONFLICT race losers");
        assertEquals(0, otherErrors.get(), "no unexpected exceptions");
        assertEquals(SeatStatus.HELD, seatStatus, "seat must be HELD after winning insert");
    }
}
