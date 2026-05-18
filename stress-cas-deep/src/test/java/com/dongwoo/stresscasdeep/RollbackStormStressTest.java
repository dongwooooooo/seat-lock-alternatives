package com.dongwoo.stresscasdeep;

import com.dongwoo.stresscasdeep.domain.Reservation;
import com.dongwoo.stresscasdeep.domain.ReservationStatus;
import com.dongwoo.stresscasdeep.domain.Seat;
import com.dongwoo.stresscasdeep.domain.SeatStatus;
import com.dongwoo.stresscasdeep.repository.ReservationRepository;
import com.dongwoo.stresscasdeep.repository.SeatRepository;
import com.dongwoo.stresscasdeep.service.FailingReservationService;
import com.dongwoo.stresscasdeep.service.SeatNotAvailableException;
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
 * 시나리오 5 (CAS) — Rollback storm.
 *
 * 30% 확률로 CAS hold + reservation INSERT 후 강제 예외 → rollback.
 * 검증: CAS UPDATE 와 reservation INSERT 가 같은 tx 안에서 모두 atomic 하게 복구
 *  → orphan (seat=HELD 인데 reservation 없음) 없음.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RollbackStormStressTest {

    @Autowired FailingReservationService failingService;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;

    @Test
    @DisplayName("Rollback storm (CAS) — 30% fail rate, atomic tx 검증")
    void rollback_storm_no_orphan_state() throws Exception {
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

        int heldSeats = 0;
        int availableSeats = 0;
        int reservationRows = 0;
        int orphanCount = 0;
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
                "Test 5 (CAS): Rollback storm (30% fail rate, per-seat)",
                elapsed, seatCount);
        snap.print();
        System.out.println("config: pool=30, seatCount=" + seatCount + ", failRate=" + failRate);
        System.out.println("rollbackCount(service)=" + failingService.getRollbackCount());
        System.out.println("heldSeats=" + heldSeats + " availableSeats=" + availableSeats
                + " reservationRows(HELD)=" + reservationRows + " orphanCount=" + orphanCount);
        System.out.println("verdict: " + (orphanCount == 0
                ? "PASS — rollback atomic across " + seatCount + " seats"
                : "FAIL — " + orphanCount + " orphan seats detected"));

        int accounted = m.success.get() + m.seatNotAvailable.get() + m.intentionalRollback.get()
                + m.otherError.get() + m.connectionTimeout.get() + m.deadlock.get() + m.lockTimeout.get();
        assertEquals(seatCount, accounted, "all attempts accounted for");
        assertTrue(m.intentionalRollback.get() > 0, "expected some intentional rollbacks");
        assertEquals(0, orphanCount, "no orphan state allowed (atomic tx)");
        assertEquals(seatCount, m.success.get() + m.intentionalRollback.get(),
                "all attempts result in success or rollback");
    }
}
