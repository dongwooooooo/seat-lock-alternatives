package com.dongwoo.stresscasdeep;

import com.dongwoo.stresscasdeep.service.LeakyService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시나리오 6 (CAS) — Connection leak detection.
 *
 * CAS 와 무관한 코드 규율 항목. 비관적 락 시나리오와 동일 측정 — leak 30회 누적 후
 * 풀 회전 영구 중단 확인.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConnectionLeakStressTest {

    private static final Logger log = LoggerFactory.getLogger(ConnectionLeakStressTest.class);

    @Autowired LeakyService leakyService;
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("Connection leak (CAS) — pool 영구 고갈, leak detection 감지")
    void connection_leak_exhausts_pool_permanently() throws Exception {
        int poolSize = ((HikariDataSource) dataSource).getMaximumPoolSize();
        System.out.println("HikariCP maximum-pool-size=" + poolSize);

        int leakCount = poolSize;
        for (int i = 0; i < leakCount; i++) {
            leakyService.acquireWithoutClose();
        }
        System.out.println("leaked=" + leakyService.leaked.get()
                + " (= pool size)");

        Thread.sleep(2000);

        int probeCount = 5;
        AtomicInteger timeoutCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();
        ExecutorService probeExec = Executors.newFixedThreadPool(probeCount);
        CountDownLatch probeDone = new CountDownLatch(probeCount);
        long probeT0 = System.currentTimeMillis();
        for (int i = 0; i < probeCount; i++) {
            probeExec.submit(() -> {
                try (Connection c = dataSource.getConnection()) {
                    successCount.incrementAndGet();
                } catch (SQLTransientConnectionException e) {
                    timeoutCount.incrementAndGet();
                } catch (Exception e) {
                    if (String.valueOf(e.getMessage()).contains("Connection is not available")) {
                        timeoutCount.incrementAndGet();
                    }
                } finally {
                    probeDone.countDown();
                }
            });
        }
        probeDone.await(30, TimeUnit.SECONDS);
        probeExec.shutdownNow();
        long probeElapsed = System.currentTimeMillis() - probeT0;

        Thread.sleep(4000);

        System.out.println();
        System.out.println("=== Test 6 (CAS): Connection leak ===");
        System.out.println("config: pool=" + poolSize + ", leaked=" + leakCount + ", probeCount=" + probeCount);
        System.out.println("probe success=" + successCount.get() + " timeout=" + timeoutCount.get());
        System.out.println("probe elapsed=" + (probeElapsed / 1000.0) + "s");
        System.out.println("verdict: " + (timeoutCount.get() >= probeCount
                ? "OBSERVED pool exhaustion — leaked=" + leakCount + ", probes 100% timeout"
                : "partial timeout — pool not fully exhausted"));

        assertTrue(timeoutCount.get() >= probeCount,
                "all probes must timeout when pool fully leaked. timeout=" + timeoutCount.get()
                + " success=" + successCount.get());

        leakyService.cleanup();
        log.info("Test 6 cleanup complete. Pool restored.");
    }
}
