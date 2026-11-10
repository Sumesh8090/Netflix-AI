package com.watchspaces.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Sliding-window limiter: at most {@code max} events per {@code windowMs} for each key. Thread-safe. */
public final class RateLimiter {
    private final int max;
    private final long windowMs;
    private final Map<Long, Deque<Long>> events = new HashMap<>();

    public RateLimiter(int max, long windowMs) {
        if (max < 1 || windowMs < 1) {
            throw new IllegalArgumentException("max and windowMs must be positive");
        }
        this.max = max;
        this.windowMs = windowMs;
    }

    /** Returns true and records the event if allowed; false (recording nothing) if the key is over its limit. */
    public synchronized boolean tryAcquire(long key, long nowMs) {
        Deque<Long> q = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!q.isEmpty() && nowMs - q.peekFirst() >= windowMs) {
            q.pollFirst();
        }
        if (q.size() >= max) {
            return false;
        }
        q.addLast(nowMs);
        return true;
    }

    public synchronized void forget(long key) {
        events.remove(key);
    }
}
