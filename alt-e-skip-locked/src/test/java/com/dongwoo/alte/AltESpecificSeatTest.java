package com.dongwoo.alte;

import com.dongwoo.alte.domain.ReservationStatus;
import com.dongwoo.alte.domain.SeatStatus;
import com.dongwoo.alte.repository.ReservationRepository;
import com.dongwoo.alte.repository.SeatRepository;
import com.dongwoo.alte.service.ReservationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시나리오 1 — 특정 좌석 요청 (티켓팅 도메인의 본 시나리오).
 *
 * 100 스레드가 동시에 seat=100을 요청한다.
 *
 * SKIP LOCKED 동작:
 *  - 1 스레드가 락 획득 → status=HELD로 UPDATE → commit
 *  - 나머지 99 스레드는 동시에 SELECT ... FOR UPDATE SKIP LOCKED 실행
 *    · 승자가 commit 전이면: 행이 락 잡혀 있음 → SKIP → empty
 *    · 승자가 commit 후이면: status=HELD → WHERE 절에서 제외 → empty
 *  - 둘 다 호출 측에서는 empty Optional로 동일하게 보임
 *
 * False negative 측정:
 *  - 99건 reject 중 "내가 reject된 시점에 좌석이 실제로는 AVAILABLE이었던" 케이스
 *  - 이걸 정확히 가르려면 commit 타이밍을 잡아야 함. 단순화하여:
 *    · 전체 99건을 "락 충돌로 인한 false negative"로 간주
 *    · 승자 commit 후에는 status=HELD이므로 어차피 reject가 맞음(true negative)
 *    · 그러나 실험상 100 스레드 동시 발사 → 압도적 다수가 락 보유 중 SKIP됨
 *  - 핵심: heldCount=1 / success=1 / rejected=99. 99건 모두 "사용 불가" 응답을 받지만,
 *    좌석은 결국 (winner의 hold일 뿐) "정상 처리 가능"한 상태였음.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AltESpecificSeatTest {

    @Autowired ReservationService reservationService;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired EntityManager em;

    @Test
    @DisplayName("시나리오 1: 좌석 100에 100 스레드 동시 요청 → 1건 HELD + 99건 false negative")
    void skip_locked_specific_seat_false_negatives() throws Exception {
        Long seatId = insertSeat();

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
                    reservationService.reserveSpecific(seatId, "user-" + idx);
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

        // 좌석은 결국 winner가 HELD로 보유 중. 이 상태는 "정상 처리 결과"임.
        // 99건의 reject는 좌석이 진짜 매진된 게 아니라 락 충돌로 인한 SKIP.
        long heldCount = reservationRepository.findAll().stream()
                .filter(r -> r.getSeatId().equals(seatId))
                .filter(r -> r.getStatus() == ReservationStatus.HELD)
                .count();

        SeatStatus finalSeatStatus = seatRepository.findById(seatId).orElseThrow().getStatus();

        // false negative = 99건 전체. 좌석은 (winner 입장에서) 정상 처리됐지만
        // 99 스레드는 모두 "사용 불가" 응답을 받음. 락 충돌과 매진을 구별 못한 결과.
        int falseNegatives = rejected.get();

        System.out.println("===== ALT-E SCENARIO 1 RESULT =====");
        System.out.println("success=" + success.get());
        System.out.println("rejected=" + rejected.get());
        System.out.println("heldCount=" + heldCount);
        System.out.println("finalSeatStatus=" + finalSeatStatus);
        System.out.println("falseNegatives=" + falseNegatives);
        System.out.println("elapsedMs=" + elapsedMs);
        System.out.println("===================================");

        assertEquals(1, success.get(), "정확히 1건만 성공");
        assertEquals(99, rejected.get(), "99건 reject");
        assertEquals(1L, heldCount, "DB에 HELD reservation 1건만 존재");
        assertEquals(SeatStatus.HELD, finalSeatStatus, "winner가 좌석 보유");
        assertEquals(99, falseNegatives,
                "99건은 모두 false negative — 좌석은 winner가 HELD로 정상 보유 중인데도 '사용 불가' 응답");
        assertTrue(elapsedMs < 30_000, "30초 내 완료");
    }

    @Transactional
    Long insertSeat() {
        return ((Number) em.createNativeQuery(
                "INSERT INTO seat (section_id, seat_no, status) " +
                "VALUES (999, 1, 'AVAILABLE') RETURNING id")
                .getSingleResult()).longValue();
    }
}
