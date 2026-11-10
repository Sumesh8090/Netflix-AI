package com.watchspaces.ws;

import com.watchspaces.core.VariationRules;
import com.watchspaces.entity.EventLog;
import com.watchspaces.entity.TimelineEvent;
import com.watchspaces.entity.VariationOption;
import com.watchspaces.service.PersistenceBuffers;
import com.watchspaces.service.TimelineService;
import com.watchspaces.service.VariationStore;
import com.watchspaces.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Pre-authored variation points (no generation). Two flavours:
 *  - SUBTITLE: each viewer gets the option matching their locale (no vote, never touches playback, so it cannot desync a room);
 *  - any point can be put to a timed group vote by the host; the winner is broadcast ONCE to everybody (same payload).
 * Rules (locale match, winner, tie-break) live in {@link VariationRules}; this class only does state and I/O.
 */
@Component
public class VariationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(VariationCoordinator.class);
    public static final int DEFAULT_VOTE_SECONDS = 20;
    public static final int MAX_VOTE_SECONDS = 120;

    public record Choice(String variationId, int ts, String kind, String optionId, String label, String locale,
                         boolean fromVote) { }

    private final TimelineService timeline;
    private final RoomRegistry registry;
    private final PersistenceBuffers buffers;
    private final VariationStore store;
    private final TaskScheduler scheduler;

    public VariationCoordinator(TimelineService timeline, RoomRegistry registry, PersistenceBuffers buffers,
                                VariationStore store, TaskScheduler scheduler) {
        this.timeline = timeline;
        this.registry = registry;
        this.buffers = buffers;
        this.store = store;
        this.scheduler = scheduler;
    }

    // ---- locale-based subtitle swap -------------------------------------------------------------------

    /** What this viewer should see at a point: a decided vote wins for everybody, else subtitle points follow the locale. */
    public Choice choiceFor(RoomRuntime rt, TimelineEvent e, String locale) {
        String applied = rt.appliedVariations.get(e.variationId);
        if (applied != null) {
            VariationOption o = byKey(e, applied);
            return choice(e, o, true);
        }
        if (e.variationKind == TimelineEvent.VariationKind.SUBTITLE) {
            List<String> locales = new ArrayList<>();
            for (VariationOption o : e.options) {
                locales.add(o.locale);
            }
            return choice(e, e.options.get(VariationRules.pickForLocale(locales, locale)), false);
        }
        return null; // narrative point nobody has voted on: everybody keeps the original
    }

    public List<Choice> choicesFor(RoomRuntime rt, String locale) {
        List<Choice> out = new ArrayList<>();
        for (TimelineEvent e : timeline.variationPoints(rt.titleId)) {
            Choice c = choiceFor(rt, e, locale);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /** Wire form for the join snapshot: personal subtitle choices, decided votes and any vote that is open right now. */
    public Map<String, Object> snapshotFor(RoomRuntime rt, String locale) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("locale", locale);
        m.put("choices", choicesFor(rt, locale).stream().map(VariationCoordinator::wire).toList());
        m.put("applied", new LinkedHashMap<>(rt.appliedVariations));
        List<Map<String, Object>> open = new ArrayList<>();
        for (OpenVote ov : rt.openVotes.values()) {
            if (!ov.closed.get()) {
                open.add(voteOpenPayload(rt, ov));
            }
        }
        m.put("openVotes", open);
        return m;
    }

    public static Map<String, Object> wire(Choice c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variationId", c.variationId());
        m.put("ts", c.ts());
        m.put("kind", c.kind());
        m.put("optionId", c.optionId());
        m.put("label", c.label());
        m.put("locale", c.locale());
        m.put("fromVote", c.fromVote());
        return m;
    }

    // ---- group voting ---------------------------------------------------------------------------------

    public void open(RoomRuntime rt, long actorId, String variationId, Integer seconds) {
        if (actorId != rt.model.hostUserId()) {
            throw ApiException.forbidden("Only the host can open a vote");
        }
        if (!rt.votingEnabled) {
            throw ApiException.conflict("VOTING_DISABLED", "Voting is not enabled for this Watch Space");
        }
        if (variationId == null || variationId.isBlank()) {
            throw ApiException.badRequest("variationId is required");
        }
        TimelineEvent ev = timeline.variationPoint(rt.titleId, variationId);
        int secs = seconds == null ? DEFAULT_VOTE_SECONDS : seconds;
        if (secs < 5 || secs > MAX_VOTE_SECONDS) {
            throw ApiException.badRequest("durationSeconds must be between 5 and " + MAX_VOTE_SECONDS);
        }
        List<String> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (VariationOption o : ev.options) {
            keys.add(o.optionKey);
            labels.add(o.label);
        }
        OpenVote ov;
        synchronized (rt.openVotes) {
            if (rt.openVotes.containsKey(variationId) || rt.appliedVariations.containsKey(variationId)) {
                throw ApiException.conflict("VOTE_ALREADY_HELD", "This variation point already had a vote in this Watch Space");
            }
            long closesAt = System.currentTimeMillis() + secs * 1000L;
            ov = new OpenVote(variationId, ev.id, ev.tsSeconds, keys, labels, closesAt);
            rt.openVotes.put(variationId, ov);
            ov.timer = scheduler.schedule(() -> close(rt, variationId), Instant.ofEpochMilli(closesAt));
        }
        Map<String, Object> payload = voteOpenPayload(rt, ov);
        payload.put("kind", ev.variationKind.name().toLowerCase());
        registry.broadcast(rt, "room.variation.voteOpen", payload);
        buffers.log(rt.watchSpaceId, actorId, EventLog.LogType.VOTE_OPEN, variationId);
    }

    /** One vote per user; voting again replaces the earlier choice until the vote closes. */
    public void vote(RoomRuntime rt, long userId, String variationId, String optionId) {
        OpenVote ov = variationId == null ? null : rt.openVotes.get(variationId);
        if (ov == null || ov.closed.get()) {
            throw ApiException.conflict("NO_OPEN_VOTE", "There is no open vote for " + variationId);
        }
        if (!rt.model.isMember(userId)) {
            throw ApiException.forbidden("Only participants can vote");
        }
        if (optionId == null || !ov.optionKeys.contains(optionId)) {
            throw ApiException.badRequest("Unknown option " + optionId);
        }
        ov.votes.put(userId, optionId);
        buffers.log(rt.watchSpaceId, userId, EventLog.LogType.VOTE_CAST, variationId);
    }

    /** Host may close early. */
    public void closeByHost(RoomRuntime rt, long actorId, String variationId) {
        if (actorId != rt.model.hostUserId()) {
            throw ApiException.forbidden("Only the host can close a vote");
        }
        if (variationId == null || !rt.openVotes.containsKey(variationId)) {
            throw ApiException.conflict("NO_OPEN_VOTE", "There is no open vote for " + variationId);
        }
        close(rt, variationId);
    }

    /** Tallies and applies exactly once (the timer and an early close can both call this). */
    public void close(RoomRuntime rt, String variationId) {
        OpenVote ov = rt.openVotes.get(variationId);
        if (ov == null || !ov.closed.compareAndSet(false, true)) {
            return;
        }
        Map<Long, String> cast = Map.copyOf(ov.votes);
        String winner = ov.winner();
        Map<String, Integer> tally = ov.tally();
        rt.appliedVariations.put(variationId, winner);
        rt.openVotes.remove(variationId);
        if (ov.timer != null) {
            ov.timer.cancel(false);
        }
        try {
            store.save(rt.watchSpaceId, ov.eventId, Instant.ofEpochMilli(ov.openedAtMs), Instant.ofEpochMilli(ov.closesAtMs),
                    winner, cast);
        } catch (RuntimeException e) {
            log.error("variation.persist.failed corr={} ws={} variation={} error={}", rt.corr, rt.watchSpaceId, variationId, e.toString());
        }
        String label = ov.optionLabels.get(ov.optionKeys.indexOf(winner));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("variationId", variationId);
        payload.put("winningOptionId", winner);
        payload.put("label", label);
        payload.put("appliesAtTs", ov.appliesAtTs);
        payload.put("tally", tally);
        registry.broadcast(rt, "room.variation.applied", payload);
        buffers.log(rt.watchSpaceId, null, EventLog.LogType.VOTE_APPLIED, variationId + "=" + winner);
        log.info("variation.applied corr={} ws={} variation={} winner={} tally={}", rt.corr, rt.watchSpaceId, variationId, winner, tally);
    }

    /** Cancels pending vote timers when a room shuts down. Undecided votes are dropped, never half-applied. */
    public void discardRoom(RoomRuntime rt) {
        for (OpenVote ov : rt.openVotes.values()) {
            ov.closed.set(true);
            if (ov.timer != null) {
                ov.timer.cancel(false);
            }
        }
        rt.openVotes.clear();
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private Map<String, Object> voteOpenPayload(RoomRuntime rt, OpenVote ov) {
        List<Map<String, Object>> opts = new ArrayList<>();
        for (int i = 0; i < ov.optionKeys.size(); i++) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", ov.optionKeys.get(i));
            o.put("label", ov.optionLabels.get(i));
            opts.add(o);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("variationId", ov.variationId);
        payload.put("ts", ov.appliesAtTs);
        payload.put("options", opts);
        payload.put("closesAt", ov.closesAtMs);
        return payload;
    }

    private static VariationOption byKey(TimelineEvent e, String key) {
        for (VariationOption o : e.options) {
            if (o.optionKey.equals(key)) {
                return o;
            }
        }
        return e.options.get(0);
    }

    private static Choice choice(TimelineEvent e, VariationOption o, boolean fromVote) {
        return new Choice(e.variationId, e.tsSeconds, e.variationKind.name().toLowerCase(), o.optionKey, o.label, o.locale,
                fromVote);
    }
}
