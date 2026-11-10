package com.watchspaces.service;

import com.watchspaces.entity.*;
import com.watchspaces.repo.*;
import com.watchspaces.security.AuthUser;
import com.watchspaces.web.ApiException;
import com.watchspaces.web.Dto;
import com.watchspaces.util.InputSanitizer;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Watch Space lifecycle that is persisted: create, join-by-code, access checks, history, analytics. */
@Service
public class SessionService {

    public record CreateRequest(long titleId, String name, Integer maxParticipants, String aiVerbosity,
                                Boolean votingEnabled, Boolean triviaEnabled, String privacy) { }

    public record Analytics(long watchSpaceId, String status, long durationSeconds, int peakConcurrentParticipants,
                            int aiQuestions, int triviaCards, int chatMessages, Map<String, Long> eventLogCounts,
                            long chatRowsInDatabase, boolean reconciled, boolean snapshot) { }

    public static final int MAX_ROOM_CAPACITY = 50;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WatchSpaceRepository watchSpaces;
    private final WatchSpaceParticipantRepository participants;
    private final TitleRepository titles;
    private final UserRepository users;
    private final ChatMessageRepository chat;
    private final EventLogRepository eventLog;
    private final PersistenceBuffers buffers;

    public SessionService(WatchSpaceRepository watchSpaces, WatchSpaceParticipantRepository participants,
                          TitleRepository titles, UserRepository users, ChatMessageRepository chat,
                          EventLogRepository eventLog, PersistenceBuffers buffers) {
        this.watchSpaces = watchSpaces;
        this.participants = participants;
        this.titles = titles;
        this.users = users;
        this.chat = chat;
        this.eventLog = eventLog;
        this.buffers = buffers;
    }

    @Transactional
    public WatchSpace create(AuthUser host, CreateRequest r) {
        Title title = titles.findById(r.titleId()).orElseThrow(() -> ApiException.notFound("Title"));
        User hostUser = users.findById(host.id()).orElseThrow(() -> ApiException.notFound("User"));
        WatchSpace w = new WatchSpace();
        w.title = title;
        w.host = hostUser;
        w.name = r.name() == null || r.name().isBlank() ? hostUser.displayName + "'s Watch Space"
                : InputSanitizer.plain(r.name());
        int max = r.maxParticipants() == null ? 25 : r.maxParticipants();
        if (max < 2 || max > MAX_ROOM_CAPACITY) {
            throw ApiException.badRequest("maxParticipants must be between 2 and " + MAX_ROOM_CAPACITY);
        }
        w.maxParticipants = max;
        w.aiVerbosity = parseEnum(WatchSpace.Verbosity.class, r.aiVerbosity(), WatchSpace.Verbosity.NORMAL, "aiVerbosity");
        w.privacy = parseEnum(WatchSpace.Privacy.class, r.privacy(), WatchSpace.Privacy.INVITE_ONLY, "privacy");
        w.votingEnabled = Boolean.TRUE.equals(r.votingEnabled());
        w.triviaEnabled = r.triviaEnabled() == null || r.triviaEnabled();
        w.inviteCode = newInviteCode();
        return watchSpaces.save(w);
    }

    /** Resolves an invite code and records that the caller may see this space. Does not consume a seat. */
    @Transactional
    public WatchSpace joinByCode(AuthUser user, String code) {
        WatchSpace w = watchSpaces.findByInviteCode(code == null ? "" : code.trim().toUpperCase())
                .orElseThrow(() -> ApiException.notFound("Invite code"));
        if (w.status == WatchSpace.Status.ENDED) {
            throw new ApiException(org.springframework.http.HttpStatus.GONE, "SESSION_ENDED", "This Watch Space has ended");
        }
        recordParticipant(w.id, user.id());
        return w;
    }

    @Transactional
    public void recordParticipant(long watchSpaceId, long userId) {
        if (participants.findByWatchSpace_IdAndUser_Id(watchSpaceId, userId).isEmpty()) {
            WatchSpaceParticipant p = new WatchSpaceParticipant();
            p.watchSpace = watchSpaces.getReferenceById(watchSpaceId);
            p.user = users.getReferenceById(userId);
            participants.save(p);
        }
    }

    @Transactional
    public void markLeft(long watchSpaceId, long userId) {
        participants.findByWatchSpace_IdAndUser_Id(watchSpaceId, userId).ifPresent(p -> {
            p.leftAt = Instant.now();
            participants.save(p);
        });
    }

    @Transactional
    public void markLive(long watchSpaceId) {
        watchSpaces.findById(watchSpaceId).ifPresent(w -> {
            if (w.status == WatchSpace.Status.SCHEDULED) {
                w.status = WatchSpace.Status.LIVE;
                w.startedAt = Instant.now();
                watchSpaces.save(w);
            }
        });
    }

    @Transactional
    public void changeHost(long watchSpaceId, long newHostId) {
        watchSpaces.findById(watchSpaceId).ifPresent(w -> {
            w.host = users.getReferenceById(newHostId);
            watchSpaces.save(w);
        });
    }

    @Transactional(readOnly = true)
    public WatchSpace get(long id) {
        return watchSpaces.findById(id).orElseThrow(() -> ApiException.notFound("Watch Space"));
    }

