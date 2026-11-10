package com.watchspaces.core;

/** Turns "the room ended at position X" into the viewing record stored for the recommender. Pure and unit-tested. */
public final class InteractionRules {
    public static final int MIN_RECORDABLE_SECONDS = 30;
    public static final double COMPLETED_FRACTION = 0.9;

    public record Summary(int watchedSeconds, boolean completed, boolean recordable) { }

    private InteractionRules() { }

    public static Summary fromSession(double positionSeconds, int durationSeconds) {
        if (durationSeconds <= 0 || Double.isNaN(positionSeconds)) {
            return new Summary(0, false, false);
        }
        int watched = (int) Math.max(0, Math.min(positionSeconds, durationSeconds));
        boolean completed = positionSeconds >= COMPLETED_FRACTION * durationSeconds;
        return new Summary(watched, completed, watched >= MIN_RECORDABLE_SECONDS);
    }
}
