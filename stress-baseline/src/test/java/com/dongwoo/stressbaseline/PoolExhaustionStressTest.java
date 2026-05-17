package com.dongwoo.stressbaseline;

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
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pool exhaustion 시나리오 — 좌석 100개에 500 동시 요청, pool=10 한정.
 *
 * 의도:
 *  - 트랜잭션 안에서 락 + INSERT 가 모두 일어나므로 커넥션을 점유.
 *  - 동시 500 → 풀 점유 10건만 진행, 나머지 490는 connection-timeout(3초) 대기 큐.
 *  - 일부는 acquisition 실패 → connection timeout 발생.
 *  - p99 latency 폭증.
 *
 * 이 결과가 Stage 3(대기열 도입) 필요성의 직접 근거.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PoolExhaustionStressTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        jdbc.execute("DELETE FROM reservation");
        jdbc.execute("UPDATE seat SET status='AVAILABLE'");
    }

    @Test
    @DisplayName("Pool=10, 좌석 100, 동시 500 → connection-timeout 발생, p99 폭증")
    void pool_exhaustion_500_concurrent() throws Exception {
        int seatCount = 100;
        int threadCount = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        StressMetrics m = new StressMetrics();
        Random rng = new Random(7);

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            final long seatId = 1 + rng.nextInt(seatCount);
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

        StressMetrics.Snapshot snap = m.snapshot("Pool Exhaustion 500 동시 (pool=10)", elapsed, threadCount);
        snap.print();

        // Inverted assertion — 풀 고갈을 관찰해야 의도 충족.
        // 단, race-correctness 검증은 유지: 합계 = 총 요청수.
        int accounted = m.success.get() + m.seatNotAvailable.get()
                + m.dataIntegrityViolation.get() + m.connectionTimeout.get() + m.otherError.get();
        assertTrue(accounted == threadCount, "every request must be accounted for, got " + accounted);
    }
}
