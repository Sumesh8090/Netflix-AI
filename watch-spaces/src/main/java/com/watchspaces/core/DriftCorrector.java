package com.watchspaces.core;

/**
 * Computes the correction a participant client should apply, given the position it reported and the
 * authoritative room position. Pure function.
 *
 * drift = clientPosition - authoritativePosition (positive => client is ahead).
 * <ul>
 *   <li>|drift| &lt;= {@value #TOLERANCE_MS} ms : NONE</li>
 *   <li>|drift| &lt;= {@value #MICRO_LIMIT_MS} ms : MICRO_SEEK (small nudge to the target, not a jarring jump)</li>
 *   <li>larger : HARD_SEEK</li>
 * </ul>
 */
public final class DriftCorrector {

    public static final long TOLERANCE_MS = 100;
    public static final long MICRO_LIMIT_MS = 1000;

    public enum Action { NONE, MICRO_SEEK, HARD_SEEK }

    public record Correction(Action action, double targetPositionSeconds, long driftMs) { }

    private DriftCorrector() { }

    /**
     * @param clientPositionSeconds position the client captured when it sent the ping
     * @param authoritativeNowSeconds room position at the moment the server received the ping
     * @param playing whether the room is currently PLAYING (if so, the authoritative position moved
     *                during the one-way trip, so the client's sample is compared to a back-dated position)
     * @param oneWayLatencyMs estimated one-way latency (RTT/2); 0 if unknown
     */
    public static Correction evaluate(double clientPositionSeconds, double authoritativeNowSeconds,
                                      boolean playing, long oneWayLatencyMs) {
        double authAtSend = playing ? authoritativeNowSeconds - oneWayLatencyMs / 1000.0 : authoritativeNowSeconds;
        long driftMs = Math.round((clientPositionSeconds - authAtSend) * 1000.0);
        long abs = Math.abs(driftMs);
        Action action;
        if (abs <= TOLERANCE_MS) {
            action = Action.NONE;
        } else if (abs <= MICRO_LIMIT_MS) {
            action = Action.MICRO_SEEK;
        } else {
            action = Action.HARD_SEEK;
        }
        // Target is where the client should be *now*: authoritative now (client applies it on receipt).
        return new Correction(action, authoritativeNowSeconds, driftMs);
    }
}
