package com.watchspaces.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchspaces.core.PlaybackStateMachine;
import com.watchspaces.core.RoomModel;
import com.watchspaces.entity.WatchSpace;
import com.watchspaces.entity.RoomVariation;
import com.watchspaces.repo.ChatMessageRepository;
import com.watchspaces.repo.RoomVariationRepository;
import com.watchspaces.repo.WatchSpaceRepository;
import com.watchspaces.service.MetricsRegistry;
import com.watchspaces.web.ApiException;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Owns the live rooms and the fan-out primitives. No room assumes it is the only one. */
@Component
public class RoomRegistry {
    private static final Logger log = LoggerFactory.getLogger(RoomRegistry.class);

    private final Map<Long, RoomRuntime> rooms = new ConcurrentHashMap<>();
    private final WatchSpaceRepository watchSpaces;
    private final ChatMessageRepository chat;
    private final RoomVariationRepository roomVariations;
    private final ObjectMapper mapper;
    private final MetricsRegistry metrics;

    public RoomRegistry(WatchSpaceRepository watchSpaces, ChatMessageRepository chat, RoomVariationRepository roomVariations, ObjectMapper mapper,
                        MetricsRegistry metrics) {
        this.watchSpaces = watchSpaces;
        this.chat = chat;
        this.roomVariations = roomVariations;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public RoomRuntime getOrCreate(long watchSpaceId) {
        RoomRuntime existing = rooms.get(watchSpaceId);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            RoomRuntime again = rooms.get(watchSpaceId);
            if (again != null) {
                return again;
            }
            RoomRuntime created = load(watchSpaceId);
            rooms.put(watchSpaceId, created);
            return created;
        }
    }

    public Optional<RoomRuntime> find(long watchSpaceId) {
        return Optional.ofNullable(rooms.get(watchSpaceId));
    }

    public Collection<RoomRuntime> all() {
        return rooms.values();
    }

    /** Returns true only for the caller that actually removed the room (used as an "end exactly once" guard). */
    public boolean remove(RoomRuntime rt) {
        return rooms.remove(rt.watchSpaceId, rt);
    }

    private RoomRuntime load(long id) {
        WatchSpace w = watchSpaces.findById(id).orElseThrow(() -> ApiException.notFound("Watch Space"));
        if (w.status == WatchSpace.Status.ENDED) {
            throw new ApiException(HttpStatus.GONE, "SESSION_ENDED", "This Watch Space has ended");
        }
        PlaybackStateMachine pb = new PlaybackStateMachine(w.title.durationSeconds, System::currentTimeMillis);
        RoomModel model = new RoomModel(id, w.host.id, w.maxParticipants, pb);
        RoomRuntime rt = new RoomRuntime(id, w.title.id, w.title.durationSeconds, pb, model, w.aiVerbosity,
                w.triviaEnabled, w.votingEnabled, chat.maxSeq(id));
        // votes already decided in this Watch Space stay decided if the room is rebuilt (e.g. after a restart)
        for (RoomVariation rv : roomVariations.findByWatchSpace_Id(id)) {
            if (rv.status == RoomVariation.Status.CLOSED && rv.winningOptionKey != null && rv.event.variationId != null) {
                rt.appliedVariations.put(rv.event.variationId, rv.winningOptionKey);
            }
        }
        return rt;
    }

    // ---- sending -------------------------------------------------------------------------------------

    public String envelope(RoomRuntime rt, String event, Object payload) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("event", event);
        env.put("watchSpaceId", rt.watchSpaceId);
        env.put("payload", payload);
        env.put("ts", System.currentTimeMillis());
        try {
            return mapper.writeValueAsString(env);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + event, e);
        }
    }

    public void broadcast(RoomRuntime rt, String event, Object payload) {
        broadcastExcept(rt, event, payload, -1);
    }

    /** Sends to every session in the room except those of {@code excludeUserId}. Records fan-out time. */
    public void broadcastExcept(RoomRuntime rt, String event, Object payload, long excludeUserId) {
        long t0 = System.nanoTime();
        TextMessage msg = new TextMessage(envelope(rt, event, payload));
        for (Map.Entry<Long, Set<String>> e : rt.userSessions.entrySet()) {
            if (e.getKey() == excludeUserId) {
                continue;
            }
            for (String sid : e.getValue()) {
                WebSocketSession s = rt.sessions.get(sid);
                if (s != null) {
                    safeSend(s, msg);
                }
            }
        }
        metrics.fanoutMs.record((System.nanoTime() - t0) / 1_000_000.0);
    }

    public void sendTo(RoomRuntime rt, WebSocketSession session, String event, Object payload) {
        safeSend(session, new TextMessage(envelope(rt, event, payload)));
    }

    public void sendToUser(RoomRuntime rt, long userId, String event, Object payload) {
        Set<String> ids = rt.userSessions.get(userId);
        if (ids == null) {
            return;
        }
        TextMessage msg = new TextMessage(envelope(rt, event, payload));
        for (String sid : ids) {
            WebSocketSession s = rt.sessions.get(sid);
            if (s != null) {
                safeSend(s, msg);
            }
        }
    }

    public void closeUser(RoomRuntime rt, long userId, CloseStatus status) {
        Set<String> ids = rt.userSessions.get(userId);
        if (ids == null) {
            return;
        }
        for (String sid : Set.copyOf(ids)) {
            WebSocketSession s = rt.sessions.get(sid);
            if (s != null) {
                try {
                    s.close(status);
                } catch (IOException ignored) {
                    // already closing
                }
            }
        }
    }

    public void closeAll(RoomRuntime rt, CloseStatus status) {
        for (WebSocketSession s : rt.sessions.values()) {
            try {
                s.close(status);
            } catch (IOException ignored) {
                // already closing
            }
        }
    }

    private void safeSend(WebSocketSession s, TextMessage msg) {
        try {
            if (s.isOpen()) {
                s.sendMessage(msg);
            }
        } catch (Exception e) {
            log.warn("ws.send.failed session={} error={}", s.getId(), e.toString());
            try {
                s.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }
}
