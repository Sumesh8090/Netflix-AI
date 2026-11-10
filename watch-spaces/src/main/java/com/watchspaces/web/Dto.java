package com.watchspaces.web;

import com.watchspaces.core.GroundedQa;
import com.watchspaces.entity.Title;
import com.watchspaces.entity.TimelineEvent;
import com.watchspaces.entity.User;
import com.watchspaces.entity.WatchSpace;
import java.time.Instant;
import java.util.List;

/** Response shapes shared by more than one controller. Request bodies live next to their controllers. */
public final class Dto {
    private Dto() { }

    public record UserView(long id, String email, String displayName, String role, String subtitleLocale) {
        public static UserView of(User u) {
            return new UserView(u.id, u.email, u.displayName, u.role.name(), u.subtitleLocale);
        }
    }

    public record TitleView(long id, String name, int durationSeconds, List<String> genres, String videoAssetUrl) {
        public static TitleView of(Title t) {
            return new TitleView(t.id, t.name, t.durationSeconds, List.copyOf(t.genreSet()), t.videoAssetUrl);
        }
    }

    public record OptionView(String id, String label, String locale) { }

    /** Wire shape of a timeline event. type is one of scene|character|trivia|glossary|variationPoint. */
    public record TimelineEventView(String id, int ts, Integer endTs, String type, String title, String text,
                                    List<String> keywords, String variationId, String kind, List<OptionView> options) {
        public static String typeName(TimelineEvent.EventType t) {
            return switch (t) {
                case SCENE -> "scene";
                case CHARACTER -> "character";
                case TRIVIA -> "trivia";
                case GLOSSARY -> "glossary";
                case VARIATION_POINT -> "variationPoint";
            };
        }

        public static TimelineEventView of(TimelineEvent e) {
            List<String> kws = e.keywords == null || e.keywords.isBlank() ? List.of()
                    : java.util.Arrays.stream(e.keywords.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            boolean variation = e.eventType == TimelineEvent.EventType.VARIATION_POINT;
            List<OptionView> opts = variation
                    ? e.options.stream().map(o -> new OptionView(o.optionKey, o.label, o.locale)).toList() : null;
            return new TimelineEventView("evt_" + e.id, e.tsSeconds, e.endTsSeconds, typeName(e.eventType), e.name, e.body,
                    kws.isEmpty() ? null : kws, e.variationId,
                    e.variationKind == null ? null : e.variationKind.name().toLowerCase(), opts);
        }
    }

    public record WatchSpaceView(long watchSpaceId, String name, String inviteCode, String status, long hostUserId,
                                 long titleId, String titleName, String privacy, int maxParticipants,
                                 String aiVerbosity, boolean votingEnabled, boolean triviaEnabled,
                                 Instant createdAt, Instant startedAt, Instant endedAt) {
        public static WatchSpaceView of(WatchSpace w, boolean includeInviteCode) {
            return new WatchSpaceView(w.id, w.name, includeInviteCode ? w.inviteCode : null, w.status.name().toLowerCase(),
                    w.host.id, w.title.id, w.title.name, w.privacy.name().toLowerCase(), w.maxParticipants,
                    w.aiVerbosity.name().toLowerCase(), w.votingEnabled, w.triviaEnabled, w.createdAt, w.startedAt, w.endedAt);
        }
    }

    public record AskResult(String questionId, String answer, List<String> sourceEvents, List<GroundedQa.Source> sources,
                            boolean grounded, Instant generatedAt, long latencyMs) { }
}
