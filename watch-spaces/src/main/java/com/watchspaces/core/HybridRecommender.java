package com.watchspaces.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Hybrid recommender: content-based (genre affinity) + user-based collaborative filtering with a co-watch bonus.
 * Pure and deterministic. It is computed on demand from interaction data, so a newly recorded interaction shows up
 * in the next call with no retrain/redeploy step (the service layer only caches per interaction-version).
 *
 * final = ALPHA * content + (1 - ALPHA) * collaborative
 */
public final class HybridRecommender {

    public record Rating(long userId, long titleId, double score) { }

    public record TitleMeta(long id, Set<String> genres) { }

    public record CoWatch(long userA, long userB, int count) { }

    public record Recommendation(long titleId, double score, double contentScore, double collabScore, String reason) { }

    /**
     * @param precisionAtK        standard Precision@k (hits / k), averaged over evaluated users
     * @param maxAchievable       the best Precision@k any model could score on this hold-out (small hold-outs cap it below 1)
     * @param normalizedPrecision precisionAtK / maxAchievable
     */
    public record Evaluation(int k, int users, double precisionAtK, double maxAchievable, double normalizedPrecision) { }

    public static final double ALPHA = 0.7; // chosen by a sweep on the synthetic dataset (see docs/PERFORMANCE.md)
    public static final int NEIGHBOURS = 20;
    /** Default hold-out protocol: every 2nd liked title is hidden from the model and must be recovered. */
    public static final int HOLDOUT_STRIDE = 2;
    public static final double LIKED_THRESHOLD = 0.7;

    private HybridRecommender() { }

    /** Implicit/explicit interaction strength in [0,1]. An explicit rating wins; otherwise completion/progress. */
    public static double score(Integer rating, boolean completed, double watchedFraction) {
        if (rating != null) {
            return Math.max(0, Math.min(5, rating)) / 5.0;
        }
        if (completed) {
            return 0.8;
        }
        return Math.min(0.6, Math.max(0, watchedFraction) * 0.6);
    }

    public static List<Recommendation> recommend(long userId, Collection<Rating> ratings, Collection<TitleMeta> titles,
                                                 Collection<CoWatch> coWatch, int n) {
        return recommend(userId, ratings, titles, coWatch, n, ALPHA);
    }

    public static List<Recommendation> recommend(long userId, Collection<Rating> ratings, Collection<TitleMeta> titles,
                                                 Collection<CoWatch> coWatch, int n, double alpha) {
        Map<Long, Map<Long, Double>> byUser = new HashMap<>();
        Map<Long, List<Double>> byTitle = new HashMap<>();
        for (Rating r : ratings) {
            byUser.computeIfAbsent(r.userId(), k -> new HashMap<>()).merge(r.titleId(), r.score(), Math::max);
            byTitle.computeIfAbsent(r.titleId(), k -> new ArrayList<>()).add(r.score());
        }
        Map<Long, TitleMeta> meta = new TreeMap<>();
        for (TitleMeta t : titles) {
            meta.put(t.id(), t);
        }
        Map<Long, Double> mine = byUser.getOrDefault(userId, Map.of());
        Set<Long> seen = new HashSet<>(mine.keySet());

        List<Recommendation> out = new ArrayList<>();
        if (mine.isEmpty()) {
            // cold start: popularity
            for (TitleMeta t : meta.values()) {
                List<Double> s = byTitle.get(t.id());
                if (s == null || s.isEmpty()) {
                    continue;
                }
                double mean = s.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double pop = mean * Math.log(1 + s.size());
                out.add(new Recommendation(t.id(), pop, 0, 0, "Popular with other viewers"));
            }
            out.sort(Comparator.comparingDouble(Recommendation::score).reversed()
                    .thenComparingLong(Recommendation::titleId));
            return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
        }

        // ---- content profile: score-weighted genre vector, L2-normalised -------------------------------
        Map<String, Double> profile = new HashMap<>();
        for (Map.Entry<Long, Double> e : mine.entrySet()) {
            TitleMeta t = meta.get(e.getKey());
            if (t == null) {
                continue;
            }
            // centre around 0.5 so disliked titles push a genre down
            double w = e.getValue() - 0.5;
            for (String g : t.genres()) {
                profile.merge(g, w, Double::sum);
            }
        }
        double profNorm = Math.sqrt(profile.values().stream().mapToDouble(v -> v * v).sum());

        // ---- neighbours -----------------------------------------------------------------------------------
        Map<Long, Integer> coWatchWithMe = new HashMap<>();
        for (CoWatch c : coWatch) {
            if (c.userA() == userId) {
                coWatchWithMe.merge(c.userB(), c.count(), Integer::sum);
            } else if (c.userB() == userId) {
                coWatchWithMe.merge(c.userA(), c.count(), Integer::sum);
            }
        }
        record Neighbour(long userId, double sim) { }
        List<Neighbour> neighbours = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, Double>> e : byUser.entrySet()) {
            if (e.getKey() == userId) {
                continue;
            }
            double sim = cosine(mine, e.getValue());
            sim += Math.min(0.3, 0.05 * coWatchWithMe.getOrDefault(e.getKey(), 0));
            if (sim > 0) {
                neighbours.add(new Neighbour(e.getKey(), sim));
            }
        }
        neighbours.sort(Comparator.comparingDouble(Neighbour::sim).reversed().thenComparingLong(Neighbour::userId));
        if (neighbours.size() > NEIGHBOURS) {
            neighbours = new ArrayList<>(neighbours.subList(0, NEIGHBOURS));
        }

