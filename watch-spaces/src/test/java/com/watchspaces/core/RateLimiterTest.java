package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RateLimiterTest {
    @Test
    void allowsUpToMaxThenBlocksUntilWindowPasses() {
        RateLimiter r = new RateLimiter(3, 1000);
        assertTrue(r.tryAcquire(1, 0));
        assertTrue(r.tryAcquire(1, 100));
        assertTrue(r.tryAcquire(1, 200));
        assertFalse(r.tryAcquire(1, 300));
        assertFalse(r.tryAcquire(1, 999));
        assertTrue(r.tryAcquire(1, 1000)); // the first event has aged out exactly at the window edge
    }

    @Test
    void keysAreIndependentAndRejectedAttemptsAreNotCounted() {
        RateLimiter r = new RateLimiter(1, 1000);
        assertTrue(r.tryAcquire(1, 0));
        assertFalse(r.tryAcquire(1, 500));
        assertTrue(r.tryAcquire(2, 500));
        assertTrue(r.tryAcquire(1, 1000)); // the rejected attempt at 500 did not extend the block
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 10));
    }
}
