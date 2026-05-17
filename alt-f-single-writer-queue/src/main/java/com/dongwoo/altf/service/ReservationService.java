package com.dongwoo.altf.service;

import com.dongwoo.altf.queue.SingleWriterReservationQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 대안 F — 단일 작성자 큐.
 *
 * reserve() = queue.submit(...) + future.get(timeout)
 * 모든 동시 호출은 큐에 줄을 서서 worker 1개가 순차 처리.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private static final long SUBMIT_TIMEOUT_SECONDS = 30;

    private final SingleWriterReservationQueue queue;

    public Long reserve(Long seatId, String userId) {
        try {
            return queue.submit(seatId, userId).get(SUBMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for reservation", e);
        } catch (TimeoutException e) {
            throw new RuntimeException("reservation timed out after " + SUBMIT_TIMEOUT_SECONDS + "s", e);
        }
    }
}
