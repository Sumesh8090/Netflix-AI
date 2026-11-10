package com.watchspaces.core;

import java.util.function.LongSupplier;

/**
 * Authoritative per-room playback state machine. Pure Java (no Spring) so it can be unit tested
 * deterministically with an injected clock.
 *
 * States: PLAYING, PAUSED, BUFFERING. "Seeking" is modelled as a transition action (SEEK) that
 * moves the position while keeping the current state, rather than as a resting state; the wire
 * event reports it as state "seek".
 *
 * Transition table (anything else throws {@link IllegalTransitionException}):
 * <pre>
 *   PAUSED     --PLAY-->         PLAYING
 *   BUFFERING  --PLAY-->         PLAYING
 *   PLAYING    --PAUSE-->        PAUSED
 *   BUFFERING  --PAUSE-->        PAUSED
 *   PLAYING    --BUFFER_START--> BUFFERING (resume state = PLAYING)
 *   PAUSED     --BUFFER_START--> BUFFERING (resume state = PAUSED)
 *   BUFFERING  --BUFFER_END-->   resume state
 *   any        --SEEK-->         same state, new position
 * </pre>
 * Position is derived from (basePosition + elapsed) while PLAYING and frozen otherwise, so no
 * heartbeat is ever written anywhere. When playback reaches the end of the title it settles to PAUSED.
 */
public final class PlaybackStateMachine {

    public enum State { PLAYING, PAUSED, BUFFERING }

    public enum Command { PLAY, PAUSE, SEEK, BUFFER_START, BUFFER_END }

    public record Snapshot(State state, double positionSeconds, long serverTimeMs, long version, String action) { }

    public static class IllegalTransitionException extends RuntimeException {
        public IllegalTransitionException(String message) {
            super(message);
        }
    }

    private final double durationSeconds;
    private final LongSupplier clock;

    private State state = State.PAUSED;
    private State resumeState = State.PAUSED;
    private double basePosition = 0;
    private long baseTimeMs;
    private long version = 0;
    private String lastAction = "init";

    public PlaybackStateMachine(double durationSeconds, LongSupplier clock) {
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be > 0");
        }
        this.durationSeconds = durationSeconds;
        this.clock = clock;
        this.baseTimeMs = clock.getAsLong();
    }

    /**
     * Applies a command. {@code position} is required for SEEK and optional for PLAY/PAUSE
     * (the host's reported position at the moment of the action, used to re-anchor the room clock).
     */
    public synchronized Snapshot apply(Command command, Double position) {
        long now = clock.getAsLong();
        settle(now);
        switch (command) {
            case PLAY -> {
                if (state == State.PLAYING) {
                    throw new IllegalTransitionException("Already PLAYING");
                }
                anchor(position, now);
                state = State.PLAYING;
                resumeState = State.PLAYING;
            }
            case PAUSE -> {
                if (state == State.PAUSED) {
                    throw new IllegalTransitionException("Already PAUSED");
                }
                anchor(position, now); // freeze at the reported position, or at the room's current one
                state = State.PAUSED;
                resumeState = State.PAUSED;
            }
            case SEEK -> {
                if (position == null) {
                    throw new IllegalTransitionException("SEEK requires a position");
                }
                basePosition = clamp(position);
                baseTimeMs = now;
            }
            case BUFFER_START -> {
                if (state == State.BUFFERING) {
                    throw new IllegalTransitionException("Already BUFFERING");
                }
                resumeState = state;
                basePosition = positionAtInternal(now);
                baseTimeMs = now;
                state = State.BUFFERING;
            }
            case BUFFER_END -> {
                if (state != State.BUFFERING) {
                    throw new IllegalTransitionException("BUFFER_END requires BUFFERING");
                }
                anchor(position, now);
                state = resumeState;
            }
        }
        version++;
        lastAction = command.name();
        return new Snapshot(state, positionAtInternal(now), now, version, lastAction);
    }

    public synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        settle(now);
        return new Snapshot(state, positionAtInternal(now), now, version, lastAction);
    }

    public synchronized double positionSeconds() {
        long now = clock.getAsLong();
        settle(now);
        return positionAtInternal(now);
    }

    public synchronized State state() {
        settle(clock.getAsLong());
        return state;
    }

    public double durationSeconds() {
        return durationSeconds;
    }

    // --- internals -------------------------------------------------------------------------

    private void anchor(Double position, long now) {
        if (position != null) {
            basePosition = clamp(position);
        } else {
            basePosition = positionAtInternal(now);
        }
        baseTimeMs = now;
    }

    private double positionAtInternal(long now) {
        if (state == State.PLAYING) {
            double p = basePosition + (now - baseTimeMs) / 1000.0;
            return Math.min(p, durationSeconds);
        }
        return basePosition;
    }

    /** If playback ran past the end, freeze at the end in PAUSED. */
    private void settle(long now) {
        if (state == State.PLAYING && basePosition + (now - baseTimeMs) / 1000.0 >= durationSeconds) {
            basePosition = durationSeconds;
            baseTimeMs = now;
            state = State.PAUSED;
            resumeState = State.PAUSED;
            version++;
            lastAction = "END";
        }
    }

    private double clamp(double p) {
        return Math.max(0, Math.min(p, durationSeconds));
    }
}
