package com.dongwoo.altd;

import com.dongwoo.altd.domain.ReservationStatus;
import com.dongwoo.altd.lock.RedisDistributedLock;
import com.dongwoo.altd.repository.ReservationRepository;
import com.dongwoo.altd.service.ReservationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대안 D — Redis SETNX 분산 락.
 *
 * 가설:
 *  - 정확히 1건만 HELD (Redis 락이 critical section 차단)
 *  - 99건은 SETNX null 받고 즉시 reject (retry storm 없음)
 *  - finally의 unlock으로 Redis 키 깔끔히 정리됨
 *
 * 별도 검증 (zombie lock 시나리오):
 *  - 락을 잡고 일부러 unlock 하지 않으면 TTL 만료 전까지 다른 reservation이 모두 reject 된다.
 *  - 이는 fencing token 부재 시 Kleppmann이 지적한 위험의 일부분 (만료 후 GC pause 케이스는 별도).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AltDConcurrencyTest {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired RedisDistributedLock redisLock;
    @Autowired EntityManager em;

    @Test
    @DisplayName("좌석 1에 동시 100건 → Redis SETNX로 정확히 1건만 HELD")
    void redis_setnx_blocks_oversell() throws Exception {
        Long seatId = insertSeat();
        reservationService.resetCounters();

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        long t0 = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    reservationService.reserve(seatId, "user-" + idx);
                    success.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        long elapsedMs = System.currentTimeMillis() - t0;

        long heldCount = reservationRepository.findAll().stream()
                .filter(r -> r.getSeatId().equals(seatId))
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .count();

        int lockFails = reservationService.getLockAcquireFailCount().get();
        int statusRejects = reservationService.getStatusRejectCount().get();
        int unlockMisses = reservationService.getUnlockMissCount().get();

        // 모든 reservation 종료 후 Redis 키는 명시적 unlock으로 제거되어야 한다.
        String remaining = redisLock.peek(ReservationService.lockKey(seatId));

        System.out.println("===== ALT-D RESULT =====");
        System.out.println("success=" + success.get());
        System.out.println("rejected=" + rejected.get());
        System.out.println("heldCount=" + heldCount);
        System.out.println("lockAcquireFails=" + lockFails);
        System.out.println("statusRejects=" + statusRejects);
        System.out.println("unlockMisses=" + unlockMisses);
        System.out.println("elapsedMs=" + elapsedMs);
        System.out.println("redisKeyAfter=" + remaining);
        System.out.println("=========================");

        assertEquals(1, success.get(), "정확히 1건만 성공해야 함");
        assertEquals(99, rejected.get(), "나머지 99건은 reject");
        assertEquals(1L, heldCount, "DB에 HELD 1건만 존재");
        assertTrue(lockFails + statusRejects == 99,
                "reject 99건은 SETNX 실패 또는 락은 잡았으나 status가 HELD인 경우의 합 (=" + (lockFails + statusRejects) + ")");
        assertNull(remaining, "성공/실패 모두 finally에서 unlock 호출 → Redis 키는 남지 않아야 함");
    }

    @Test
    @DisplayName("zombie lock — 외부에서 락을 잡고 풀지 않으면 후속 reservation은 TTL 만료까지 차단")
    void zombie_lock_blocks_others_until_ttl() throws Exception {
        Long seatId = insertSeat();
        String key = ReservationService.lockKey(seatId);
        String zombieToken = UUID.randomUUID().toString();

        // 외부 클라이언트가 락만 잡아두고 unlock 안 한 상황을 시뮬레이션 — TTL 3초.
        assertTrue(redisLock.tryLock(key, zombieToken, 3), "초기 락 획득은 성공해야 한다");

        // TTL 만료 전 → 락 충돌 → reject
        long t0 = System.currentTimeMillis();
        assertThrowsSeatNotAvailable(() -> reservationService.reserve(seatId, "blocked-user"));
        long blockedElapsed = System.currentTimeMillis() - t0;

        // TTL 만료까지 기다림
        Thread.sleep(3_500);

        // 만료 후 → 새 요청은 정상 획득 가능
        long t1 = System.currentTimeMillis();
        reservationService.reserve(seatId, "after-ttl-user");
        long afterElapsed = System.currentTimeMillis() - t1;

        // zombie 소유자가 뒤늦게 unlock 시도해도 token이 달라 실패해야 한다
        boolean zombieUnlock = redisLock.unlock(key, zombieToken);

        System.out.println("===== ALT-D ZOMBIE LOCK =====");
        System.out.println("blockedDuringTTL_ms=" + blockedElapsed);
        System.out.println("acquiredAfterTTL_ms=" + afterElapsed);
        System.out.println("zombieUnlockSucceeded=" + zombieUnlock);
        System.out.println("=============================");

        assertFalse(zombieUnlock, "TTL 만료 후 새 소유자가 잡은 락은 zombie token으로 풀리면 안 된다");
    }

    @Transactional
    Long insertSeat() {
        return ((Number) em.createNativeQuery(
                "INSERT INTO seat (section_id, seat_no, status) " +
                "VALUES (1, " + System.nanoTime() % 1_000_000 + ", 'AVAILABLE') RETURNING id")
                .getSingleResult()).longValue();
    }

    private void assertThrowsSeatNotAvailable(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            return;
        }
        throw new AssertionError("expected SeatNotAvailableException");
    }
}
