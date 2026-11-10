package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * In-process performance gates for the pure logic. The limits are deliberately generous (hundreds of times above what a
 * normal laptop measures, see docs/PERFORMANCE.md) so they only fail on a real regression, not on a slow machine.
 * Socket-level latency (join, sync, fan-out) is measured by scripts/LoadTest.java against a running server.
 */
class PerformanceSmokeTest {

    private static double p(double[] a, double pct) {
        double[] s = a.clone();
        Arrays.sort(s);
        return s[Math.max(0, Math.min(s.length - 1, (int) Math.ceil(pct / 100.0 * s.length) - 1))];
    }

    private static List<KnowledgeEntry> load(String resource) throws Exception {
        try (InputStream in = PerformanceSmokeTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
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

    @Test
    void playbackStateMachineHandlesManyCommandsQuickly() {
        long[] clock = {0};
        PlaybackStateMachine sm = new PlaybackStateMachine(2400, () -> clock[0]);
        int n = 200_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            clock[0] += 10;
            sm.apply(i % 2 == 0 ? PlaybackStateMachine.Command.PLAY : PlaybackStateMachine.Command.PAUSE, (double) (i % 2000));
        }
        double usPerOp = (System.nanoTime() - t0) / 1e3 / n;
        System.out.printf("state machine: %.3f us/op%n", usPerOp);
        assertTrue(usPerOp < 50, "state machine too slow: " + usPerOp + " us/op");
    }

    @Test
    void groundedQaAnswersWellInsideTheLatencyBudgetOnBothSeedTimelines() throws Exception {
        String[] questions = {"Who is the detective?", "What happened so far?", "What does the word manifest mean?",
                "Where was this filmed?", "Who is Elena Vance?", "who just walked in", "what is the capital of France",
                "Where was the body found?"};
        for (String res : new String[]{"/seed/timeline-the-pier.json", "/seed/harbor-case-timeline.json"}) {
            List<KnowledgeEntry> k = load(res);
            for (int w = 0; w < 500; w++) {
                GroundedQa.answer(k, questions[w % questions.length], 900, 2); // warm-up
            }
            int n = 3000;
            double[] ms = new double[n];
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                GroundedQa.answer(k, questions[i % questions.length], (i * 37) % 1700, 2);
                ms[i] = (System.nanoTime() - t0) / 1e6;
            }
            System.out.printf("QA %s: p50=%.4f ms p95=%.4f ms%n", res, p(ms, 50), p(ms, 95));
            assertTrue(p(ms, 95) < 50, "QA p95 " + p(ms, 95) + " ms on " + res);
        }
    }

    @Test
    void triviaPlannerTickIsCheapEnoughToRunFourTimesASecondPerRoom() throws Exception {
        List<KnowledgeEntry> k = load("/seed/timeline-the-pier.json");
        int n = 100_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            TriviaPlanner.due(k, (i % 2000) + 0.0, (i % 2000) + 0.25, TriviaPlanner.Level.CHATTY, true, true);
        }
        double usPerTick = (System.nanoTime() - t0) / 1e3 / n;
        System.out.printf("trivia tick: %.3f us%n", usPerTick);
        assertTrue(usPerTick < 100, "trivia tick " + usPerTick + " us");
    }

    @Test
    void recommenderIsFastAndMeetsThePrecisionTargetOnTheSeededDataset() {
        var ds = SyntheticData.generate(42);
        List<HybridRecommender.Rating> rs = new ArrayList<>();
        for (var i : ds.interactions()) {
            rs.add(new HybridRecommender.Rating(i.userOrdinal(), i.titleOrdinal(),
                    HybridRecommender.score(i.rating(), i.completed(), i.watchedFraction())));
        }
        List<HybridRecommender.TitleMeta> ts = new ArrayList<>();
        for (var t : ds.titles()) {
            ts.add(new HybridRecommender.TitleMeta(t.ordinal(), new HashSet<>(t.genres())));
        }
        List<HybridRecommender.CoWatch> cw = new ArrayList<>();
        for (var c : ds.coWatch()) {
            cw.add(new HybridRecommender.CoWatch(c.userA(), c.userB(), c.count()));
        }
        for (int w = 0; w < 100; w++) {
            HybridRecommender.recommend(1 + w % SyntheticData.USERS, rs, ts, cw, 5);
        }
        double[] ms = new double[SyntheticData.USERS * 5];
        for (int i = 0; i < ms.length; i++) {
            long t0 = System.nanoTime();
            HybridRecommender.recommend(1 + i % SyntheticData.USERS, rs, ts, cw, 5);
            ms[i] = (System.nanoTime() - t0) / 1e6;
        }
        System.out.printf("recommend(): p50=%.3f ms p95=%.3f ms%n", p(ms, 50), p(ms, 95));
        assertTrue(p(ms, 95) < 100, "recommend p95 " + p(ms, 95) + " ms");

        HybridRecommender.Evaluation ev = HybridRecommender.evaluate(rs, ts, cw, 5, HybridRecommender.ALPHA);
        System.out.printf("Precision@5=%.3f (best achievable %.3f, normalised %.3f) over %d users%n", ev.precisionAtK(),
                ev.maxAchievable(), ev.normalizedPrecision(), ev.users());
        assertTrue(ev.precisionAtK() >= 0.6, "P@5 " + ev.precisionAtK());
        assertTrue(ev.normalizedPrecision() >= 0.7, "normalised " + ev.normalizedPrecision());
    }
}
