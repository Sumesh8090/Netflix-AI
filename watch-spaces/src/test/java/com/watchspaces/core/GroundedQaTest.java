package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import com.watchspaces.core.KnowledgeEntry.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroundedQaTest {
    private static final List<KnowledgeEntry> E = List.of(
            new KnowledgeEntry("evt_scene1", Type.SCENE, 60, 250, "The Discovery", "A body is found at the pier.", List.of("body", "pier", "found")),
            new KnowledgeEntry("evt_rios", Type.CHARACTER, 252, 1800, "Detective Rios", "The victim's former partner.", List.of("rios", "partner", "detective")),
            new KnowledgeEntry("evt_tomas", Type.CHARACTER, 420, 900, "Tomas Vale", "The victim's younger brother.", List.of("tomas", "brother")),
            new KnowledgeEntry("evt_alibi", Type.GLOSSARY, 300, null, "Alibi", "A claim of being elsewhere when a crime occurred.", List.of("alibi")),
            new KnowledgeEntry("evt_fact", Type.TRIVIA, 812, null, "Location", "This scene was filmed on location at a real harbour.", List.of("filmed", "location", "harbour")),
            new KnowledgeEntry("evt_late", Type.CHARACTER, 5000, null, "The Mastermind", "Secretly runs the smuggling ring.", List.of("mastermind", "smuggling")));

    private static GroundedQa.Result ask(String q, int ts) {
        return GroundedQa.answer(E, q, ts, 2);
    }

    @Test
    void whoQuestionReturnsTheCharacterOnScreenAndCitesIt() {
        GroundedQa.Result r = ask("Who is the detective that just walked in?", 1000);
        assertTrue(r.grounded());
        assertEquals("evt_rios", r.sourceEvents().get(0));
        assertTrue(r.answer().contains("Detective Rios"));
        assertTrue(r.answer().contains("00:04:12"));
        assertTrue(r.answer().contains("[Source:"));
    }

    @Test
    void definitionQuestionUsesGlossary() {
        GroundedQa.Result r = ask("What does alibi mean?", 1000);
        assertEquals(List.of("evt_alibi"), r.sourceEvents());
    }

    @Test
    void recapQuestionUsesSceneAndIsChronological() {
        GroundedQa.Result r = ask("What happened so far?", 1000);
        assertTrue(r.grounded());
        assertTrue(r.sourceEvents().contains("evt_scene1"));
    }

    @Test
    void triviaQuestionUsesTrivia() {
        assertEquals("evt_fact", ask("Where was this filmed?", 1000).sourceEvents().get(0));
    }

    @Test
    void neverUsesEntriesFromTheFuture() {
        GroundedQa.Result r = ask("Who is the mastermind behind the smuggling?", 1000);
        assertFalse(r.sourceEvents().contains("evt_late"));
        assertFalse(r.answer().contains("Secretly runs"));
        // once the story reaches it, it becomes available
        assertTrue(GroundedQa.answer(E, "Who is the mastermind behind the smuggling?", 6000, 2).sourceEvents().contains("evt_late"));
    }

    @Test
    void entriesBeforeTheirTimestampAreInvisibleEvenForExactKeywords() {
        GroundedQa.Result r = ask("Tell me about Tomas", 100);
        assertFalse(r.grounded());
        assertTrue(ask("Tell me about Tomas", 500).grounded());
    }

    @Test
    void declinesInsteadOfInventingWhenNothingRelevant() {
        for (String q : List.of("What is the capital of France?", "How tall is the Eiffel Tower", "asdf qwerty", "")) {
            GroundedQa.Result r = ask(q, 1000);
            assertFalse(r.grounded(), q);
            assertTrue(r.sourceEvents().isEmpty(), q);
            assertEquals(GroundedQa.DECLINE, r.answer(), q);
        }
    }

    @Test
    void nullQuestionDeclines() {
        assertFalse(GroundedQa.answer(E, null, 100, 2).grounded());
    }

    @Test
    void everyGroundedAnswerCitesOnlyRealEntries() {
        List<String> real = E.stream().map(KnowledgeEntry::id).toList();
        for (String q : List.of("who is rios", "what is an alibi", "recap please", "who is tomas", "where was it filmed")) {
            GroundedQa.Result r = ask(q, 1000);
            if (r.grounded()) {
                assertFalse(r.sourceEvents().isEmpty());
                for (String id : r.sourceEvents()) {
                    assertTrue(real.contains(id), id);
                }
                assertEquals(r.sourceEvents().size(), r.sources().size());
            }
        }
    }

    @Test
    void maxSourcesIsRespected() {
        assertTrue(GroundedQa.answer(E, "who is rios and tomas and the brother partner", 1000, 1).sourceEvents().size() <= 1);
    }

    @Test
    void answerContainsOnlyTextFromTheCitedEntries() {
        GroundedQa.Result r = ask("What does alibi mean?", 1000);
        assertTrue(r.answer().startsWith("Alibi: A claim of being elsewhere when a crime occurred."));
    }

    @Test
    void renderCardFormatsByType() {
        assertEquals("This scene was filmed on location at a real harbour.", GroundedQa.renderCard(E.get(4)));
        assertTrue(GroundedQa.renderCard(E.get(3)).startsWith("Alibi - "));
    }

    @Test
    void timeFormat() {
        assertEquals("00:04:12", TimeFmt.hms(252));
        assertEquals("01:01:01", TimeFmt.hms(3661));
    }

    @Test
    void recapIntentWordsDoNotMatchIncidentalText() {
        List<KnowledgeEntry> es = List.of(
                new KnowledgeEntry("s1", Type.SCENE, 10, 100, "Opening", "A boat leaves the harbour.", List.of("boat")),
                new KnowledgeEntry("g1", Type.GLOSSARY, 20, null, "Alibi", "Where a suspect says they were when it happened.", List.of("alibi")));
        GroundedQa.Result r = GroundedQa.answer(es, "What happened so far?", 500, 2);
        assertEquals(List.of("s1"), r.sourceEvents());
    }

    @Test
    void meaningIntentWordDoesNotMatchEveryGlossaryEntry() {
        List<KnowledgeEntry> es = List.of(
                new KnowledgeEntry("g1", Type.GLOSSARY, 20, null, "Alibi", "Where a suspect says they were.", List.of("alibi", "mean")));
        assertFalse(GroundedQa.answer(es, "What is the meaning of life", 500, 2).grounded());
        assertTrue(GroundedQa.answer(es, "What does alibi mean", 500, 2).grounded());
    }
}
