package com.watchspaces.service;

import com.watchspaces.entity.*;
import com.watchspaces.repo.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists a finished vote (room_variations + variation_votes) in one transaction. */
@Service
public class VariationStore {
    private final RoomVariationRepository roomVariations;
    private final VariationVoteRepository votes;
    private final WatchSpaceRepository watchSpaces;
    private final TimelineEventRepository events;
    private final UserRepository users;

    public VariationStore(RoomVariationRepository roomVariations, VariationVoteRepository votes,
                          WatchSpaceRepository watchSpaces, TimelineEventRepository events, UserRepository users) {
        this.roomVariations = roomVariations;
        this.votes = votes;
        this.watchSpaces = watchSpaces;
        this.events = events;
        this.users = users;
    }

    @Transactional
    public void save(long watchSpaceId, long eventId, Instant opened, Instant closes, String winner, Map<Long, String> cast) {
        RoomVariation rv = roomVariations.findByWatchSpace_IdAndEvent_Id(watchSpaceId, eventId).orElseGet(RoomVariation::new);
        rv.watchSpace = watchSpaces.getReferenceById(watchSpaceId);
        rv.event = events.getReferenceById(eventId);
        rv.openedAt = opened;
        rv.closesAt = closes;
        rv.status = RoomVariation.Status.CLOSED;
        rv.winningOptionKey = winner;
        rv = roomVariations.save(rv);
        for (Map.Entry<Long, String> e : cast.entrySet()) {
            VariationVote v = votes.findByRoomVariation_IdAndUser_Id(rv.id, e.getKey()).orElseGet(VariationVote::new);
            v.roomVariation = rv;
            v.user = users.getReferenceById(e.getKey());
            v.optionKey = e.getValue();
            votes.save(v);
        }
    }
}
