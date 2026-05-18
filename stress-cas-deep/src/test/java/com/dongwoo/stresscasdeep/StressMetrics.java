package com.dongwoo.stresscasdeep;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deep stress test 공통 지표 — 카운터 + 응답시간 분포 + 추가 실패 모드 카운터.
 */
public final class StressMetrics {

    public final AtomicInteger success = new AtomicInteger();
    public final AtomicInteger seatNotAvailable = new AtomicInteger();
    public final AtomicInteger dataIntegrityViolation = new AtomicInteger();
    public final AtomicInteger connectionTimeout = new AtomicInteger();
    public final AtomicInteger deadlock = new AtomicInteger();
    public final AtomicInteger lockTimeout = new AtomicInteger();
    public final AtomicInteger intentionalRollback = new AtomicInteger();
    public final AtomicInteger otherError = new AtomicInteger();

    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

    public void record(long latencyMs) {
        latencies.add(latencyMs);
    }

    public Snapshot snapshot(String label, long elapsedMs, int total) {
        java.util.ArrayList<Long> sorted = new java.util.ArrayList<>(latencies);
        Collections.sort(sorted);
        return new Snapshot(
                label,
                total,
                success.get(),
                seatNotAvailable.get(),
                dataIntegrityViolation.get(),
                connectionTimeout.get(),
                deadlock.get(),
                lockTimeout.get(),
                intentionalRollback.get(),
                otherError.get(),
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                sorted.isEmpty() ? 0L : sorted.get(sorted.size() - 1),
                elapsedMs);
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(sorted.size() * (p / 100.0)) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }

    public record Snapshot(
            String label,
            int total,
            int success,
            int seatNotAvailable,
            int dataIntegrityViolation,
            int connectionTimeout,
            int deadlock,
            int lockTimeout,
            int intentionalRollback,
            int otherError,
            long p50,
            long p95,
            long p99,
            long max,
            long elapsedMs) {

        public double throughputPerSec() {
            if (elapsedMs == 0) return 0;
            return total * 1000.0 / elapsedMs;
        }

        public void print() {
            System.out.println();
            System.out.println("=== " + label + " ===");
            System.out.println("total=" + total
                    + " success=" + success
                    + " seatNotAvailable=" + seatNotAvailable
                    + " dataIntegrityViolation=" + dataIntegrityViolation
                    + " connectionTimeout=" + connectionTimeout
                    + " deadlock=" + deadlock
                    + " lockTimeout=" + lockTimeout
                    + " intentionalRollback=" + intentionalRollback
                    + " otherError=" + otherError);
            System.out.println("latency p50=" + p50 + "ms p95=" + p95
                    + "ms p99=" + p99 + "ms max=" + max + "ms");
            System.out.println("elapsed=" + (elapsedMs / 1000.0) + "s throughput="
                    + String.format("%.1f", throughputPerSec()) + " ops/sec");
            System.out.println();
        }
    }

    public void classifyException(Throwable e) {
        String msg = String.valueOf(e.getMessage());
        Throwable cause = e;
        StringBuilder fullChain = new StringBuilder();
        int depth = 0;
        while (cause != null && depth < 10) {
            fullChain.append(cause.getClass().getSimpleName()).append(":").append(cause.getMessage()).append(" / ");
            cause = cause.getCause();
            depth++;
        }
        String chain = fullChain.toString();

        if (chain.contains("deadlock") || chain.contains("DeadlockLoserDataAccess") || chain.contains("40P01")) {
            deadlock.incrementAndGet();
        } else if (chain.contains("LockAcquisitionException") || chain.contains("PessimisticLockException")
                || chain.contains("CannotAcquireLockException") || chain.contains("lock timeout")
                || chain.contains("55P03") || chain.contains("LockTimeoutException")) {
            lockTimeout.incrementAndGet();
        } else if (chain.contains("Connection is not available") || chain.contains("HikariPool")
                || chain.contains("DataAccessResourceFailure")) {
            connectionTimeout.incrementAndGet();
        } else if (chain.contains("DataIntegrityViolation") || chain.contains("23505")) {
            dataIntegrityViolation.incrementAndGet();
        } else if (chain.contains("SeatNotAvailable")) {
            seatNotAvailable.incrementAndGet();
        } else if (chain.contains("IntentionalFailure")) {
            intentionalRollback.incrementAndGet();
        } else {
            otherError.incrementAndGet();
        }
    }
}
