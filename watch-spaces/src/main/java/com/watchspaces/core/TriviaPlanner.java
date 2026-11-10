package com.watchspaces.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which timeline cards the AI co-pilot should push to a room between two ticks. Pure function.
 *
 * Rules:
 *  - Only while the room is PLAYING and the host enabled trivia.
 *  - A card is due when {@code from < ts <= to}: each marker fires exactly once per pass, on the first tick at or after
 *    its timestamp, so the delay is at most one tick.
 *  - A seek (position moved backwards, or forwards by more than {@value #JUMP_THRESHOLD_SECONDS} s since the last tick)
 *    fires nothing; the caller re-bases its cursor so skipped markers are not replayed.
 *  - What is pushed depends on verbosity: MINIMAL = trivia; NORMAL = trivia + glossary; CHATTY = + characters.
 *    Scenes are never pushed (they only feed question answering).
 */
public final class TriviaPlanner {

    public enum Level { MINIMAL, NORMAL, CHATTY }

    public static final double JUMP_THRESHOLD_SECONDS = 5.0;

    private TriviaPlanner() { }

    public static boolean shouldPush(KnowledgeEntry.Type type, Level level) {
        return switch (type) {
            case TRIVIA -> true;
            case GLOSSARY -> level != Level.MINIMAL;
            case CHARACTER -> level == Level.CHATTY;
            case SCENE -> false;
        };
    }

    public static boolean isJump(double from, double to) {
        return to < from || to - from > JUMP_THRESHOLD_SECONDS;
    }

    /** True when a marker at {@code ts} was crossed by normal playback between two ticks (same rules as {@link #due}). */
    public static boolean crossed(int ts, double from, double to, boolean playing) {
        return playing && !isJump(from, to) && ts > from && ts <= to;
    }

    public static List<KnowledgeEntry> due(List<KnowledgeEntry> entries, double from, double to, Level level,
                                           boolean enabled, boolean playing) {
        List<KnowledgeEntry> out = new ArrayList<>();
        if (!enabled || !playing || isJump(from, to)) {
            return out;
        }
        for (KnowledgeEntry e : entries) {
            if (e.startTs() > from && e.startTs() <= to && shouldPush(e.type(), level)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingInt(KnowledgeEntry::startTs).thenComparing(KnowledgeEntry::id));
        return out;
    }
}
