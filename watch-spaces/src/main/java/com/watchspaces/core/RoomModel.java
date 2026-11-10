package com.watchspaces.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory, socket-free model of one Watch Space: membership, capacity, lock, mute/ban, host role
 * and the deterministic host-disconnect policy. All time is passed in, so it is trivially testable.
 *
 * Host-disconnect policy (deterministic):
 *  1. The moment the host's last connection drops the room enters HOST_DISCONNECTED and playback is paused.
 *  2. If the host reconnects within {@code hostGraceMs} the room returns to normal (playback stays paused;
 *     the host resumes it explicitly).
 *  3. Otherwise {@link #promoteIfHostGone} hands the host role to the earliest-joined, currently connected
 *     member (ties broken by lowest user id). If nobody is connected the room stays HOST_DISCONNECTED.
 */
public final class RoomModel {

    public static class RoomException extends RuntimeException {
        private final String code;

        public RoomException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final class Member {
        public final long userId;
        public final String displayName;
        public final long joinedAtMs;
        public boolean connected = true;
        public boolean muted = false;

        Member(long userId, String displayName, long joinedAtMs) {
            this.userId = userId;
            this.displayName = displayName;
            this.joinedAtMs = joinedAtMs;
        }
    }

    public record JoinResult(boolean firstJoin, int participantCount) { }

    private final long watchSpaceId;
    private final int maxParticipants;
    private final PlaybackStateMachine playback;
    private final Map<Long, Member> members = new LinkedHashMap<>();
    private final Set<Long> banned = new HashSet<>();

    private long hostUserId;
    private boolean locked;
    private boolean hostDisconnected;
    private long hostDisconnectedAtMs;
    private int peakConnected;

    public RoomModel(long watchSpaceId, long hostUserId, int maxParticipants, PlaybackStateMachine playback) {
        this.watchSpaceId = watchSpaceId;
        this.hostUserId = hostUserId;
        this.maxParticipants = maxParticipants;
        this.playback = playback;
    }

    // --- membership ----------------------------------------------------------------------------

    /** Registers a connection for a user. Re-connecting members never trigger a duplicate "joined". */
    public synchronized JoinResult join(long userId, String displayName, long nowMs) {
        if (banned.contains(userId)) {
            throw new RoomException("REMOVED", "You were removed from this Watch Space");
        }
        Member existing = members.get(userId);
        if (existing != null) {
            existing.connected = true;
            if (userId == hostUserId && hostDisconnected) {
                hostDisconnected = false;
            }
            updatePeak();
            return new JoinResult(false, connectedCount());
        }
        if (locked && userId != hostUserId) {
            throw new RoomException("ROOM_LOCKED", "This Watch Space is locked");
        }
        if (members.size() >= maxParticipants) {
            throw new RoomException("ROOM_FULL", "This Watch Space is full");
        }
        members.put(userId, new Member(userId, displayName, nowMs));
        updatePeak();
        return new JoinResult(true, connectedCount());
    }

    /**
     * Marks a user's last connection as dropped. The member stays in the room (reconnect grace) until
     * {@link #finalizeLeave}. Returns true if this was the host and the room entered HOST_DISCONNECTED.
     */
    public synchronized boolean markDisconnected(long userId, long nowMs) {
        Member m = members.get(userId);
        if (m == null) {
            return false;
        }
        m.connected = false;
        if (userId == hostUserId && !hostDisconnected) {
            hostDisconnected = true;
            hostDisconnectedAtMs = nowMs;
            try {
                playback.apply(PlaybackStateMachine.Command.PAUSE, null);
            } catch (PlaybackStateMachine.IllegalTransitionException ignored) {
                // already paused
            }
            return true;
        }
        return false;
    }

    /** Removes a member whose reconnect grace expired. Returns true if they were removed. */
    public synchronized boolean finalizeLeave(long userId) {
        Member m = members.get(userId);
        if (m != null && !m.connected && userId != hostUserId) {
            members.remove(userId);
            return true;
        }
        if (m != null && !m.connected) {
            // host keeps their seat; handled by host-disconnect policy
            return false;
        }
        return false;
    }

    /** Deterministic failover. Returns the new host id if the host role moved. */
    public synchronized Optional<Long> promoteIfHostGone(long nowMs, long hostGraceMs) {
        if (!hostDisconnected || nowMs - hostDisconnectedAtMs < hostGraceMs) {
            return Optional.empty();
        }
        Optional<Member> next = members.values().stream()
                .filter(m -> m.connected && m.userId != hostUserId)
                .min(Comparator.<Member>comparingLong(m -> m.joinedAtMs).thenComparingLong(m -> m.userId));
        if (next.isEmpty()) {
            return Optional.empty();
        }
        long oldHost = hostUserId;
        hostUserId = next.get().userId;
        hostDisconnected = false;
        members.remove(oldHost); // old host's seat is released
        return Optional.of(hostUserId);
    }

    // --- host moderation -------------------------------------------------------------------------

    public synchronized void mute(long actorId, long targetId, boolean muted) {
        requireHost(actorId);
        Member m = requireMember(targetId);
        if (targetId == hostUserId) {
            throw new RoomException("INVALID_TARGET", "The host cannot be muted");
        }
        m.muted = muted;
    }

    public synchronized void remove(long actorId, long targetId) {
        requireHost(actorId);
        if (targetId == hostUserId) {
            throw new RoomException("INVALID_TARGET", "The host cannot be removed");
        }
        requireMember(targetId);
        members.remove(targetId);
        banned.add(targetId);
    }

    public synchronized void setLocked(long actorId, boolean locked) {
        requireHost(actorId);
        this.locked = locked;
    }

    public synchronized void transferHost(long actorId, long targetId) {
        requireHost(actorId);
        Member target = requireMember(targetId);
        if (!target.connected) {
            throw new RoomException("INVALID_TARGET", "Target is not connected");
        }
        hostUserId = targetId;
        hostDisconnected = false;
    }

    // --- queries -----------------------------------------------------------------------------------

    public synchronized boolean canIssuePlayback(long userId) {
        return userId == hostUserId && !hostDisconnected;
    }

    public synchronized boolean canChat(long userId) {
        Member m = members.get(userId);
        return m != null && !m.muted;
    }

    public synchronized boolean isMember(long userId) {
        return members.containsKey(userId);
    }

    public synchronized int connectedCount() {
        int c = 0;
        for (Member m : members.values()) {
            if (m.connected) {
                c++;
            }
        }
        return c;
    }

    public synchronized List<Member> connectedMembers() {
        List<Member> out = new ArrayList<>();
        for (Member m : members.values()) {
            if (m.connected) {
                out.add(m);
            }
        }
        return out;
    }

    public synchronized int peakConnected() {
        return peakConnected;
    }

    public synchronized long hostUserId() {
        return hostUserId;
    }

    public synchronized boolean isHostDisconnected() {
        return hostDisconnected;
    }

    public synchronized boolean isLocked() {
        return locked;
    }

    public long watchSpaceId() {
        return watchSpaceId;
    }

    public PlaybackStateMachine playback() {
        return playback;
    }

    // --- internals -----------------------------------------------------------------------------------

    private void updatePeak() {
        peakConnected = Math.max(peakConnected, connectedCount());
    }

    private void requireHost(long actorId) {
        if (actorId != hostUserId) {
            throw new RoomException("FORBIDDEN", "Only the host can do this");
        }
    }

    private Member requireMember(long userId) {
        Member m = members.get(userId);
        if (m == null) {
            throw new RoomException("NOT_FOUND", "User is not in this Watch Space");
        }
        return m;
    }
}
