package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import com.watchspaces.core.PlaybackStateMachine.Command;
import com.watchspaces.core.PlaybackStateMachine.IllegalTransitionException;
import com.watchspaces.core.PlaybackStateMachine.State;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybackStateMachineTest {
    private AtomicLong now;
    private PlaybackStateMachine sm;

    @BeforeEach
    void setUp() {
        now = new AtomicLong(1_000);
        sm = new PlaybackStateMachine(100, now::get);
    }

    @Test
    void startsPausedAtZero() {
        assertEquals(State.PAUSED, sm.state());
        assertEquals(0.0, sm.positionSeconds());
    }

    @Test
    void positionAdvancesOnlyWhilePlaying() {
        sm.apply(Command.PLAY, 10.0);
        now.addAndGet(5_000);
        assertEquals(15.0, sm.positionSeconds(), 0.001);
        sm.apply(Command.PAUSE, null);
        now.addAndGet(5_000);
        assertEquals(15.0, sm.positionSeconds(), 0.001);
    }

    @Test
    void seekKeepsStateAndMovesPosition() {
        sm.apply(Command.PLAY, 0.0);
        sm.apply(Command.SEEK, 50.0);
        assertEquals(State.PLAYING, sm.state());
        now.addAndGet(2_000);
        assertEquals(52.0, sm.positionSeconds(), 0.001);
    }

    @Test
    void seekIsClampedToTitleDuration() {
        sm.apply(Command.SEEK, 9_999.0);
        assertEquals(100.0, sm.positionSeconds());
        sm.apply(Command.SEEK, -5.0);
        assertEquals(0.0, sm.positionSeconds());
    }

    @Test
    void bufferingFreezesPositionAndResumesPreviousState() {
        sm.apply(Command.PLAY, 10.0);
        now.addAndGet(5_000);
        sm.apply(Command.BUFFER_START, null);
        now.addAndGet(3_000);
        assertEquals(State.BUFFERING, sm.state());
        assertEquals(15.0, sm.positionSeconds(), 0.001);
        sm.apply(Command.BUFFER_END, null);
        assertEquals(State.PLAYING, sm.state());
    }

    @Test
    void illegalTransitionsAreRejectedAndDoNotChangeVersion() {
        sm.apply(Command.PLAY, 0.0);
        long v = sm.snapshot().version();
        assertThrows(IllegalTransitionException.class, () -> sm.apply(Command.PLAY, null));
        assertThrows(IllegalTransitionException.class, () -> sm.apply(Command.BUFFER_END, null));
        assertThrows(IllegalTransitionException.class, () -> sm.apply(Command.SEEK, null));
        assertEquals(v, sm.snapshot().version());
    }

    @Test
    void playbackSettlesToPausedAtTheEndOfTheTitle() {
        sm.apply(Command.PLAY, 90.0);
        now.addAndGet(60_000);
        assertEquals(State.PAUSED, sm.state());
        assertEquals(100.0, sm.positionSeconds());
    }

    @Test
    void versionIncreasesOnEveryAcceptedCommand() {
        long v0 = sm.snapshot().version();
        sm.apply(Command.PLAY, 0.0);
        sm.apply(Command.PAUSE, null);
        assertEquals(v0 + 2, sm.snapshot().version());
    }
}
