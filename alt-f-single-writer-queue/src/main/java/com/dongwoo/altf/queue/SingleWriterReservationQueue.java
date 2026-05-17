package com.dongwoo.altf.queue;

import com.dongwoo.altf.domain.Reservation;
import com.dongwoo.altf.domain.Seat;
import com.dongwoo.altf.domain.SeatStatus;
import com.dongwoo.altf.repository.ReservationRepository;
import com.dongwoo.altf.repository.SeatRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 대안 F — in-process 단일 작성자 큐.
 *
 * 모든 reserve 호출 스레드는 큐에 명령을 넣고 CompletableFuture를 await.
 * worker 스레드 1개가 큐를 take()로 비우며 트랜잭션을 직렬로 실행.
 * DB 입장에선 동시 INSERT/UPDATE가 한 건도 들어오지 않으므로 락이 필요 없다.
 *
 * 한계:
 *  - 처리량이 1 / processing_time 으로 hard cap (단일 worker)
 *  - JVM 재시작 시 큐 안의 요청은 유실 (in-memory)
 *  - 멀티 인스턴스로 확장 불가 (단일 작성자가 깨짐)
 */
@Component
@Slf4j
public class SingleWriterReservationQueue {

    public record ReservationCommand(Long seatId, String userId, CompletableFuture<Long> future) {}

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final TransactionTemplate txTemplate;

    private final LinkedBlockingQueue<ReservationCommand> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger maxObservedDepth = new AtomicInteger();
    private final AtomicInteger processedCount = new AtomicInteger();

    private Thread worker;
    private volatile boolean running;

    public SingleWriterReservationQueue(SeatRepository seatRepository,
                                        ReservationRepository reservationRepository,
                                        PlatformTransactionManager txManager) {
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @PostConstruct
    public void start() {
        running = true;
        worker = new Thread(this::runLoop, "alt-f-single-writer");
        worker.setDaemon(true);
        worker.start();
        log.info("single-writer worker started");
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("single-writer worker stopped");
    }

    /** Public entry — 호출 스레드는 future.get()에서 대기. */
    public CompletableFuture<Long> submit(Long seatId, String userId) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        queue.offer(new ReservationCommand(seatId, userId, future));
        int depth = queue.size();
        maxObservedDepth.updateAndGet(prev -> Math.max(prev, depth));
        return future;
    }

    public int getMaxObservedDepth() {
        return maxObservedDepth.get();
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public void resetMetrics() {
        maxObservedDepth.set(0);
        processedCount.set(0);
    }

    private void runLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                ReservationCommand cmd = queue.take();
                try {
                    Long reservationId = txTemplate.execute(status -> processInDB(cmd));
                    cmd.future().complete(reservationId);
                } catch (Exception e) {
                    cmd.future().completeExceptionally(e);
                } finally {
                    processedCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Long processInDB(ReservationCommand cmd) {
        Seat seat = seatRepository.findById(cmd.seatId())
                .orElseThrow(() -> new IllegalArgumentException("seat not found: " + cmd.seatId()));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new SeatNotAvailableException(
                    "seat " + cmd.seatId() + " status=" + seat.getStatus());
        }

        seat.hold();
        // 별도 save 불필요 — managed entity의 dirty checking으로 UPDATE.
        Reservation reservation = Reservation.create(cmd.seatId(), cmd.userId(), HOLD_DURATION);
        return reservationRepository.save(reservation).getId();
    }

    public static class SeatNotAvailableException extends RuntimeException {
        public SeatNotAvailableException(String message) {
            super(message);
        }
    }
}
