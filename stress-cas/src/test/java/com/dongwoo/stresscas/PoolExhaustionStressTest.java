package com.dongwoo.stresscas;

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
 * Pool exhaustion 시나리오 (CAS) — 좌석 100, 동시 500, pool=10.
 *
 * 사용자 행동: 콘서트 오픈 1초간 500명이 100개 좌석 중 임의로 클릭.
 * 서버 부위: HikariCP getConnection() → CAS UPDATE → INSERT → COMMIT.
 * 무엇 때문에: 트랜잭션 보유 시간이 짧으므로 (단순 UPDATE+INSERT) 풀 점유가
 *   빠르게 회전. 비관적 락 보다 풀 회전이 빨라 timeout 발생 임계 부하가 더 큼.
 * 사용자가 보는 결과: 좌석당 1명 성공, 나머지는 "이미 선점됨". 정상 응답.
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
    @DisplayName("Pool=10, 좌석 100, 동시 500 (CAS) — 풀 회전 측정")
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

        StressMetrics.Snapshot snap = m.snapshot("Pool Exhaustion 500 동시 (CAS, pool=10)", elapsed, threadCount);
        snap.print();

        int accounted = m.success.get() + m.seatNotAvailable.get()
                + m.dataIntegrityViolation.get() + m.connectionTimeout.get() + m.otherError.get();
        assertTrue(accounted == threadCount, "every request must be accounted for, got " + accounted);
    }
}
