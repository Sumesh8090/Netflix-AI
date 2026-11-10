package com.watchspaces.ws;

import com.watchspaces.core.PlaybackStateMachine;
import com.watchspaces.core.RoomModel;
import com.watchspaces.entity.WatchSpace;
import com.watchspaces.core.RateLimiter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.socket.WebSocketSession;

/**
 * Live, in-memory state of one Watch Space. The authoritative playback position lives here (never in the DB);
 * only coarse summaries are persisted. Holds no DB connections.
 */
public final class RoomRuntime {
    public final long watchSpaceId;
    public final long titleId;
    public final int durationSeconds;
    /** Correlation id stamped on every log line for this watch session. */
    public final String corr = UUID.randomUUID().toString().substring(0, 8);

    public final PlaybackStateMachine playback;
    public final RoomModel model;

    public volatile WatchSpace.Verbosity verbosity;
    public volatile boolean triviaEnabled;
    public volatile boolean votingEnabled;

    /** sessionId -> thread-safe session wrapper. */
    public final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** userId -> that user's open session ids (a user may have several tabs). */
    public final Map<Long, Set<String>> userSessions = new ConcurrentHashMap<>();
    public final Map<Long, String> displayNames = new ConcurrentHashMap<>();
    /** Everyone who connected at least once during this session (used for the viewing record at the end). */
    public final Set<Long> everJoined = ConcurrentHashMap.newKeySet();
    public final Map<Long, String> locales = new ConcurrentHashMap<>();
    /** variationId -> open vote (in memory until closed, then persisted once). */
    public final Map<String, OpenVote> openVotes = new ConcurrentHashMap<>();
    public final Map<Long, ScheduledFuture<?>> leaveTimers = new ConcurrentHashMap<>();

    /** Guards "assign sequence number + broadcast" so every client sees chat in the same order. */
    public final Object orderLock = new Object();
    public final AtomicLong chatSeq;

    /** Guards lastTriviaPos against the trivia ticker racing with host seeks. */
    public final Object triviaLock = new Object();
    public double lastTriviaPos = 0;

    /** variationId -> winning option id, for late joiners and reconnects. */
    public final Map<String, String> appliedVariations = new ConcurrentHashMap<>();

    /** Abuse limits per user (sliding windows). */
    public final RateLimiter chatLimiter = new RateLimiter(10, 5_000);
    public final RateLimiter aiLimiter = new RateLimiter(5, 10_000);
    public final RateLimiter typingLimiter = new RateLimiter(20, 5_000);

    public final long createdAtMs = System.currentTimeMillis();
    /** 0 while anybody is connected or the room was never occupied; otherwise when the last connection closed. */
    public volatile long emptySinceMs = 0;

    public RoomRuntime(long watchSpaceId, long titleId, int durationSeconds, PlaybackStateMachine playback,
                       RoomModel model, WatchSpace.Verbosity verbosity, boolean triviaEnabled, boolean votingEnabled,
                       long initialChatSeq) {
        this.watchSpaceId = watchSpaceId;
        this.titleId = titleId;
        this.durationSeconds = durationSeconds;
        this.playback = playback;
        this.model = model;
        this.verbosity = verbosity;
        this.triviaEnabled = triviaEnabled;
        this.votingEnabled = votingEnabled;
        this.chatSeq = new AtomicLong(initialChatSeq);
    }

    public int sessionCount() {
        return sessions.size();
    }
}
