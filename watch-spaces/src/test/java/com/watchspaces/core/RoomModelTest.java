package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import com.watchspaces.core.PlaybackStateMachine.Command;
import com.watchspaces.core.PlaybackStateMachine.State;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoomModelTest {
    private static final long GRACE = 15_000;
    private AtomicLong clock;
    private PlaybackStateMachine playback;
    private RoomModel room;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0);
        playback = new PlaybackStateMachine(100, clock::get);
        room = new RoomModel(1, 1, 3, playback);
        room.join(1, "host", 0);
        room.join(2, "bea", 10);
        room.join(3, "cal", 20);
    }

    private static String codeOf(Runnable r) {
        try {
            r.run();
        } catch (RoomModel.RoomException e) {
            return e.code();
        }
        return "NONE";
    }

    @Test
    void capacityIsEnforced() {
        assertEquals("ROOM_FULL", codeOf(() -> room.join(4, "dan", 30)));
    }

    @Test
    void lockedRoomRejectsNewcomersButNotReturningMembers() {
        room.setLocked(1, true);
        assertTrue(room.isLocked());
        assertEquals("ROOM_LOCKED", codeOf(() -> room.join(4, "dan", 30)));
        room.markDisconnected(2, 100);
        assertFalse(room.join(2, "bea", 200).firstJoin());
    }

    @Test
    void onlyHostCanModerate() {
        assertEquals("FORBIDDEN", codeOf(() -> room.mute(2, 3, true)));
        assertEquals("FORBIDDEN", codeOf(() -> room.remove(2, 3)));
        assertEquals("FORBIDDEN", codeOf(() -> room.setLocked(2, true)));
        assertEquals("FORBIDDEN", codeOf(() -> room.transferHost(2, 3)));
    }

    @Test
    void removedUsersCannotRejoin() {
        room.remove(1, 3);
        assertEquals("REMOVED", codeOf(() -> room.join(3, "cal", 100)));
    }

    @Test
    void mutedUsersCannotChat() {
        room.mute(1, 2, true);
        assertFalse(room.canChat(2));
        assertTrue(room.canChat(3));
    }

    @Test
    void onlyConnectedHostMayIssuePlayback() {
        assertTrue(room.canIssuePlayback(1));
        assertFalse(room.canIssuePlayback(2));
        room.markDisconnected(1, 100);
        assertFalse(room.canIssuePlayback(1));
    }

    @Test
    void reconnectingDoesNotCountAsANewJoin() {
        room.markDisconnected(2, 100);
        assertEquals(2, room.connectedCount());
        var again = room.join(2, "bea", 200);
        assertFalse(again.firstJoin());
        assertEquals(3, again.participantCount());
    }

    @Test
    void hostDisconnectPausesPlaybackImmediately() {
        playback.apply(Command.PLAY, 0.0);
        assertTrue(room.markDisconnected(1, 100));
        assertEquals(State.PAUSED, playback.state());
        assertTrue(room.isHostDisconnected());
    }

    @Test
    void hostReturningWithinGraceKeepsTheRoleAndNobodyIsPromoted() {
        room.markDisconnected(1, 1_000);
        assertEquals(Optional.empty(), room.promoteIfHostGone(5_000, GRACE));
        room.join(1, "host", 6_000);
        assertFalse(room.isHostDisconnected());
        assertEquals(1, room.hostUserId());
        assertEquals(Optional.empty(), room.promoteIfHostGone(60_000, GRACE));
    }

    @Test
    void afterGraceTheEarliestJoinedConnectedMemberIsPromoted() {
        room.markDisconnected(1, 1_000);
        assertEquals(Optional.empty(), room.promoteIfHostGone(1_000 + GRACE - 1, GRACE));
        assertEquals(Optional.of(2L), room.promoteIfHostGone(1_000 + GRACE, GRACE));
        assertEquals(2, room.hostUserId());
        assertTrue(room.canIssuePlayback(2));
        assertFalse(room.isMember(1));
    }

    @Test
    void promotionSkipsMembersWhoAreAlsoDisconnected() {
        room.markDisconnected(2, 500);
        room.markDisconnected(1, 1_000);
        assertEquals(Optional.of(3L), room.promoteIfHostGone(100_000, GRACE));
    }

    @Test
    void roomWithNobodyLeftStaysHostDisconnected() {
        room.markDisconnected(2, 1);
        room.markDisconnected(3, 1);
        room.markDisconnected(1, 2);
        assertEquals(Optional.empty(), room.promoteIfHostGone(100_000, GRACE));
        assertTrue(room.isHostDisconnected());
    }

    @Test
    void peakConcurrencyIsTracked() {
        room.markDisconnected(2, 1);
        room.markDisconnected(3, 1);
        assertEquals(3, room.peakConnected());
    }
}
