package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LatencyStatsTest {
    @Test
    void emptySummaryIsZero() {
        assertEquals(0, new LatencyStats(10).summary().count());
    }

    @Test
    void nearestRankPercentiles() {
        LatencyStats s = new LatencyStats(1000);
        for (int i = 1; i <= 100; i++) {
            s.record(i);
        }
        LatencyStats.Summary sum = s.summary();
        assertEquals(100, sum.count());
        assertEquals(1.0, sum.min(), 1e-9);
        assertEquals(50.0, sum.p50(), 1e-9);
        assertEquals(95.0, sum.p95(), 1e-9);
        assertEquals(100.0, sum.max(), 1e-9);
    }

    @Test
    void ringKeepsOnlyTheLatestSamplesButCountsAll() {
        LatencyStats s = new LatencyStats(5);
        for (int i = 1; i <= 10; i++) {
            s.record(i);
        }
        LatencyStats.Summary sum = s.summary();
        assertEquals(10, sum.count());
        assertEquals(6.0, sum.min(), 1e-9);
        assertEquals(10.0, sum.max(), 1e-9);
    }
}
