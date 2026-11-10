package com.watchspaces.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Batches writes so the persistence layer is hit at most once per {@code maxAgeMs} or {@code maxItems}
 * (whichever comes first) instead of once per WebSocket message. {@link #add} flushes inline when a threshold
 * is crossed; a scheduler should also call {@link #flushIfDue} periodically, and readers call {@link #flush}
 * before reading so they never miss buffered rows.
 *
 * The sink is invoked while holding the buffer's lock, so batches reach the sink strictly in the order the items
 * were added (the event log relies on this for JOIN/LEAVE replay).
 */
public final class WriteBuffer<T> {

    private final int maxItems;
    private final long maxAgeMs;
    private final Consumer<List<T>> sink;
    private final LongSupplier clock;
    private final List<T> items = new ArrayList<>();
    private long oldestMs;

    public WriteBuffer(int maxItems, long maxAgeMs, Consumer<List<T>> sink, LongSupplier clock) {
        this.maxItems = maxItems;
        this.maxAgeMs = maxAgeMs;
        this.sink = sink;
        this.clock = clock;
    }

    public synchronized void add(T item) {
        if (items.isEmpty()) {
            oldestMs = clock.getAsLong();
        }
        items.add(item);
        if (items.size() >= maxItems) {
            drainToSink();
        }
    }

    public synchronized void flushIfDue() {
        if (!items.isEmpty() && clock.getAsLong() - oldestMs >= maxAgeMs) {
            drainToSink();
        }
    }

    public synchronized void flush() {
        if (!items.isEmpty()) {
            drainToSink();
        }
    }

    public synchronized int pending() {
        return items.size();
    }

    private void drainToSink() {
        List<T> copy = new ArrayList<>(items);
        items.clear();
        sink.accept(copy);
    }
}
