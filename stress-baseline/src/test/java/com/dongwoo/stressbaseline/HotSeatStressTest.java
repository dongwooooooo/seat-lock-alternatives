package com.dongwoo.stressbaseline;

import com.dongwoo.stressbaseline.domain.ReservationStatus;
import com.dongwoo.stressbaseline.repository.ReservationRepository;
import com.dongwoo.stressbaseline.service.ReservationService;
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
 * Hot seat 시나리오 — 좌석 1개에 1000명 동시 요청 (매진 hot seat 시뮬레이션).
 *
 * 가설:
 *  - heldCount = 1 (락+UNIQUE가 race 차단)
 *  - success = 1
 *  - 나머지 999건 중:
 *    * 일부: SeatNotAvailableException (락 해제 후 HELD 상태 확인)
 *    * 일부: connection timeout (락 대기 큐가 길어져 풀 acquisition 실패)
 *  - p99 latency 폭증 (락 대기시간 합산)
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class HotSeatStressTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        // Spring Boot test context는 testcontainer 재사용 → 이전 테스트 상태 격리.
        jdbc.execute("DELETE FROM reservation");
        jdbc.execute("UPDATE seat SET status='AVAILABLE'");
    }

    @Test
    @DisplayName("Hot seat 1000 동시 — race 차단되지만 풀/대기로 p99 폭증, timeout 발생")
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

        StressMetrics.Snapshot snap = m.snapshot("Hot Seat 1000 동시", elapsed, threadCount);
        snap.print();
        System.out.println("heldCount=" + heldCount);

        // 정합성: race 차단 보장.
        assertEquals(1L, heldCount, "DB must have exactly 1 HELD reservation for the hot seat");
        assertEquals(1, m.success.get(), "exactly 1 reservation must succeed");
        // 관찰 assertion — 부하로 인한 한계 노출 확인.
        assertTrue(m.success.get() + m.seatNotAvailable.get() + m.dataIntegrityViolation.get()
                + m.connectionTimeout.get() + m.otherError.get() == threadCount,
                "every request must be accounted for");
    }
}
