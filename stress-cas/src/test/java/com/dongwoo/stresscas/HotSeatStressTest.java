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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hot seat 시나리오 (CAS) — 좌석 1개에 1000명 동시 요청.
 *
 * 사용자 행동: BTS 콘서트 11:00:00 정각, R열 1번 (VIP) 좌석에 1000명이 동시 클릭.
 * 서버 부위: ReservationService.reserve() → SeatRepository.casHold() (atomic UPDATE).
 * 무엇 때문에: PostgreSQL 의 UPDATE 가 row lock 을 1ms 단위로 잡고 푸므로,
 *   1명만 affected=1, 999명은 affected=0 → SeatNotAvailableException.
 * 사용자가 보는 결과: 1명은 결제 페이지로 이동, 999명은 "이미 선점됨" 메시지.
 *
 * 비관적 락 대비 가설:
 *  - heldCount = 1 (CAS 가 race 차단)
 *  - p99 latency 낮음 (락 보유 시간 ~1ms → 큐 대기 없음)
 *  - throughput 높음
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class HotSeatStressTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc.execute("DELETE FROM reservation");
        jdbc.execute("UPDATE seat SET status='AVAILABLE'");
    }

    @Test
    @DisplayName("Hot seat 1000 동시 (CAS) — atomic UPDATE 로 race 차단")
    void hot_seat_1000_concurrent() throws Exception {
        Long seatId = 1L;
        int threadCount = 1000;
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
        done.await(180, TimeUnit.SECONDS);
        executor.shutdownNow();
        long elapsed = System.currentTimeMillis() - t0;

        long heldCount = reservationRepository.findAll().stream()
                .filter(r -> r.getSeatId().equals(seatId))
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .count();

        StressMetrics.Snapshot snap = m.snapshot("Hot Seat 1000 동시 (CAS)", elapsed, threadCount);
        snap.print();
        System.out.println("heldCount=" + heldCount);

        // 정합성: race 차단 보장.
        assertEquals(1L, heldCount, "DB must have exactly 1 HELD reservation for the hot seat");
        assertEquals(1, m.success.get(), "exactly 1 reservation must succeed");
        assertTrue(m.success.get() + m.seatNotAvailable.get() + m.dataIntegrityViolation.get()
                + m.connectionTimeout.get() + m.otherError.get() == threadCount,
                "every request must be accounted for");
    }
}
