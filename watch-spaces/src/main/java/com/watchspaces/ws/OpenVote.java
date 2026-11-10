package com.watchspaces.ws;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** An in-flight narrative-variation vote. optionKeys are ordered by position (index 0 = original). */
public final class OpenVote {
    public final String variationId;
    public final long eventId;
    public final int appliesAtTs;
    public final List<String> optionKeys;
    public final List<String> optionLabels;
    public final long closesAtMs;
    public final long openedAtMs = System.currentTimeMillis();
    public final Map<Long, String> votes = new ConcurrentHashMap<>();
    public final AtomicBoolean closed = new AtomicBoolean(false);
    public volatile ScheduledFuture<?> timer;

    public OpenVote(String variationId, long eventId, int appliesAtTs, List<String> optionKeys,
                    List<String> optionLabels, long closesAtMs) {
        this.variationId = variationId;
        this.eventId = eventId;
        this.appliesAtTs = appliesAtTs;
        this.optionKeys = optionKeys;
        this.optionLabels = optionLabels;
        this.closesAtMs = closesAtMs;
    }

    /** Winner = most votes; ties and "no votes" fall back to the earliest option (the original). */
    public String winner() {
        return com.watchspaces.core.VariationRules.winner(optionKeys, votes.values());
    }

    public Map<String, Integer> tally() {
        return com.watchspaces.core.VariationRules.tally(optionKeys, votes.values());
    }
}
