package com.watchspaces.core;

import java.util.Arrays;

/** Thread-safe rolling sample of measurements (ms) with percentile support. Keeps the last N samples. */
public final class LatencyStats {

    public record Summary(long count, double min, double p50, double p95, double max) { }

    private final double[] ring;
    private int next = 0;
    private long total = 0;

    public LatencyStats(int capacity) {
        this.ring = new double[capacity];
    }

    public synchronized void record(double ms) {
        ring[next] = ms;
        next = (next + 1) % ring.length;
        total++;
    }

    public synchronized Summary summary() {
        int n = (int) Math.min(total, ring.length);
        if (n == 0) {
            return new Summary(0, 0, 0, 0, 0);
        }
        double[] copy = Arrays.copyOf(ring, n);
        Arrays.sort(copy);
        return new Summary(total, copy[0], percentile(copy, 50), percentile(copy, 95), copy[n - 1]);
    }

    /** Nearest-rank percentile over a sorted array. */
    static double percentile(double[] sorted, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }
}
