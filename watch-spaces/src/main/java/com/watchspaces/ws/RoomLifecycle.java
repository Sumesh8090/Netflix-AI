package com.watchspaces.ws;

import com.watchspaces.service.PersistenceBuffers;
import com.watchspaces.service.RecommendationService;
import com.watchspaces.service.SessionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

/** Ends a live Watch Space exactly once: announces it, freezes the analytics snapshot on the row, closes the sockets. */
@Component
public class RoomLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RoomLifecycle.class);

    private final RoomRegistry registry;
    private final SessionService sessions;
    private final PersistenceBuffers buffers;
    private final VariationCoordinator variations;
    private final RecommendationService recommendations;

    public RoomLifecycle(RoomRegistry registry, SessionService sessions, PersistenceBuffers buffers,
                         VariationCoordinator variations, RecommendationService recommendations) {
        this.variations = variations;
        this.recommendations = recommendations;
        this.registry = registry;
        this.sessions = sessions;
        this.buffers = buffers;
    }

    /** Returns the final analytics, or null if somebody else already ended this room. */
    public SessionService.Analytics end(RoomRuntime rt, Long endedBy, String reason) {
        if (!registry.remove(rt)) {
            return null;
        }
        for (ScheduledFuture<?> f : rt.leaveTimers.values()) {
            f.cancel(false);
        }
        variations.discardRoom(rt);
        double endPosition = rt.playback.positionSeconds();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("endedBy", endedBy);
        registry.broadcast(rt, "room.ended", payload);

        buffers.flushAll();
        SessionService.Analytics a = sessions.finish(rt.watchSpaceId);
        registry.closeAll(rt, CloseStatus.NORMAL);
        try {
            // feeds the recommender: one viewing record per person who was in the room (position approximates what each saw)
            recommendations.recordSession(rt.titleId, endPosition, new ArrayList<>(rt.everJoined));
        } catch (RuntimeException e) {
            log.error("session.interactions.failed corr={} ws={} error={}", rt.corr, rt.watchSpaceId, e.toString());
        }
        log.info("session.ended corr={} ws={} reason={} peak={} ai={} trivia={} chat={}", rt.corr, rt.watchSpaceId, reason,
                a.peakConcurrentParticipants(), a.aiQuestions(), a.triviaCards(), a.chatMessages());
        return a;
    }
}
