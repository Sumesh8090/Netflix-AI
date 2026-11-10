package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChatRulesTest {
    @Test
    void emptyAndOversizedBodiesAreRejected() {
        assertEquals(ChatRules.Problem.EMPTY, ChatRules.check(null));
        assertEquals(ChatRules.Problem.EMPTY, ChatRules.check("   "));
        assertEquals(ChatRules.Problem.TOO_LONG, ChatRules.check("x".repeat(501)));
        assertNull(ChatRules.check("x".repeat(500)));
        assertNull(ChatRules.check("hello"));
    }

    @Test
    void onlyWhitelistedReactionsPass() {
        assertTrue(ChatRules.isReaction("🔥"));
        assertTrue(ChatRules.isReaction("❤️"));
        assertFalse(ChatRules.isReaction("<script>"));
        assertFalse(ChatRules.isReaction(null));
        assertFalse(ChatRules.isReaction(""));
    }
}