    /** Host, admin and anyone who joined by code may read the space; PUBLIC_LINK spaces are readable by any signed-in user. */
    @Transactional(readOnly = true)
    public boolean mayView(WatchSpace w, AuthUser u) {
        return u.isAdmin() || w.host.id == u.id() || w.privacy == WatchSpace.Privacy.PUBLIC_LINK
                || participants.findByWatchSpace_IdAndUser_Id(w.id, u.id()).isPresent();
    }

    @Transactional(readOnly = true)
    public Dto.WatchSpaceView view(long id, AuthUser u) {
        WatchSpace w = get(id);
        if (!mayView(w, u)) {
            throw ApiException.forbidden("You have not joined this Watch Space");
        }
        boolean privileged = u.isAdmin() || w.host.id == u.id()
                || participants.findByWatchSpace_IdAndUser_Id(w.id, u.id()).isPresent();
        return Dto.WatchSpaceView.of(w, privileged);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(long id, AuthUser u, Long beforeSeq, int limit) {
        WatchSpace w = get(id);
        if (!mayView(w, u)) {
            throw ApiException.forbidden("You have not joined this Watch Space");
        }
        buffers.flushAll();
        int n = Math.max(1, Math.min(limit, 200));
        List<ChatMessage> rows = beforeSeq == null
                ? chat.findByWatchSpace_IdOrderBySeqDesc(id, PageRequest.of(0, n))
                : chat.findByWatchSpace_IdAndSeqLessThanOrderBySeqDesc(id, beforeSeq, PageRequest.of(0, n));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            out.add(chatView(rows.get(i)));
        }
        return out;
    }

    /** Last n messages, oldest first, for the join snapshot (no access check: caller already admitted the user). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentChat(long id, int n) {
        buffers.flushAll();
        List<ChatMessage> rows = chat.findByWatchSpace_IdOrderBySeqDesc(id, PageRequest.of(0, n));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            out.add(chatView(rows.get(i)));
        }
        return out;
    }

    public static Map<String, Object> chatView(ChatMessage m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("messageId", m.messageUuid);
        v.put("seq", m.seq);
        v.put("type", m.msgType.name().toLowerCase());
        v.put("userId", m.user == null ? null : m.user.id);
        v.put("body", m.body);
        v.put("tsSeconds", m.tsSeconds);
        v.put("createdAt", m.createdAt);
        return v;
    }

    // ---- analytics -----------------------------------------------------------------------------------

    /**
     * Computed from the append-only event log: peak concurrency is replayed from JOIN/LEAVE rows in id order, counts are
     * counts of rows. {@code reconciled} cross-checks the log's CHAT count against the persisted chat_messages table.
     */
    @Transactional(readOnly = true)
    public Analytics analytics(long id, AuthUser u) {
        WatchSpace w = get(id);
        if (!(u.isAdmin() || w.host.id == u.id())) {
            throw ApiException.forbidden("Only the host or an admin can view session analytics");
        }
        buffers.flushAll();
        List<EventLog> rows = eventLog.findByWatchSpace_IdOrderByIdAsc(id);
        Map<String, Long> counts = new LinkedHashMap<>();
        int current = 0;
        int peak = 0;
        for (EventLog e : rows) {
            counts.merge(e.type.name(), 1L, Long::sum);
            if (e.type == EventLog.LogType.JOIN) {
                peak = Math.max(peak, ++current);
            } else if (e.type == EventLog.LogType.LEAVE) {
                current = Math.max(0, current - 1);
            }
        }
        long chatRows = chat.countByWatchSpace_IdAndMsgType(id, ChatMessage.MsgType.CHAT);
        long chatLogged = counts.getOrDefault("CHAT", 0L);
        Instant end = w.endedAt != null ? w.endedAt : Instant.now();
        long duration = w.startedAt == null ? 0 : Duration.between(w.startedAt, end).getSeconds();
        return new Analytics(id, w.status.name().toLowerCase(), duration, peak,
                counts.getOrDefault("AI_QUESTION", 0L).intValue(), counts.getOrDefault("TRIVIA", 0L).intValue(),
                (int) chatLogged, counts, chatRows, chatRows == chatLogged, w.status == WatchSpace.Status.ENDED);
    }

    /** Called when a session ends: freezes the analytics snapshot on the watch_spaces row. */
    @Transactional
    public Analytics finish(long id) {
        WatchSpace w = get(id);
        if (w.status == WatchSpace.Status.ENDED) {
            return analytics(id, new AuthUser(w.host.id, w.host.email, Role.ADMIN));
        }
        w.status = WatchSpace.Status.ENDED;
        w.endedAt = Instant.now();
        watchSpaces.save(w);
        Analytics a = analytics(id, new AuthUser(w.host.id, w.host.email, Role.ADMIN));
        w.snapPeakParticipants = a.peakConcurrentParticipants();
        w.snapAiQuestions = a.aiQuestions();
        w.snapTriviaCards = a.triviaCards();
        w.snapChatMessages = a.chatMessages();
        w.snapDurationSeconds = a.durationSeconds();
        watchSpaces.save(w);
        return a;
    }

    @Transactional(readOnly = true)
    public List<WatchSpaceParticipant> participantsOf(long id) {
        return participants.findByWatchSpace_Id(id);
    }

    private String newInviteCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder("NX-");
            for (int i = 0; i < 4; i++) {
                sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            if (!watchSpaces.existsByInviteCode(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Could not allocate an invite code");
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E dflt, String field) {
        if (value == null || value.isBlank()) {
            return dflt;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid " + field + ": " + value);
        }
    }
}
