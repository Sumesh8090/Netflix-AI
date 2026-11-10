package com.watchspaces.ws;

import com.watchspaces.core.GroundedQa;
import com.watchspaces.core.KnowledgeEntry;
import com.watchspaces.core.PlaybackStateMachine;
import com.watchspaces.core.TriviaPlanner;
import com.watchspaces.entity.ChatMessage;
import com.watchspaces.entity.EventLog;
import com.watchspaces.entity.TimelineEvent;
import com.watchspaces.service.MetricsRegistry;
import com.watchspaces.service.PersistenceBuffers;
import com.watchspaces.service.TimelineService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Timeline-aware trigger. Every tick (default 250 ms) each occupied room is checked for markers crossed since the last
 * tick, so a card fires within one tick of its authored timestamp. The decision itself is {@link TriviaPlanner} (pure,
 * unit-tested); this class only reads the room's clock and broadcasts.
 */
@Component
public class TriviaScheduler {
    private static final Logger log = LoggerFactory.getLogger(TriviaScheduler.class);

    private final RoomRegistry registry;
    private final TimelineService timeline;
    private final PersistenceBuffers buffers;
    private final MetricsRegistry metrics;
    private final VariationCoordinator variations;

    public TriviaScheduler(RoomRegistry registry, TimelineService timeline, PersistenceBuffers buffers, MetricsRegistry metrics,
                           VariationCoordinator variations) {
        this.variations = variations;
        this.registry = registry;
        this.timeline = timeline;
        this.buffers = buffers;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.realtime.trivia-tick-ms:250}")
    public void tick() {
        for (RoomRuntime rt : registry.all()) {
            try {
                tickRoom(rt);
            } catch (RuntimeException e) {
                log.error("trivia.tick.failed corr={} ws={} error={}", rt.corr, rt.watchSpaceId, e.toString());
            }
        }
    }

    void tickRoom(RoomRuntime rt) {
        if (rt.sessionCount() == 0) {
            return;
        }
        PlaybackStateMachine.Snapshot snap = rt.playback.snapshot();
        double pos = snap.positionSeconds();
        double from;
        synchronized (rt.triviaLock) {
            from = rt.lastTriviaPos;
            rt.lastTriviaPos = pos;
        }
        var level = TriviaPlanner.Level.valueOf(rt.verbosity.name());
        for (KnowledgeEntry e : TriviaPlanner.due(timeline.knowledge(rt.titleId), from, pos, level, rt.triviaEnabled,
                snap.state() == PlaybackStateMachine.State.PLAYING)) {
            fireCard(rt, e, pos);
        }
        fireVariations(rt, from, pos, snap.state() == PlaybackStateMachine.State.PLAYING);
    }

    /**
     * When playback crosses a variation point: subtitle points are pushed to each viewer in THEIR locale (personal, never touches
     * playback); a narrative point whose vote has been decided is announced once, identically, to everybody.
     */
    private void fireVariations(RoomRuntime rt, double from, double pos, boolean playing) {
        for (TimelineEvent e : timeline.variationPoints(rt.titleId)) {
            if (!TriviaPlanner.crossed(e.tsSeconds, from, pos, playing)) {
                continue;
            }
            if (e.variationKind == TimelineEvent.VariationKind.SUBTITLE) {
                for (Long uid : rt.userSessions.keySet()) {
                    var c = variations.choiceFor(rt, e, rt.locales.get(uid));
                    if (c != null) {
                        registry.sendToUser(rt, uid, "room.subtitle.swap", VariationCoordinator.wire(c));
                    }
                }
            } else {
                var c = variations.choiceFor(rt, e, null);
                if (c != null) {
                    registry.broadcast(rt, "room.variation.reached", VariationCoordinator.wire(c));
                }
            }
        }
    }

    /** Called when the host plays/pauses/seeks so the next tick does not treat the jump as elapsed play time. */
    public void rebase(RoomRuntime rt, double position) {
        synchronized (rt.triviaLock) {
            rt.lastTriviaPos = position;
        }
    }

    private void fireCard(RoomRuntime rt, KnowledgeEntry e, double pos) {
        String text = GroundedQa.renderCard(e);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", e.id());
        payload.put("kind", e.type().name().toLowerCase());
        payload.put("text", text);
        payload.put("tsSeconds", e.startTs());
        synchronized (rt.orderLock) {
            long seq = rt.chatSeq.incrementAndGet();
            payload.put("seq", seq);
            buffers.addChat(rt.watchSpaceId, null, ChatMessage.MsgType.SYSTEM, UUID.randomUUID().toString(), text, seq,
                    e.startTs());
            registry.broadcast(rt, "room.ai.trivia", payload);
        }
        buffers.log(rt.watchSpaceId, null, EventLog.LogType.TRIVIA, e.id());
        long lagMs = Math.round(Math.max(0, (pos - e.startTs()) * 1000.0));
        metrics.triviaLagMs.record(lagMs);
        log.info("ai.trivia corr={} ws={} event={} lagMs={}", rt.corr, rt.watchSpaceId, e.id(), lagMs);
    }
}
