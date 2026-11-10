package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WriteBufferTest {
    private final AtomicLong now = new AtomicLong(0);
    private final List<List<Integer>> batches = new ArrayList<>();
    private final WriteBuffer<Integer> buf = new WriteBuffer<>(3, 2000, batches::add, now::get);

    @Test
    void doesNotWriteBelowThresholds() {
        buf.add(1);
        buf.add(2);
        assertEquals(0, batches.size());
        assertEquals(2, buf.pending());
    }

    @Test
    void flushesWhenItemLimitReachedInOrder() {
        buf.add(1);
        buf.add(2);
        buf.add(3);
        assertEquals(List.of(List.of(1, 2, 3)), batches);
        assertEquals(0, buf.pending());
    }

    @Test
    void flushesOnAgeWhenDue() {
        buf.add(1);
        now.set(1999);
        buf.flushIfDue();
        assertEquals(0, batches.size());
        now.set(2000);
        buf.flushIfDue();
        assertEquals(List.of(List.of(1)), batches);
    }

    @Test
    void ageIsMeasuredFromTheOldestPendingItem() {
        buf.add(1);
        now.set(1500);
        buf.add(2);
        now.set(2000);
        buf.flushIfDue();
        assertEquals(List.of(List.of(1, 2)), batches);
    }

    @Test
    void manualFlushWritesEverythingAndEmptyFlushIsNoop() {
        buf.flush();
        assertEquals(0, batches.size());
        buf.add(1);
        buf.flush();
        assertEquals(List.of(List.of(1)), batches);
    }

    @Test
    void manyAddsProduceFewBatches() {
        for (int i = 0; i < 100; i++) {
            buf.add(i);
        }
        buf.flush();
        assertEquals(34, batches.size()); // 33 full batches of 3 + the remainder
        List<Integer> flat = new ArrayList<>();
        batches.forEach(flat::addAll);
        for (int i = 0; i < 100; i++) {
            assertEquals(i, flat.get(i).intValue());
        }
    }
}
