package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VariationRulesTest {
    private static final List<String> LOCALES = Arrays.asList("en", "hi", "es", "pt-br");
    private static final List<String> KEYS = List.of("a", "b", "c");

    @Test
    void localeMatchesExactThenLanguageElseOriginal() {
        assertEquals(1, VariationRules.pickForLocale(LOCALES, "hi"));
        assertEquals(1, VariationRules.pickForLocale(LOCALES, "hi-IN"));
        assertEquals(2, VariationRules.pickForLocale(LOCALES, "ES"));
        assertEquals(3, VariationRules.pickForLocale(LOCALES, "pt_BR"));
        assertEquals(0, VariationRules.pickForLocale(LOCALES, "fr"));
        assertEquals(0, VariationRules.pickForLocale(LOCALES, null));
        assertEquals(0, VariationRules.pickForLocale(LOCALES, "  "));
    }

    @Test
    void optionsWithoutALocaleAreNeverPickedByLocale() {
        assertEquals(0, VariationRules.pickForLocale(Arrays.asList(null, null), "hi"));
        // exact regional match wins over a language match that appears earlier
        assertEquals(2, VariationRules.pickForLocale(Arrays.asList("en", "pt", "pt-br"), "pt-BR"));
    }

    @Test
    void mostVotesWins() {
        assertEquals("b", VariationRules.winner(KEYS, List.of("b", "b", "a")));
        assertEquals("c", VariationRules.winner(KEYS, List.of("a", "c", "c", "c", "b")));
    }

    @Test
    void tiesGoToTheEarliestOptionAndNoVotesMeansTheOriginal() {
        assertEquals("a", VariationRules.winner(KEYS, List.of("a", "b")));
        assertEquals("b", VariationRules.winner(KEYS, List.of("c", "b", "c", "b")));
        assertEquals("a", VariationRules.winner(KEYS, List.of()));
    }

    @Test
    void tallyListsEveryOptionAndIgnoresUnknownKeys() {
        assertEquals(Map.of("a", 1, "b", 2, "c", 0), VariationRules.tally(KEYS, List.of("a", "b", "b", "zzz")));
        assertEquals(List.of("a", "b", "c"), List.copyOf(VariationRules.tally(KEYS, List.of()).keySet()));
    }

    @Test
    void aVariationPointWithNoOptionsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> VariationRules.winner(List.of(), List.of()));
    }

    @Test
    void crossedUsesTheSameRulesAsTrivia() {
        assertTrue(TriviaPlanner.crossed(10, 9.8, 10.05, true));
        assertFalse(TriviaPlanner.crossed(10, 10.0, 10.25, true));   // already fired on the previous tick
        assertFalse(TriviaPlanner.crossed(10, 9.8, 10.05, false));   // paused
        assertFalse(TriviaPlanner.crossed(10, 0, 20, true));         // a seek never fires markers
    }

    @Test
    void localeNormalisationRejectsGarbage() {
        assertEquals("hi", VariationRules.normalizeLocale(" HI "));
        assertEquals("pt-br", VariationRules.normalizeLocale("pt_BR"));
        assertNull(VariationRules.normalizeLocale("<script>"));
        assertNull(VariationRules.normalizeLocale(""));
        assertNull(VariationRules.normalizeLocale(null));
        assertNull(VariationRules.normalizeLocale("english-language-long"));
    }
}
