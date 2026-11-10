package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import com.watchspaces.core.KnowledgeEntry.Type;
import com.watchspaces.core.TriviaPlanner.Level;
import java.util.List;
import org.junit.jupiter.api.Test;

class TriviaPlannerTest {
    private static KnowledgeEntry e(String id, Type t, int ts) {
        return new KnowledgeEntry(id, t, ts, null, id, "text", List.of());
    }

    private static final List<KnowledgeEntry> ALL = List.of(
            e("s", Type.SCENE, 10), e("t1", Type.TRIVIA, 20), e("g", Type.GLOSSARY, 30),
            e("c", Type.CHARACTER, 40), e("t2", Type.TRIVIA, 40));

    private static List<String> ids(List<KnowledgeEntry> l) {
        return l.stream().map(KnowledgeEntry::id).toList();
    }

    private static List<String> playThrough(Level level) {
        List<String> fired = new java.util.ArrayList<>();
        for (double pos = 15; pos < 45; pos += 0.25) {
            fired.addAll(ids(TriviaPlanner.due(ALL, pos, pos + 0.25, level, true, true)));
        }
        return fired;
    }

    @Test
    void verbosityControlsWhatIsPushed() {
        assertEquals(List.of("t1", "t2"), playThrough(Level.MINIMAL));
        assertEquals(List.of("t1", "g", "t2"), playThrough(Level.NORMAL));
        assertEquals(List.of("t1", "g", "c", "t2"), playThrough(Level.CHATTY));
    }

    @Test
    void scenesAreNeverPushed() {
        assertEquals(List.of(), TriviaPlanner.due(ALL, 5, 10, Level.CHATTY, true, true));
    }

    @Test
    void markerFiresOnceAtTheFirstTickAtOrAfterItsTimestamp() {
        assertEquals(List.of(), TriviaPlanner.due(ALL, 19.0, 19.9, Level.MINIMAL, true, true));
        assertEquals(List.of("t1"), ids(TriviaPlanner.due(ALL, 19.9, 20.0, Level.MINIMAL, true, true)));
        // exact boundary: ts == from does NOT fire again
        assertEquals(List.of(), TriviaPlanner.due(ALL, 20.0, 20.25, Level.MINIMAL, true, true));
    }

    @Test
    void nothingFiresWhenPausedOrDisabled() {
        assertEquals(List.of(), TriviaPlanner.due(ALL, 15, 20, Level.CHATTY, true, false));
        assertEquals(List.of(), TriviaPlanner.due(ALL, 15, 20, Level.CHATTY, false, true));
    }

    @Test
    void seeksFireNothing() {
        assertEquals(List.of(), TriviaPlanner.due(ALL, 100, 20, Level.CHATTY, true, true));   // backwards
        assertEquals(List.of(), TriviaPlanner.due(ALL, 0, 40, Level.CHATTY, true, true));     // big forward jump
        assertTrue(TriviaPlanner.isJump(0, 5.01));
        assertFalse(TriviaPlanner.isJump(0, 5.0));
    }

    @Test
    void simultaneousMarkersAreOrderedByTimeThenId() {
        assertEquals(List.of("c", "t2"), ids(TriviaPlanner.due(ALL, 39, 40, Level.CHATTY, true, true)));
    }

    @Test
    void everyMarkerFiresExactlyOnceOverAFullNormalPlaythrough() {
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        double pos = 0;
        while (pos < 60) {
            double next = pos + 0.25;
            for (KnowledgeEntry k : TriviaPlanner.due(ALL, pos, next, Level.CHATTY, true, true)) {
                count.merge(k.id(), 1, Integer::sum);
            }
            pos = next;
        }
        assertEquals(java.util.Map.of("t1", 1, "g", 1, "c", 1, "t2", 1), count);
    }
}
