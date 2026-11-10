package com.watchspaces.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchspaces.config.AppProperties;
import com.watchspaces.core.ChatRules;
import com.watchspaces.core.DriftCorrector;
import com.watchspaces.core.PlaybackStateMachine;
import com.watchspaces.core.PlaybackStateMachine.Command;
import com.watchspaces.core.RoomModel;
import com.watchspaces.core.VariationRules;
import com.watchspaces.entity.ChatMessage;
import com.watchspaces.entity.EventLog;
import com.watchspaces.repo.UserRepository;
import com.watchspaces.security.AuthUser;
import com.watchspaces.service.AiService;
import com.watchspaces.service.MetricsRegistry;
import com.watchspaces.service.PersistenceBuffers;
import com.watchspaces.service.SessionService;
import com.watchspaces.util.InputSanitizer;
import com.watchspaces.web.ApiException;
import com.watchspaces.web.Dto;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Real-time gateway. Client -> server events (envelope {"event","payload"}):
 * <pre>
 *   room.playback.command  {action: play|pause|seek|buffer_start|buffer_end, positionSeconds}   host only
 *   room.sync.ping         {clientSentAt, positionSeconds, rttMs?}
 *   room.state.request     {}
 *   room.chat.send         {body}
 *   room.chat.typing       {typing: true|false}
 *   room.reaction          {emoji}
 *   room.ai.ask            {question, currentTs?}
 *   room.mod.mute          {userId, muted}                  host only
 *   room.mod.remove        {userId}                         host only
 *   room.mod.lock          {locked}                         host only
 *   room.mod.transferHost  {userId}                         host only
 *   room.session.end       {}                               host only
 *   room.locale.set        {locale}                         changes this viewer's subtitle locale
 *   room.variation.open    {variationId, durationSeconds?}  host only (needs votingEnabled)
 *   room.variation.vote    {variationId, optionId}          one vote per user, re-voting replaces
 *   room.variation.close   {variationId}                    host only (early close)
 * </pre>
 * Server -> client: room.state, room.playback.update, room.presence.update, room.chat.message, room.chat.typing,
 * room.reaction, room.ai.trivia, room.ai.answer, room.moderation, room.host.disconnected, room.host.reconnected,
 * room.host.changed, room.sync.pong, room.ended, room.error, room.subtitle.swap (personal), room.subtitle.choices (personal),
 * room.variation.voteOpen, room.variation.voted (to the voter), room.variation.applied, room.variation.reached.
 * Playback position is held in memory only; nothing is written per heartbeat, and chat/log rows are batched.
 */
