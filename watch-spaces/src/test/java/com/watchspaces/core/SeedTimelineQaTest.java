package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs the grounded Q&A engine against the REAL seeded timeline ("The Pier"), the way the live system will, and checks
 * answers and citations. Also measures that the trivia planner fires every pushable marker of the seed exactly once.
 */
class SeedTimelineQaTest {

    private static List<KnowledgeEntry> load() throws Exception {
        try (InputStream in = SeedTimelineQaTest.class.getResourceAsStream("/seed/timeline-the-pier.json")) {
            assertNotNull(in);
            JsonNode root = new ObjectMapper().readTree(in);
            List<KnowledgeEntry> out = new ArrayList<>();
            int n = 0;
            for (JsonNode ev : root.get("events")) {
                n++;
                String type = ev.get("type").asText();
                if (type.equals("variation_point")) {
                    continue;
                }
                List<String> kws = new ArrayList<>();
                if (ev.has("keywords")) {
                    ev.get("keywords").forEach(k -> kws.add(k.asText()));
                }
                out.add(new KnowledgeEntry("evt_" + n, KnowledgeEntry.Type.valueOf(type.toUpperCase()), ev.get("ts").asInt(),
                        ev.has("endTs") ? ev.get("endTs").asInt() : null, ev.has("title") ? ev.get("title").asText() : "",
                        ev.get("text").asText(), kws));
            }
            return out;
        }
    }

    private static GroundedQa.Result ask(List<KnowledgeEntry> k, String q, int ts) {
        return GroundedQa.answer(k, q, ts, 2);
    }

    private static String titles(List<KnowledgeEntry> k, GroundedQa.Result r) {
        List<String> t = new ArrayList<>();
        for (String id : r.sourceEvents()) {
            for (KnowledgeEntry e : k) {
                if (e.id().equals(id)) {
                    t.add(e.title());
                }
            }
        }
        return t.toString();
    }

    @Test
    void whoQuestionsAtTheRightMomentCiteTheCharacterOnScreen() throws Exception {
        var k = load();
        var r = ask(k, "Who is the character that just walked in?", 270);
        assertTrue(r.grounded(), r.answer());
        assertTrue(titles(k, r).contains("Detective Rios"), titles(k, r));
        var r2 = ask(k, "Who is this woman?", 850);
        assertTrue(r2.grounded(), r2.answer());
        assertTrue(titles(k, r2).contains("Elena Vance"), titles(k, r2));
    }

    @Test
    void definitionsComeFromTheGlossary() throws Exception {
        var k = load();
        var r = ask(k, "What does alibi mean?", 400);
        assertTrue(r.grounded());
        assertTrue(titles(k, r).contains("Alibi"), titles(k, r));
        var r2 = ask(k, "What is a red herring?", 1000);
        assertTrue(titles(k, r2).contains("Red herring"), titles(k, r2));
    }

    @Test
    void behindTheScenesQuestionsUseTrivia() throws Exception {
        var k = load();
        var r = ask(k, "Where was this filmed?", 500);
        assertTrue(r.grounded());
        assertTrue(titles(k, r).contains("Location"), titles(k, r));
    }

    @Test
    void recapQuestionsUseScenesUpToNowOnly() throws Exception {
        var k = load();
        var r = ask(k, "What happened so far?", 320);
        assertTrue(r.grounded());
        String t = titles(k, r);
        assertFalse(t.contains("The Second Witness"), t);
        assertFalse(t.contains("The Sister Arrives"), t);
    }

    @Test
    void noSpoilersAndNoInventionOutsideTheMetadata() throws Exception {
        var k = load();
        // Elena Vance first appears at 800 s; at 100 s she must be invisible
        var early = ask(k, "Who is Elena Vance?", 100);
        assertFalse(early.grounded(), early.answer());
        assertEquals(GroundedQa.DECLINE, early.answer());
        assertEquals(GroundedQa.DECLINE, ask(k, "What is the capital of France?", 2000).answer());
        assertEquals(GroundedQa.DECLINE, ask(k, "Who is the murderer?", 2000).answer());
    }

    @Test
    void everyCitationRefersToARealEntryAtOrBeforeNow() throws Exception {
        var k = load();
        String[] questions = {"Who is Rios?", "What does cold case mean?", "Tell me about the soundtrack", "Who found the body?",
                "Where is the pier?", "Who is Marcus Hale?", "what did the dock worker see"};
        for (String q : questions) {
            for (int ts : new int[]{0, 60, 300, 900, 1500, 2300}) {
                var r = ask(k, q, ts);
                for (var s : r.sources()) {
                    assertTrue(s.ts() <= ts, q + " @" + ts + " cited a future entry " + s);
                }
                if (!r.grounded()) {
                    assertTrue(r.sourceEvents().isEmpty());
                }
            }
        }
    }

    @Test
    void trivialPushPlaythroughOfTheSeedFiresEachMarkerOnce() throws Exception {
        var k = load();
        long expected = k.stream().filter(e -> TriviaPlanner.shouldPush(e.type(), TriviaPlanner.Level.NORMAL)).count();
        java.util.Map<String, Integer> fired = new java.util.HashMap<>();
        double pos = 0;
        while (pos < 2400) {
            double next = pos + 0.25;
            for (KnowledgeEntry e : TriviaPlanner.due(k, pos, next, TriviaPlanner.Level.NORMAL, true, true)) {
                fired.merge(e.id(), 1, Integer::sum);
            }
            pos = next;
        }
        assertEquals(expected, fired.size());
        assertTrue(fired.values().stream().allMatch(c -> c == 1));
    }

    @Test
    void aRoomThatJustStartedAnswersFromTheOpeningSceneOnly() throws Exception {
        var k = load();
        // the integration test asks these at room position ~0-2 s
        var found = ask(k, "Where was the body found?", 2);
        assertTrue(found.grounded(), found.answer());
        assertTrue(titles(k, found).contains("The Discovery"), titles(k, found));
        var elena = ask(k, "Who is Elena Vance?", 2);
        assertFalse(elena.grounded(), elena.answer());
        assertTrue(elena.sourceEvents().isEmpty());
    }
}