        for (TitleMeta t : meta.values()) {
            if (seen.contains(t.id())) {
                continue;
            }
            double content = 0;
            if (profNorm > 0 && !t.genres().isEmpty()) {
                double dot = 0;
                for (String g : t.genres()) {
                    dot += profile.getOrDefault(g, 0.0);
                }
                content = Math.max(0, dot / (profNorm * Math.sqrt(t.genres().size())));
            }
            double num = 0;
            double den = 0;
            int contributors = 0;
            for (Neighbour nb : neighbours) {
                Double s = byUser.get(nb.userId()).get(t.id());
                if (s != null) {
                    num += nb.sim() * s;
                    den += nb.sim();
                    contributors++;
                }
            }
            double collab = den > 0 ? (num / den) * Math.min(1.0, contributors / 3.0) : 0;
            double total = alpha * content + (1 - alpha) * collab;
            if (total <= 0) {
                continue;
            }
            out.add(new Recommendation(t.id(), total, content, collab, reason(t, content, collab, profile)));
        }
        out.sort(Comparator.comparingDouble(Recommendation::score).reversed().thenComparingLong(Recommendation::titleId));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /** Convenience: standard Precision@k only. */
    public static double precisionAtK(Collection<Rating> ratings, Collection<TitleMeta> titles,
                                      Collection<CoWatch> coWatch, int k) {
        return evaluate(ratings, titles, coWatch, k, ALPHA).precisionAtK();
    }

    /**
     * Offline evaluation. For every user with at least 3 "liked" interactions (score &gt;= LIKED_THRESHOLD), every
     * {@code stride}-th liked title (by id order) is held out; the model sees the rest and its top-k is checked against the hold-out.
     * Precision@k = hits / k, averaged over evaluated users. Because a hold-out smaller than k caps what any model can
     * score, the evaluation also reports the achievable maximum and the normalised value.
     */
    public static Evaluation evaluate(Collection<Rating> ratings, Collection<TitleMeta> titles,
                                      Collection<CoWatch> coWatch, int k, double alpha) {
        return evaluate(ratings, titles, coWatch, k, alpha, HOLDOUT_STRIDE);
    }

    /** @param stride every {@code stride}-th liked title (by id order, starting at the first) is held out */
    public static Evaluation evaluate(Collection<Rating> ratings, Collection<TitleMeta> titles,
                                      Collection<CoWatch> coWatch, int k, double alpha, int stride) {
        Map<Long, TreeSet<Long>> liked = new TreeMap<>();
        for (Rating r : ratings) {
            if (r.score() >= LIKED_THRESHOLD) {
                liked.computeIfAbsent(r.userId(), x -> new TreeSet<>()).add(r.titleId());
            }
        }
        Set<String> heldOut = new HashSet<>();
        Map<Long, Set<Long>> holdByUser = new TreeMap<>();
        for (Map.Entry<Long, TreeSet<Long>> e : liked.entrySet()) {
            if (e.getValue().size() < 3) {
                continue;
            }
            int i = 0;
            Set<Long> hold = new HashSet<>();
            for (Long titleId : e.getValue()) {
                if (i % stride == 0) {
                    hold.add(titleId);
                    heldOut.add(e.getKey() + ":" + titleId);
                }
                i++;
            }
            holdByUser.put(e.getKey(), hold);
        }
        List<Rating> train = new ArrayList<>();
        for (Rating r : ratings) {
            if (!heldOut.contains(r.userId() + ":" + r.titleId())) {
                train.add(r);
            }
        }
        double sum = 0;
        double ceiling = 0;
        int users = 0;
        for (Map.Entry<Long, Set<Long>> e : holdByUser.entrySet()) {
            List<Recommendation> recs = recommend(e.getKey(), train, titles, coWatch, k, alpha);
            int hits = 0;
            for (Recommendation r : recs) {
                if (e.getValue().contains(r.titleId())) {
                    hits++;
                }
            }
            sum += (double) hits / k;
            ceiling += (double) Math.min(k, e.getValue().size()) / k;
            users++;
        }
        if (users == 0) {
            return new Evaluation(k, 0, 0, 0, 0);
        }
        double p = sum / users;
        double c = ceiling / users;
        return new Evaluation(k, users, p, c, c == 0 ? 0 : p / c);
    }

    // --- internals --------------------------------------------------------------------------------------

    private static double cosine(Map<Long, Double> a, Map<Long, Double> b) {
        double dot = 0;
        for (Map.Entry<Long, Double> e : a.entrySet()) {
            Double o = b.get(e.getKey());
            if (o != null) {
                dot += e.getValue() * o;
            }
        }
        if (dot == 0) {
            return 0;
        }
        double na = Math.sqrt(a.values().stream().mapToDouble(v -> v * v).sum());
        double nb = Math.sqrt(b.values().stream().mapToDouble(v -> v * v).sum());
        return na == 0 || nb == 0 ? 0 : dot / (na * nb);
    }

    private static String reason(TitleMeta t, double content, double collab, Map<String, Double> profile) {
        String bestGenre = t.genres().stream()
                .max(Comparator.comparingDouble(g -> profile.getOrDefault(g, 0.0)))
                .orElse("this genre");
        if (content >= collab) {
            return "Because you enjoy " + bestGenre;
        }
        return "Viewers with similar taste liked this";
    }
}
