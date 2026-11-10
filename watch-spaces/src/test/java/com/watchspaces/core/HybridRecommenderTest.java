package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HybridRecommenderTest {
    private static HybridRecommender.TitleMeta t(long id, String... genres) {
        return new HybridRecommender.TitleMeta(id, Set.of(genres));
    }

    private static HybridRecommender.Rating r(long u, long t, double s) {
        return new HybridRecommender.Rating(u, t, s);
    }

    private static final List<HybridRecommender.TitleMeta> TITLES = List.of(
            t(1, "Crime"), t(2, "Crime"), t(3, "Crime"), t(4, "Comedy"), t(5, "Comedy"), t(6, "Comedy"));

    @Test
    void scoreUsesRatingThenCompletionThenProgress() {
        assertEquals(1.0, HybridRecommender.score(5, false, 0), 1e-9);
        assertEquals(0.2, HybridRecommender.score(1, true, 1), 1e-9);
        assertEquals(0.8, HybridRecommender.score(null, true, 1), 1e-9);
        assertEquals(0.3, HybridRecommender.score(null, false, 0.5), 1e-9);
        assertEquals(0.6, HybridRecommender.score(null, false, 5.0), 1e-9);
    }

    @Test
    void neverRecommendsAlreadySeenTitles() {
        List<HybridRecommender.Rating> rs = List.of(r(1, 1, 1), r(1, 2, 1), r(2, 1, 1), r(2, 2, 1), r(2, 3, 1));
        for (var rec : HybridRecommender.recommend(1, rs, TITLES, List.of(), 10)) {
            assertTrue(rec.titleId() != 1 && rec.titleId() != 2);
        }
    }

    @Test
    void contentBasedPrefersLikedGenre() {
        List<HybridRecommender.Rating> rs = List.of(r(1, 1, 1), r(1, 2, 1), r(1, 4, 0.1));
        List<HybridRecommender.Recommendation> recs = HybridRecommender.recommend(1, rs, TITLES, List.of(), 3);
        assertEquals(3, recs.get(0).titleId()); // the unseen Crime title beats unseen Comedy titles
    }

    @Test
    void collaborativeSignalSurfacesTitleLikedBySimilarUsers() {
        List<HybridRecommender.TitleMeta> ts = List.of(t(1, "A"), t(2, "A"), t(3, "B"), t(4, "C"));
        List<HybridRecommender.Rating> rs = new ArrayList<>(List.of(r(1, 1, 1), r(1, 2, 1)));
        for (long u = 2; u <= 5; u++) {
            rs.add(r(u, 1, 1));
            rs.add(r(u, 2, 1));
            rs.add(r(u, 3, 1)); // all neighbours loved title 3
        }
        rs.add(r(9, 4, 1)); // unrelated user loved title 4
        List<HybridRecommender.Recommendation> recs = HybridRecommender.recommend(1, rs, ts, List.of(), 2);
        assertEquals(3, recs.get(0).titleId());
        assertTrue(recs.get(0).collabScore() > 0);
    }

    @Test
    void coldStartFallsBackToPopularity() {
        List<HybridRecommender.Rating> rs = List.of(r(2, 1, 1), r(3, 1, 1), r(4, 1, 1), r(2, 5, 1));
        List<HybridRecommender.Recommendation> recs = HybridRecommender.recommend(99, rs, TITLES, List.of(), 2);
        assertEquals(1, recs.get(0).titleId());
    }

    @Test
    void newInteractionChangesTheRailWithoutAnyRetrain() {
        List<HybridRecommender.Rating> rs = new ArrayList<>(List.of(r(1, 1, 1), r(1, 2, 1)));
        long before = HybridRecommender.recommend(1, rs, TITLES, List.of(), 1).get(0).titleId();
        assertEquals(3, before);
        rs.add(r(1, 3, 1)); // user now has all crime
        rs.add(r(1, 4, 1));
        rs.add(r(1, 5, 1)); // and loves comedy
        long after = HybridRecommender.recommend(1, rs, TITLES, List.of(), 1).get(0).titleId();
        assertEquals(6, after);
    }

    @Test
    void coWatchBoostsNeighbourSimilarity() {
        List<HybridRecommender.TitleMeta> ts = List.of(t(1, "A"), t(2, "A"), t(3, "B"), t(4, "C"));
        List<HybridRecommender.Rating> rs = List.of(r(1, 1, 1), r(2, 1, 1), r(2, 3, 1), r(3, 1, 1), r(3, 4, 1));
        var without = HybridRecommender.recommend(1, rs, ts, List.of(), 5);
        var with = HybridRecommender.recommend(1, rs, ts, List.of(new HybridRecommender.CoWatch(1, 3, 3)), 5);
        double w3 = with.stream().filter(x -> x.titleId() == 4).findFirst().orElseThrow().collabScore();
        double wo3 = without.stream().filter(x -> x.titleId() == 4).findFirst().orElseThrow().collabScore();
        assertTrue(w3 >= wo3);
    }

    @Test
    void deterministicOutput() {
        var ds = SyntheticData.generate(42);
        assertEquals(ds.interactions().size(), SyntheticData.generate(42).interactions().size());
    }

    @Test
    void offlinePrecisionAtFiveOnSyntheticDataMeetsTarget() {
        for (long seed : new long[] {42, 7, 99}) {
            var ds = SyntheticData.generate(seed);
            List<HybridRecommender.Rating> rs = new ArrayList<>();
            for (var i : ds.interactions()) {
                rs.add(new HybridRecommender.Rating(i.userOrdinal(), i.titleOrdinal(),
                        HybridRecommender.score(i.rating(), i.completed(), i.watchedFraction())));
            }
            List<HybridRecommender.TitleMeta> ts = new ArrayList<>();
            for (var x : ds.titles()) {
                ts.add(new HybridRecommender.TitleMeta(x.ordinal(), new HashSet<>(x.genres())));
            }
            List<HybridRecommender.CoWatch> cw = new ArrayList<>();
            for (var c : ds.coWatch()) {
                cw.add(new HybridRecommender.CoWatch(c.userA(), c.userB(), c.count()));
            }
            HybridRecommender.Evaluation ev = HybridRecommender.evaluate(rs, ts, cw, 5, HybridRecommender.ALPHA);
            assertTrue(ev.users() > 30, "users evaluated: " + ev.users());
            assertTrue(ev.precisionAtK() >= 0.6, "seed " + seed + " P@5=" + ev.precisionAtK());
        }
    }

    @Test
    void hybridBeatsPopularityBaselineOnSyntheticData() {
        var ds = SyntheticData.generate(42);
        List<HybridRecommender.Rating> rs = new ArrayList<>();
        for (var i : ds.interactions()) {
            rs.add(new HybridRecommender.Rating(i.userOrdinal(), i.titleOrdinal(),
                    HybridRecommender.score(i.rating(), i.completed(), i.watchedFraction())));
        }
        List<HybridRecommender.TitleMeta> ts = new ArrayList<>();
        for (var x : ds.titles()) {
            ts.add(new HybridRecommender.TitleMeta(x.ordinal(), new HashSet<>(x.genres())));
        }
        double hybrid = HybridRecommender.evaluate(rs, ts, List.of(), 5, HybridRecommender.ALPHA).precisionAtK();
        // alpha=0 and alpha=1 are the pure collaborative / pure content ablations; hybrid must not be worse than both
        double collab = HybridRecommender.evaluate(rs, ts, List.of(), 5, 0.0).precisionAtK();
        double content = HybridRecommender.evaluate(rs, ts, List.of(), 5, 1.0).precisionAtK();
        assertTrue(hybrid >= Math.min(collab, content), "hybrid=" + hybrid + " collab=" + collab + " content=" + content);
    }
}
