package com.watchspaces.service;

import com.watchspaces.entity.Interaction;
import com.watchspaces.entity.WatchSpace;
import com.watchspaces.repo.InteractionRepository;
import com.watchspaces.repo.WatchSpaceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    public record Watched(long titleId, String titleName, int watchedSeconds, boolean completed, Integer rating, Instant at) { }

    public record RoomRef(long watchSpaceId, String name, String titleName, String status, String inviteCode,
                          boolean youAreHost, Instant createdAt) { }

    public record PastSession(long watchSpaceId, String name, String titleName, Instant endedAt, Long durationSeconds,
                              Integer peakConcurrentParticipants, Integer aiQuestions, Integer triviaCards, Integer chatMessages) { }

    public record Dashboard(List<Watched> recentlyWatched, List<RoomRef> upcoming, List<RoomRef> liveNow,
                            List<PastSession> pastSessions, List<RecommendationService.RecView> recommendedForYou) { }

    private final InteractionRepository interactions;
    private final WatchSpaceRepository watchSpaces;
    private final RecommendationService recs;

    public DashboardService(InteractionRepository interactions, WatchSpaceRepository watchSpaces, RecommendationService recs) {
        this.interactions = interactions;
        this.watchSpaces = watchSpaces;
        this.recs = recs;
    }

    @Transactional(readOnly = true)
    public Dashboard forUser(long userId) {
        List<Watched> recent = new ArrayList<>();
        for (Interaction i : interactions.findByUser_IdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 10))) {
            recent.add(new Watched(i.title.id, i.title.name, i.watchedSeconds, i.completed, i.rating, i.createdAt));
        }
        List<RoomRef> upcoming = new ArrayList<>();
        List<RoomRef> live = new ArrayList<>();
        List<PastSession> past = new ArrayList<>();
        for (WatchSpace w : watchSpaces.findForUser(userId)) {
            boolean host = w.host.id == userId;
            switch (w.status) {
                case SCHEDULED -> upcoming.add(ref(w, host));
                case LIVE -> live.add(ref(w, host));
                case ENDED -> past.add(new PastSession(w.id, w.name, w.title.name, w.endedAt, w.snapDurationSeconds,
                        w.snapPeakParticipants, w.snapAiQuestions, w.snapTriviaCards, w.snapChatMessages));
            }
        }
        return new Dashboard(recent, upcoming, live, past, recs.forUser(userId, 5));
    }

    private static RoomRef ref(WatchSpace w, boolean host) {
        return new RoomRef(w.id, w.name, w.title.name, w.status.name().toLowerCase(), w.inviteCode, host, w.createdAt);
    }
}
