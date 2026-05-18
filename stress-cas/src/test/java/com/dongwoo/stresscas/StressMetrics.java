package com.dongwoo.stresscas;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stress test 공통 지표 — 카운터 + 응답시간 분포.
 */
final class StressMetrics {

    final AtomicInteger success = new AtomicInteger();
    final AtomicInteger seatNotAvailable = new AtomicInteger();
    final AtomicInteger dataIntegrityViolation = new AtomicInteger();
    final AtomicInteger connectionTimeout = new AtomicInteger();
    final AtomicInteger otherError = new AtomicInteger();

    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

    void record(long latencyMs) {
        latencies.add(latencyMs);
    }

    Snapshot snapshot(String label, long elapsedMs, int total) {
        java.util.ArrayList<Long> sorted = new java.util.ArrayList<>(latencies);
        Collections.sort(sorted);
        return new Snapshot(
                label,
                total,
                success.get(),
                seatNotAvailable.get(),
                dataIntegrityViolation.get(),
                connectionTimeout.get(),
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

    record Snapshot(
            String label,
            int total,
            int success,
            int seatNotAvailable,
            int dataIntegrityViolation,
            int connectionTimeout,
            int otherError,
            long p50,
            long p95,
            long p99,
            long max,
            long elapsedMs) {

        double throughputPerSec() {
            if (elapsedMs == 0) return 0;
            return total * 1000.0 / elapsedMs;
        }

        void print() {
            System.out.println();
            System.out.println("=== " + label + " ===");
            System.out.println("total=" + total
                    + " success=" + success
                    + " seatNotAvailable=" + seatNotAvailable
                    + " dataIntegrityViolation=" + dataIntegrityViolation
                    + " connectionTimeout=" + connectionTimeout
                    + " otherError=" + otherError);
            System.out.println("latency p50=" + p50 + "ms p95=" + p95
                    + "ms p99=" + p99 + "ms max=" + max + "ms");
            System.out.println("elapsed=" + (elapsedMs / 1000.0) + "s throughput="
                    + String.format("%.1f", throughputPerSec()) + " ops/sec");
            System.out.println();
        }
    }
}
