package com.dongwoo.stresscas;

import com.dongwoo.stresscas.domain.ReservationStatus;
import com.dongwoo.stresscas.repository.ReservationRepository;
import com.dongwoo.stresscas.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Distributed seat 시나리오 (CAS) — 좌석 1000개에 2000 동시 요청 + 20% hot 집중.
 *
 * 사용자 행동: 콘서트 오픈 시점, 사용자 2000명이 동시 클릭. 20% 가 1번 좌석에 몰림.
 * 서버 부위: ReservationService.reserve() → casHold() per seat.
 * 무엇 때문에: 좌석별 row 가 다르면 atomic UPDATE 가 서로 충돌 안 함 → 분산 처리.
 *   hot 좌석 1번만 직렬화. 락 보유 시간이 ~1ms 라 hot seat 도 빠르게 회전.
 * 사용자가 보는 결과: 좌석마다 1명씩 성공, 나머지는 "이미 선점됨".
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DistributedSeatStressTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc.execute("DELETE FROM reservation");
        jdbc.execute("UPDATE seat SET status='AVAILABLE'");
    }

    @Test
    @DisplayName("Distributed 1000 좌석 x 2000 동시 (CAS) — 풀=10 한계 측정")
    void distributed_seats_2000_concurrent() throws Exception {
        int seatCount = 1000;
        int threadCount = 2000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        StressMetrics m = new StressMetrics();
        Random rng = new Random(42);

        // stress-baseline 과 동일 분포 (재현성): 동일 seed=42.
        long[] targetSeats = new long[threadCount];
        for (int i = 0; i < threadCount; i++) {
            if (rng.nextDouble() < 0.20) {
                targetSeats[i] = 1L;
            } else {
                targetSeats[i] = 1 + rng.nextInt(seatCount);
            }
        }

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            final long seatId = targetSeats[i];
            executor.submit(() -> {
                long started = System.currentTimeMillis();
                try {
                    ready.countDown();
                    start.await();
                    reservationService.reserve(seatId, "u-" + idx);
                    m.success.incrementAndGet();
                } catch (ReservationService.SeatNotAvailableException e) {
                    m.seatNotAvailable.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    m.dataIntegrityViolation.incrementAndGet();
                } catch (DataAccessResourceFailureException e) {
                    m.connectionTimeout.incrementAndGet();
                } catch (Exception e) {
                    String msg = String.valueOf(e.getMessage());
                    Throwable cause = e.getCause();
                    String causeMsg = cause == null ? "" : String.valueOf(cause.getMessage());
                    if (msg.contains("Connection is not available")
                            || msg.contains("HikariPool")
                            || causeMsg.contains("Connection is not available")
                            || causeMsg.contains("HikariPool")) {
                        m.connectionTimeout.incrementAndGet();
                    } else {
                        m.otherError.incrementAndGet();
                    }
                } finally {
                    m.record(System.currentTimeMillis() - started);
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(300, TimeUnit.SECONDS);
        executor.shutdownNow();
        long elapsed = System.currentTimeMillis() - t0;

        long heldCount = reservationRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .count();

        StressMetrics.Snapshot snap = m.snapshot("Distributed 1000 좌석 x 2000 동시 (CAS)", elapsed, threadCount);
        snap.print();
        System.out.println("총 heldCount(전 좌석)=" + heldCount);

        assertTrue(heldCount <= seatCount, "held count must not exceed unique seats");
        assertTrue(m.success.get() == heldCount, "success must equal held reservations");
    }
}
