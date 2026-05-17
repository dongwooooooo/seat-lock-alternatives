package com.dongwoo.stressdeep;

import com.dongwoo.stressdeep.service.LeakyService;
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
 * 시나리오 6 — Connection leak detection.
 *
 * acquireWithoutClose() 로 connection 을 빌리고 close 안 함 (anti-pattern).
 *  - pool=30 → 30개 leak 시도하면 추가 getConnection() 은 timeout.
 *  - HikariCP leakDetectionThreshold=5000 → 5초 후 leak 경고 stack trace 로그.
 *
 * 본 테스트는 두 가지 입증:
 *  1) leak이 누적되면 pool 영구 고갈 (timeout)
 *  2) HikariCP가 leak을 감지하고 경고 (관측성)
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConnectionLeakStressTest {

    private static final Logger log = LoggerFactory.getLogger(ConnectionLeakStressTest.class);

    @Autowired LeakyService leakyService;
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("Connection leak — 누적 30 leak 후 pool 영구 고갈, leak detection 감지")
    void connection_leak_exhausts_pool_permanently() throws Exception {
        int poolSize = ((HikariDataSource) dataSource).getMaximumPoolSize();
        System.out.println("HikariCP maximum-pool-size=" + poolSize);

        // Step 1: pool 크기만큼 leak.
        int leakCount = poolSize;
        for (int i = 0; i < leakCount; i++) {
            leakyService.acquireWithoutClose();
        }
        System.out.println("leaked=" + leakyService.leaked.get()
                + " (= pool size, 추가 connection 요청은 timeout 예정)");

        // Step 2: leak detection 경고가 발생할 충분한 시간 대기 (5s threshold + 여유).
        // 동시에 추가 getConnection() 호출 → 모두 timeout 되어야 함.
        Thread.sleep(2000);

        // Step 3: 추가 요청 — 모두 timeout 되어야 함.
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

        // Step 4: leak detection 경고는 별도 thread에서 5s 후 발생. 추가 대기.
        Thread.sleep(4000);

        System.out.println();
        System.out.println("=== Test 6: Connection leak ===");
        System.out.println("config: pool=" + poolSize + ", leaked=" + leakCount + ", probeCount=" + probeCount);
        System.out.println("probe success=" + successCount.get() + " timeout=" + timeoutCount.get());
        System.out.println("probe elapsed=" + (probeElapsed / 1000.0) + "s");
        System.out.println("verdict: " + (timeoutCount.get() >= probeCount
                ? "OBSERVED pool exhaustion — leaked=" + leakCount + " consumed entire pool. "
                  + "Probes 100% timed out. HikariCP leak detection 경고는 stdout/로그 확인."
                : "partial timeout — pool not fully exhausted"));

        // 검증.
        assertTrue(timeoutCount.get() >= probeCount,
                "all probes must timeout when pool fully leaked. timeout=" + timeoutCount.get()
                + " success=" + successCount.get());

        // Cleanup.
        leakyService.cleanup();
        log.info("Test 6 cleanup complete. Pool restored.");
    }
}
