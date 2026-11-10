package com.watchspaces.service;

import com.watchspaces.core.GroundedQa;
import com.watchspaces.entity.ChatMessage;
import com.watchspaces.entity.EventLog;
import com.watchspaces.entity.WatchSpace;
import com.watchspaces.util.InputSanitizer;
import com.watchspaces.web.ApiException;
import com.watchspaces.web.Dto;
import com.watchspaces.ws.RoomRegistry;
import com.watchspaces.ws.RoomRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** AI Co-Pilot: grounded Q&amp;A. Used by both REST (/ai/ask) and the WebSocket (room.ai.ask). */
@Service
public class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    public static final int MAX_QUESTION_CHARS = 500;

    private final TimelineService timeline;
    private final PersistenceBuffers buffers;
    private final MetricsRegistry metrics;
    private final RoomRegistry registry;

    public AiService(TimelineService timeline, PersistenceBuffers buffers, MetricsRegistry metrics, RoomRegistry registry) {
        this.timeline = timeline;
        this.buffers = buffers;
        this.metrics = metrics;
        this.registry = registry;
    }

    /**
     * The asker's claimed timestamp is clamped to the room's authoritative position (+2 s of slack), so a client
     * cannot ask for spoilers by lying about where it is.
     */
    public Dto.AskResult ask(RoomRuntime rt, long userId, Integer claimedTs, String rawQuestion) {
        long t0 = System.nanoTime();
        String q = InputSanitizer.clean(rawQuestion);
        if (q == null || q.isBlank()) {
            throw ApiException.badRequest("question must not be empty");
        }
        if (q.length() > MAX_QUESTION_CHARS) {
            throw ApiException.badRequest("question must be at most " + MAX_QUESTION_CHARS + " characters");
        }
        int roomPos = (int) Math.floor(rt.playback.positionSeconds());
        int ts = claimedTs == null ? roomPos : Math.max(0, Math.min(claimedTs, roomPos + 2));
        int maxSources = switch (rt.verbosity) {
            case MINIMAL -> 1;
            case NORMAL -> 2;
            case CHATTY -> 3;
        };
        GroundedQa.Result r = GroundedQa.answer(timeline.knowledge(rt.titleId), q, ts, maxSources);
        String questionId = "q_" + UUID.randomUUID().toString().substring(0, 8);
        String answer = r.answer();
        // the answer is built only from admin-sanitised metadata, but escape anyway before it is stored/broadcast
        String safeAnswer = InputSanitizer.text(answer);

        synchronized (rt.orderLock) {
            long seq = rt.chatSeq.incrementAndGet();
            buffers.addChat(rt.watchSpaceId, userId, ChatMessage.MsgType.AI_ANSWER, questionId, safeAnswer, seq, ts);
        }
        buffers.log(rt.watchSpaceId, userId, EventLog.LogType.AI_QUESTION,
                (r.grounded() ? "grounded " : "declined ") + String.join(",", r.sourceEvents()));
        long ms = Math.round((System.nanoTime() - t0) / 1_000_000.0);
        metrics.aiLatencyMs.record(ms);
        log.info("ai.ask corr={} ws={} user={} ts={} grounded={} sources={} latencyMs={}", rt.corr, rt.watchSpaceId, userId,
                ts, r.grounded(), r.sourceEvents(), ms);
        return new Dto.AskResult(questionId, safeAnswer, r.sourceEvents(), r.sources(), r.grounded(), Instant.now(), ms);
    }

    /** Payload for the room.ai.answer event. */
    public Map<String, Object> answerPayload(long userId, String question, Dto.AskResult a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questionId", a.questionId());
        m.put("userId", userId);
        m.put("question", InputSanitizer.text(question));
        m.put("answer", a.answer());
        m.put("sourceEvents", a.sourceEvents());
        m.put("grounded", a.grounded());
        return m;
    }

    public RoomRuntime liveRoom(long watchSpaceId, long userId) {
        RoomRuntime rt = registry.getOrCreate(watchSpaceId);
        if (!(rt.model.isMember(userId) || rt.model.hostUserId() == userId)) {
            throw ApiException.forbidden("Join the Watch Space before asking the co-pilot");
        }
        return rt;
    }
}