@Component
public class RoomSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RoomSocketHandler.class);
    private static final int RECENT_CHAT_ON_JOIN = 30;

    private final RoomRegistry registry;
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final PersistenceBuffers buffers;
    private final MetricsRegistry metrics;
    private final TaskScheduler scheduler;
    private final AiService ai;
    private final RoomLifecycle lifecycle;
    private final TriviaScheduler trivia;
    private final VariationCoordinator variations;
    private final UserRepository users;
    private final long reconnectGraceMs;
    private final long hostGraceMs;
    private final long emptyRoomTtlMs;

    /** raw session id -> room id (so close handling does not need attributes). */
    private final Map<String, Long> sessionRoom = new ConcurrentHashMap<>();

    public RoomSocketHandler(RoomRegistry registry, ObjectMapper mapper, SessionService sessions, PersistenceBuffers buffers,
                             MetricsRegistry metrics, TaskScheduler scheduler, AiService ai, RoomLifecycle lifecycle,
                             TriviaScheduler trivia, VariationCoordinator variations, UserRepository users,
                             AppProperties props) {
        this.variations = variations;
        this.users = users;
        this.registry = registry;
        this.mapper = mapper;
        this.sessions = sessions;
        this.buffers = buffers;
        this.metrics = metrics;
        this.scheduler = scheduler;
        this.ai = ai;
        this.lifecycle = lifecycle;
        this.trivia = trivia;
        this.reconnectGraceMs = props.realtime().reconnectGraceMs();
        this.hostGraceMs = props.realtime().hostGraceMs();
        this.emptyRoomTtlMs = props.realtime().emptyRoomTtlMs();
    }

    // ---- connect -------------------------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        AuthUser user = (AuthUser) raw.getAttributes().get(JwtHandshakeInterceptor.ATTR_USER);
        long roomId = (Long) raw.getAttributes().get(JwtHandshakeInterceptor.ATTR_WS_ID);
        RoomRuntime rt;
        try {
            rt = registry.getOrCreate(roomId);
        } catch (ApiException e) {
            raw.close(new CloseStatus(4404, e.getMessage()));
            return;
        }
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 512 * 1024);
        String name = (String) raw.getAttributes().get(JwtHandshakeInterceptor.ATTR_NAME);
        RoomModel.JoinResult jr;
        try {
            jr = rt.model.join(user.id(), name, System.currentTimeMillis());
        } catch (RoomModel.RoomException e) {
            sendError(rt, session, e.code(), e.getMessage());
            session.close(new CloseStatus(4403, e.code()));
            return;
        }
        rt.emptySinceMs = 0;
        rt.sessions.put(raw.getId(), session);
        rt.userSessions.computeIfAbsent(user.id(), k -> ConcurrentHashMap.newKeySet()).add(raw.getId());
        rt.displayNames.put(user.id(), name);
        rt.everJoined.add(user.id());
        rt.locales.put(user.id(), initialLocale(raw, user.id()));
        sessionRoom.put(raw.getId(), roomId);
        var timer = rt.leaveTimers.remove(user.id());
        if (timer != null) {
            timer.cancel(false);
        }

        if (jr.firstJoin()) {
            sessions.recordParticipant(roomId, user.id());
            sessions.markLive(roomId);
            buffers.log(roomId, user.id(), EventLog.LogType.JOIN, null);
        }
        // a host coming back within the grace window resumes the room (stays paused until the host presses play)
        if (user.id() == rt.model.hostUserId()) {
            registry.broadcast(rt, "room.host.reconnected", Map.of("hostUserId", user.id()));
        }
        log.info("ws.join corr={} ws={} user={} first={} count={}", rt.corr, roomId, user.id(), jr.firstJoin(), jr.participantCount());

        registry.sendTo(rt, session, "room.state", snapshot(rt, user.id()));
        registry.broadcastExcept(rt, "room.presence.update", presence(rt, user.id(), jr.firstJoin() ? "joined" : "reconnected"),
                user.id());
    }

    // ---- messages ------------------------------------------------------------------------------------

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        AuthUser user = (AuthUser) raw.getAttributes().get(JwtHandshakeInterceptor.ATTR_USER);
        Long roomId = sessionRoom.get(raw.getId());
        RoomRuntime rt = roomId == null ? null : registry.find(roomId).orElse(null);
        if (rt == null) {
            return;
        }
        WebSocketSession session = rt.sessions.get(raw.getId());
        metrics.wsMessages.incrementAndGet();
        try {
            long expires = (Long) raw.getAttributes().get(JwtHandshakeInterceptor.ATTR_EXPIRES);
            if (System.currentTimeMillis() > expires) {
                sendError(rt, session, "TOKEN_EXPIRED", "Access token expired; reconnect with a fresh token");
                session.close(new CloseStatus(4401, "TOKEN_EXPIRED"));
                return;
            }
            JsonNode root = mapper.readTree(message.getPayload());
            String event = root.path("event").asText("");
            JsonNode p = root.path("payload");
            switch (event) {
                case "room.playback.command" -> playback(rt, user, p);
                case "room.sync.ping" -> ping(rt, session, user, p);
                case "room.state.request" -> registry.sendTo(rt, session, "room.state", snapshot(rt, user.id()));
                case "room.chat.send" -> chat(rt, session, user, p);
                case "room.chat.typing" -> typing(rt, user, p);
                case "room.reaction" -> reaction(rt, session, user, p);
                case "room.ai.ask" -> ask(rt, session, user, p);
                case "room.mod.mute", "room.mod.remove", "room.mod.lock", "room.mod.transferHost" ->
                        moderate(rt, session, user, event, p);
                case "room.session.end" -> endSession(rt, session, user);
                case "room.locale.set" -> setLocale(rt, session, user, p);
                case "room.variation.open" -> variations.open(rt, user.id(), p.path("variationId").asText(""),
                        p.hasNonNull("durationSeconds") ? p.get("durationSeconds").asInt() : null);
                case "room.variation.vote" -> {
                    String vid = p.path("variationId").asText("");
                    String oid = p.path("optionId").asText("");
                    variations.vote(rt, user.id(), vid, oid);
                    registry.sendTo(rt, session, "room.variation.voted", Map.of("variationId", vid, "optionId", oid));
                }
                case "room.variation.close" -> variations.closeByHost(rt, user.id(), p.path("variationId").asText(""));
                default -> sendError(rt, session, "UNKNOWN_EVENT", "Unknown event: " + event);
            }
        } catch (ApiException e) {
            sendError(rt, session, e.code(), e.getMessage());
        } catch (Exception e) {
            metrics.wsErrors.incrementAndGet();
            log.warn("ws.message.failed corr={} user={} error={}", rt.corr, user.id(), e.toString());
            sendError(rt, session, "BAD_MESSAGE", "Malformed message");
        }
    }

    private void playback(RoomRuntime rt, AuthUser user, JsonNode p) {
        WebSocketSession s = firstSession(rt, user.id());
        // server-side check on every command: only the CURRENT host (and only while connected) may drive playback
        if (!rt.model.canIssuePlayback(user.id())) {
            sendError(rt, s, "FORBIDDEN", "Only the host can control playback");
            return;
        }
        String action = p.path("action").asText("");
        Double pos = p.hasNonNull("positionSeconds") ? p.get("positionSeconds").asDouble() : null;
        Command cmd;
        switch (action) {
            case "play" -> cmd = Command.PLAY;
            case "pause" -> cmd = Command.PAUSE;
            case "seek" -> cmd = Command.SEEK;
            case "buffer_start" -> cmd = Command.BUFFER_START;
            case "buffer_end" -> cmd = Command.BUFFER_END;
            default -> {
                sendError(rt, s, "BAD_ACTION", "action must be play|pause|seek|buffer_start|buffer_end");
                return;
            }
        }
        try {
            PlaybackStateMachine.Snapshot snap = rt.playback.apply(cmd, pos);
            trivia.rebase(rt, snap.positionSeconds());
            registry.broadcast(rt, "room.playback.update", playbackPayload(snap, user.id(), action));
        } catch (PlaybackStateMachine.IllegalTransitionException e) {
            sendError(rt, s, "ILLEGAL_TRANSITION", e.getMessage());
        }
    }

    private void ping(RoomRuntime rt, WebSocketSession s, AuthUser user, JsonNode p) {
        long received = System.currentTimeMillis();
        PlaybackStateMachine.Snapshot snap = rt.playback.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientSentAt", p.path("clientSentAt").asLong(0));
        out.put("serverReceivedAt", received);
        out.put("state", snap.state().name().toLowerCase());
        out.put("authoritativePositionSeconds", snap.positionSeconds());
        if (p.hasNonNull("positionSeconds") && user.id() != rt.model.hostUserId()) {
            long oneWay = p.hasNonNull("rttMs") ? p.get("rttMs").asLong() / 2 : 0;
            var c = DriftCorrector.evaluate(p.get("positionSeconds").asDouble(), snap.positionSeconds(),
                    snap.state() == PlaybackStateMachine.State.PLAYING, oneWay);
            metrics.syncDriftAbsMs.record(Math.abs(c.driftMs()));
            Map<String, Object> corr = new LinkedHashMap<>();
            corr.put("action", c.action().name().toLowerCase());
            corr.put("targetPositionSeconds", c.targetPositionSeconds());
            corr.put("driftMs", c.driftMs());
            out.put("correction", corr);
        }
        registry.sendTo(rt, s, "room.sync.pong", out);
    }

    // ---- chat, reactions, AI -------------------------------------------------------------------------

    private void chat(RoomRuntime rt, WebSocketSession s, AuthUser user, JsonNode p) {
        if (!rt.model.canChat(user.id())) {
            sendError(rt, s, "MUTED", "You are muted in this Watch Space");
            return;
        }
        String cleaned = InputSanitizer.clean(p.path("body").asText(""));
        ChatRules.Problem problem = ChatRules.check(cleaned);
        if (problem != null) {
            sendError(rt, s, problem == ChatRules.Problem.EMPTY ? "EMPTY_MESSAGE" : "MESSAGE_TOO_LONG",
                    problem == ChatRules.Problem.EMPTY ? "Message is empty" : "Message must be at most "
                            + ChatRules.MAX_BODY_CHARS + " characters");
            return;
        }
        if (!rt.chatLimiter.tryAcquire(user.id(), System.currentTimeMillis())) {
            sendError(rt, s, "RATE_LIMITED", "You are sending messages too quickly");
            return;
        }
        String body = InputSanitizer.text(cleaned); // escaped: stored and delivered as inert text
        String uuid = UUID.randomUUID().toString();
        double pos = rt.playback.positionSeconds();
        // seq assignment + broadcast are one atomic step, so every client sees chat in the same order
        synchronized (rt.orderLock) {
            long seq = rt.chatSeq.incrementAndGet();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("messageId", uuid);
            m.put("seq", seq);
            m.put("userId", user.id());
            m.put("displayName", rt.displayNames.get(user.id()));
            m.put("body", body);
            m.put("tsSeconds", pos);
            m.put("sentAt", System.currentTimeMillis());
            registry.broadcast(rt, "room.chat.message", m);
            buffers.addChat(rt.watchSpaceId, user.id(), ChatMessage.MsgType.CHAT, uuid, body, seq, pos);
        }
        buffers.log(rt.watchSpaceId, user.id(), EventLog.LogType.CHAT, null);
    }

    private void typing(RoomRuntime rt, AuthUser user, JsonNode p) {
        if (!rt.model.canChat(user.id()) || !rt.typingLimiter.tryAcquire(user.id(), System.currentTimeMillis())) {
            return; // typing indicators are best-effort: silently dropped
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", user.id());
        m.put("displayName", rt.displayNames.get(user.id()));
        m.put("typing", p.path("typing").asBoolean(true));
        registry.broadcastExcept(rt, "room.chat.typing", m, user.id());
    }

    private void reaction(RoomRuntime rt, WebSocketSession s, AuthUser user, JsonNode p) {
        String emoji = p.path("emoji").asText("");
        if (!ChatRules.isReaction(emoji)) {
            sendError(rt, s, "BAD_REACTION", "Unsupported reaction");
            return;
        }
        if (!rt.model.canChat(user.id())) {
            sendError(rt, s, "MUTED", "You are muted in this Watch Space");
            return;
        }
        if (!rt.chatLimiter.tryAcquire(user.id(), System.currentTimeMillis())) {
            sendError(rt, s, "RATE_LIMITED", "You are sending messages too quickly");
            return;
        }
        registry.broadcast(rt, "room.reaction", Map.of("userId", user.id(), "emoji", emoji));
    }

    private void ask(RoomRuntime rt, WebSocketSession s, AuthUser user, JsonNode p) {
        if (!rt.aiLimiter.tryAcquire(user.id(), System.currentTimeMillis())) {
            sendError(rt, s, "RATE_LIMITED", "Please wait before asking the co-pilot again");
            return;
        }
        String question = p.path("question").asText("");
        Integer claimedTs = p.hasNonNull("currentTs") ? p.get("currentTs").asInt() : null;
        Dto.AskResult a = ai.ask(rt, user.id(), claimedTs, question);
        registry.broadcast(rt, "room.ai.answer", ai.answerPayload(user.id(), question, a));
    }

    // ---- locale ----------------------------------------------------------------------------------------

    /** Handshake ?locale= first, otherwise the viewer's saved profile locale, otherwise English. */
    private String initialLocale(WebSocketSession raw, long userId) {
        Object fromUrl = raw.getAttributes().get(JwtHandshakeInterceptor.ATTR_LOCALE);
        if (fromUrl instanceof String l) {
            return l;
        }
        try {
            return users.findById(userId).map(u -> VariationRules.normalizeLocale(u.subtitleLocale)).orElse("en");
        } catch (RuntimeException e) {
            return "en";
        }
    }

    private void setLocale(RoomRuntime rt, WebSocketSession s, AuthUser user, JsonNode p) {
        String loc = VariationRules.normalizeLocale(p.path("locale").asText(""));
        if (loc == null) {
            sendError(rt, s, "BAD_LOCALE", "locale must look like 'hi' or 'pt-BR'");
            return;
        }
        rt.locales.put(user.id(), loc);
        registry.sendToUser(rt, user.id(), "room.subtitle.choices", variations.snapshotFor(rt, loc));
    }

    // ---- moderation and session end ------------------------------------------------------------------

    private void moderate(RoomRuntime rt, WebSocketSession s, AuthUser user, String event, JsonNode p) {
        long target = p.path("userId").asLong(-1);
        try {
            switch (event) {
                case "room.mod.mute" -> {
                    boolean muted = p.path("muted").asBoolean(true);
                    rt.model.mute(user.id(), target, muted);
                    registry.broadcast(rt, "room.moderation", moderation(muted ? "muted" : "unmuted", target, user.id()));
                }
                case "room.mod.remove" -> {
                    rt.model.remove(user.id(), target);
                    registry.broadcast(rt, "room.moderation", moderation("removed", target, user.id()));
                    var timer = rt.leaveTimers.remove(target);
                    if (timer != null) {
                        timer.cancel(false);
                    }
                    registry.closeUser(rt, target, new CloseStatus(4403, "REMOVED"));
                    sessions.markLeft(rt.watchSpaceId, target);
                    buffers.log(rt.watchSpaceId, target, EventLog.LogType.LEAVE, "removed");
                    registry.broadcast(rt, "room.presence.update", presence(rt, target, "left"));
                }
                case "room.mod.lock" -> {
                    boolean locked = p.path("locked").asBoolean(true);
                    rt.model.setLocked(user.id(), locked);
                    registry.broadcast(rt, "room.moderation", moderation(locked ? "locked" : "unlocked", null, user.id()));
                }
                case "room.mod.transferHost" -> {
                    long previous = rt.model.hostUserId();
                    rt.model.transferHost(user.id(), target);
                    sessions.changeHost(rt.watchSpaceId, target);
                    buffers.log(rt.watchSpaceId, target, EventLog.LogType.HOST_CHANGE, "transfer");
                    registry.broadcast(rt, "room.host.changed",
                            Map.of("hostUserId", target, "previousHostUserId", previous, "reason", "transfer"));
                }
                default -> sendError(rt, s, "UNKNOWN_EVENT", "Unknown event: " + event);
            }
        } catch (RoomModel.RoomException e) {
            sendError(rt, s, e.code(), e.getMessage());
        }
    }

    private void endSession(RoomRuntime rt, WebSocketSession s, AuthUser user) {
        if (user.id() != rt.model.hostUserId()) {
            sendError(rt, s, "FORBIDDEN", "Only the host can end the Watch Space");
            return;
        }
        lifecycle.end(rt, user.id(), "host_ended");
    }

    private Map<String, Object> moderation(String action, Long target, long by) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("targetUserId", target);
        m.put("by", by);
        return m;
    }

    // ---- disconnect ----------------------------------------------------------------------------------

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        AuthUser user = (AuthUser) raw.getAttributes().get(JwtHandshakeInterceptor.ATTR_USER);
        Long roomId = sessionRoom.remove(raw.getId());
        RoomRuntime rt = roomId == null ? null : registry.find(roomId).orElse(null);
        if (rt == null || user == null) {
            return;
        }
        rt.sessions.remove(raw.getId());
        var ids = rt.userSessions.get(user.id());
        if (ids != null) {
            ids.remove(raw.getId());
            if (!ids.isEmpty()) {
                return; // still has another tab open
            }
            rt.userSessions.remove(user.id());
        }
        if (rt.sessionCount() == 0) {
            rt.emptySinceMs = System.currentTimeMillis();
        }
        if (!rt.model.isMember(user.id())) {
            return; // removed by the host or replaced as host: nothing more to announce
        }
        boolean hostDropped = rt.model.markDisconnected(user.id(), System.currentTimeMillis());
        registry.broadcast(rt, "room.presence.update", presence(rt, user.id(), "disconnected"));
        if (hostDropped) {
            // deterministic policy: pause for everyone, announce, then fail over after hostGraceMs (see tick())
            PlaybackStateMachine.Snapshot snap = rt.playback.snapshot();
            registry.broadcast(rt, "room.host.disconnected", Map.of("hostUserId", user.id(), "graceMs", hostGraceMs,
                    "policy", "pause-then-promote"));
            registry.broadcast(rt, "room.playback.update", playbackPayload(snap, user.id(), "host_disconnected"));
        }
        // viewers get a short reconnect window before they are really removed
        if (user.id() != rt.model.hostUserId()) {
            long uid = user.id();
            rt.leaveTimers.put(uid, scheduler.schedule(() -> finalizeLeave(rt, uid),
                    Instant.now().plusMillis(reconnectGraceMs)));
        }
    }

    private void finalizeLeave(RoomRuntime rt, long userId) {
        rt.leaveTimers.remove(userId);
        if (rt.model.finalizeLeave(userId)) {
            sessions.markLeft(rt.watchSpaceId, userId);
            buffers.log(rt.watchSpaceId, userId, EventLog.LogType.LEAVE, null);
            registry.broadcast(rt, "room.presence.update", presence(rt, userId, "left"));
        }
    }

    /**
     * Housekeeping, several times a second: (1) host failover once the host's grace period has elapsed,
     * (2) rooms that have been empty for {@code emptyRoomTtlMs} are ended (or, if never occupied, simply dropped).
     */
    @Scheduled(fixedDelayString = "${app.realtime.trivia-tick-ms:250}")
    public void tick() {
        long now = System.currentTimeMillis();
        for (RoomRuntime rt : registry.all()) {
            try {
                failoverIfNeeded(rt, now);
                reapIfEmpty(rt, now);
            } catch (RuntimeException e) {
                log.error("ws.tick.failed corr={} ws={} error={}", rt.corr, rt.watchSpaceId, e.toString());
            }
        }
    }

    private void failoverIfNeeded(RoomRuntime rt, long now) {
        if (!rt.model.isHostDisconnected()) {
            return;
        }
        long oldHost = rt.model.hostUserId();
        rt.model.promoteIfHostGone(now, hostGraceMs).ifPresent(newHost -> {
            sessions.changeHost(rt.watchSpaceId, newHost);
            sessions.markLeft(rt.watchSpaceId, oldHost);
            buffers.log(rt.watchSpaceId, oldHost, EventLog.LogType.LEAVE, "host timed out");
            buffers.log(rt.watchSpaceId, newHost, EventLog.LogType.HOST_CHANGE, "promoted");
            registry.broadcast(rt, "room.host.changed", Map.of("hostUserId", newHost, "previousHostUserId", oldHost,
                    "reason", "host_timeout"));
            registry.broadcast(rt, "room.presence.update", presence(rt, oldHost, "left"));
        });
    }

    private void reapIfEmpty(RoomRuntime rt, long now) {
        if (rt.sessionCount() != 0 || !rt.leaveTimers.isEmpty()) {
            return;
        }
        if (rt.emptySinceMs > 0 && now - rt.emptySinceMs > emptyRoomTtlMs) {
            lifecycle.end(rt, null, "empty");
        } else if (rt.emptySinceMs == 0 && now - rt.createdAtMs > emptyRoomTtlMs) {
            registry.remove(rt); // created (e.g. by a REST call) but nobody ever connected
        }
    }

    // ---- payload helpers -----------------------------------------------------------------------------

    private Map<String, Object> playbackPayload(PlaybackStateMachine.Snapshot s, long issuedBy, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", action.equals("seek") ? "seek" : s.state().name().toLowerCase());
        m.put("playbackState", s.state().name().toLowerCase());
        m.put("positionSeconds", s.positionSeconds());
        m.put("serverTimeMs", s.serverTimeMs());
        m.put("version", s.version());
        m.put("issuedBy", issuedBy);
        return m;
    }

    private Map<String, Object> presence(RoomRuntime rt, long userId, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("participantId", userId);
        m.put("displayName", rt.displayNames.get(userId));
        m.put("action", action);
        m.put("participantCount", rt.model.connectedCount());
        return m;
    }

    /** Full room snapshot sent right after connect/reconnect, so the client can sync and restore chat context. */
    private Map<String, Object> snapshot(RoomRuntime rt, long forUser) {
        PlaybackStateMachine.Snapshot snap = rt.playback.snapshot();
        List<Map<String, Object>> members = rt.model.connectedMembers().stream().map(m -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("userId", m.userId);
            v.put("displayName", m.displayName);
            v.put("muted", m.muted);
            v.put("isHost", m.userId == rt.model.hostUserId());
            return v;
        }).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("yourUserId", forUser);
        m.put("hostUserId", rt.model.hostUserId());
        m.put("hostDisconnected", rt.model.isHostDisconnected());
        m.put("locked", rt.model.isLocked());
        m.put("playback", playbackPayload(snap, rt.model.hostUserId(), "state"));
        m.put("participants", members);
        m.put("recentChat", sessions.recentChat(rt.watchSpaceId, RECENT_CHAT_ON_JOIN));
        m.put("variations", variations.snapshotFor(rt, rt.locales.getOrDefault(forUser, "en")));
        m.put("serverTimeMs", System.currentTimeMillis());
        return m;
    }

    private void sendError(RoomRuntime rt, WebSocketSession s, String code, String message) {
        if (s != null) {
            registry.sendTo(rt, s, "room.error", Map.of("code", code, "message", message));
        }
    }

    private WebSocketSession firstSession(RoomRuntime rt, long userId) {
        var ids = rt.userSessions.get(userId);
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return rt.sessions.get(ids.iterator().next());
    }
}
