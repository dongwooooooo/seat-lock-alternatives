package com.dongwoo.stressdeep;

import com.dongwoo.stressdeep.domain.Reservation;
import com.dongwoo.stressdeep.domain.ReservationStatus;
import com.dongwoo.stressdeep.domain.Seat;
import com.dongwoo.stressdeep.domain.SeatStatus;
import com.dongwoo.stressdeep.repository.ReservationRepository;
import com.dongwoo.stressdeep.repository.SeatRepository;
import com.dongwoo.stressdeep.service.FailingReservationService;
import com.dongwoo.stressdeep.service.SeatNotAvailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시나리오 5 — Rollback storm.
 *
 * 100 좌석을 각자 1 thread가 점유 시도. 30% 확률로 reservation INSERT 후 강제 rollback.
 * 검증:
 *  - rollback된 경우 seat.hold() 변경이 원복되어 seat status가 AVAILABLE로 유지
 *  - rollback된 reservation row가 DB에 남지 않음 (atomic tx)
 *  - 성공한 좌석은 정확히 HELD, 실패한 좌석은 AVAILABLE
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RollbackStormStressTest {

    @Autowired FailingReservationService failingService;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;

    @Test
    @DisplayName("Rollback storm — 30% fail rate, atomic tx 검증 (각 좌석 1 thread)")
    void rollback_storm_no_orphan_state() throws Exception {
        // 좌석 50~99 (50개) 사용 — 각자 1 thread만 시도.
        long seatStart = 50L;
        int seatCount = 50;
        double failRate = 0.30;
        ExecutorService executor = Executors.newFixedThreadPool(seatCount);
        CountDownLatch ready = new CountDownLatch(seatCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(seatCount);
        StressMetrics m = new StressMetrics();

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < seatCount; i++) {
            final long seatId = seatStart + i;
            final int idx = i;
            executor.submit(() -> {
                long started = System.currentTimeMillis();
                try {
                    ready.countDown();
                    start.await();
                    failingService.reserveSometimesFailing(seatId, "u-" + idx, failRate);
                    m.success.incrementAndGet();
                } catch (SeatNotAvailableException e) {
                    m.seatNotAvailable.incrementAndGet();
                } catch (FailingReservationService.IntentionalFailure e) {
                    m.intentionalRollback.incrementAndGet();
                } catch (Exception e) {
                    // org.springframework 으로 한번 wrap 될 수 있음. 원인 따라 분류.
                    Throwable c = e.getCause();
                    if (c instanceof FailingReservationService.IntentionalFailure) {
                        m.intentionalRollback.incrementAndGet();
                    } else {
                        m.classifyException(e);
                    }
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

        // 검증: 50개 좌석 각각의 최종 상태.
        int heldSeats = 0;
        int availableSeats = 0;
        int reservationRows = 0;
        int orphanCount = 0;  // seat=HELD 인데 reservation 없거나, seat=AVAILABLE 인데 reservation 있음.
        for (int i = 0; i < seatCount; i++) {
            long seatId = seatStart + i;
            Seat seat = seatRepository.findById(seatId).orElseThrow();
            List<Reservation> resvs = reservationRepository.findBySeatId(seatId).stream()
                    .filter(r -> r.getStatus() == ReservationStatus.HELD)
                    .toList();
            if (seat.getStatus() == SeatStatus.HELD) {
                heldSeats++;
                reservationRows += resvs.size();
                if (resvs.size() != 1) orphanCount++;
            } else if (seat.getStatus() == SeatStatus.AVAILABLE) {
                availableSeats++;
                if (!resvs.isEmpty()) orphanCount++;
            }
        }

        StressMetrics.Snapshot snap = m.snapshot(
                "Test 5: Rollback storm (30% fail rate, per-seat)",
                elapsed, seatCount);
        snap.print();
        System.out.println("config: pool=30, seatCount=" + seatCount + ", failRate=" + failRate);
        System.out.println("rollbackCount(service)=" + failingService.getRollbackCount());
        System.out.println("heldSeats=" + heldSeats + " availableSeats=" + availableSeats
                + " reservationRows(HELD)=" + reservationRows + " orphanCount=" + orphanCount);
        System.out.println("verdict: " + (orphanCount == 0
                ? "PASS — rollback atomic; no orphan state across " + seatCount + " seats. "
                  + "rolled-back seats stayed AVAILABLE and reservation row absent."
                : "FAIL — " + orphanCount + " orphan seats detected"));

        // 성공 + 롤백 + 기타 = seatCount.
        int accounted = m.success.get() + m.seatNotAvailable.get() + m.intentionalRollback.get()
                + m.otherError.get() + m.connectionTimeout.get() + m.deadlock.get() + m.lockTimeout.get();
        assertEquals(seatCount, accounted, "all attempts accounted for");
        assertTrue(m.intentionalRollback.get() > 0, "expected some intentional rollbacks (30% rate, "
                + seatCount + " trials)");
        assertEquals(0, orphanCount, "no orphan state allowed (atomic tx)");
        // 성공 + 롤백 = seatCount (모든 좌석이 처음이라 AVAILABLE).
        assertEquals(seatCount, m.success.get() + m.intentionalRollback.get(),
                "all attempts result in success or rollback");
    }
}
