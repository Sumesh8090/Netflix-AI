package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class InteractionRulesTest {
    @Test
    void shortSessionsAreNotRecorded() {
        assertFalse(InteractionRules.fromSession(10, 2400).recordable());
        assertTrue(InteractionRules.fromSession(30, 2400).recordable());
    }

    @Test
    void completionThresholdIsNinetyPercent() {
        assertFalse(InteractionRules.fromSession(2159, 2400).completed());
        assertTrue(InteractionRules.fromSession(2160, 2400).completed());
    }

    @Test
    void watchedIsClampedToTheTitle() {
        assertEquals(2400, InteractionRules.fromSession(9999, 2400).watchedSeconds());
        assertEquals(0, InteractionRules.fromSession(-5, 2400).watchedSeconds());
        assertFalse(InteractionRules.fromSession(100, 0).recordable());
    }
}
