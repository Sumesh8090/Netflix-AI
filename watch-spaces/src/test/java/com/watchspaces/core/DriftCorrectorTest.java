package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import com.watchspaces.core.DriftCorrector.Action;
import org.junit.jupiter.api.Test;

class DriftCorrectorTest {
    @Test
    void withinToleranceNeedsNoCorrection() {
        var c = DriftCorrector.evaluate(10.05, 10.0, true, 0);
        assertEquals(Action.NONE, c.action());
        assertEquals(50, c.driftMs());
    }

    @Test
    void moderateDriftGetsAMicroSeek() {
        assertEquals(Action.MICRO_SEEK, DriftCorrector.evaluate(10.5, 10.0, true, 0).action());
        assertEquals(Action.MICRO_SEEK, DriftCorrector.evaluate(9.5, 10.0, true, 0).action());
    }

    @Test
    void largeDriftGetsAHardSeek() {
        var c = DriftCorrector.evaluate(15.0, 10.0, false, 0);
        assertEquals(Action.HARD_SEEK, c.action());
        assertEquals(10.0, c.targetPositionSeconds());
    }

    @Test
    void latencyIsCompensatedOnlyWhilePlaying() {
        // client sampled 10.0 about 200 ms ago; room is now at 10.2 and playing => no drift
        assertEquals(Action.NONE, DriftCorrector.evaluate(10.0, 10.2, true, 200).action());
        // when paused the room did not move, so the same sample really is 200 ms behind
        assertEquals(Action.MICRO_SEEK, DriftCorrector.evaluate(10.0, 10.2, false, 200).action());
    }
}
