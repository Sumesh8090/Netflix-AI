package com.watchspaces.core;

import java.util.Set;

/** Validation rules for chat input. Pure, so they are unit-tested without a socket. */
public final class ChatRules {
    public static final int MAX_BODY_CHARS = 500; // escaping can grow text 5x; 500 * 5 fits the 4000-char column

    /** Reactions are restricted to a fixed set, so nothing user-controlled is ever broadcast as a "reaction". */
    public static final Set<String> REACTIONS = Set.of("👍", "❤️", "😂", "😮", "😢", "👏", "🔥", "🎉");

    public enum Problem { EMPTY, TOO_LONG }

    private ChatRules() { }

    /** Returns null when the (already trimmed) body is acceptable. */
    public static Problem check(String body) {
        if (body == null || body.isBlank()) {
            return Problem.EMPTY;
        }
        if (body.length() > MAX_BODY_CHARS) {
            return Problem.TOO_LONG;
        }
        return null;
    }

    public static boolean isReaction(String emoji) {
        return emoji != null && REACTIONS.contains(emoji);
    }
}
