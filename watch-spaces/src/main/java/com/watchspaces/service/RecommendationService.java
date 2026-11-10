package com.watchspaces.service;

import com.watchspaces.core.HybridRecommender;
import com.watchspaces.core.InteractionRules;
import com.watchspaces.entity.Interaction;
import com.watchspaces.entity.Title;
import com.watchspaces.repo.InteractionRepository;
import com.watchspaces.repo.TitleRepository;
import com.watchspaces.repo.UserRepository;
import com.watchspaces.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bridges the database and {@link HybridRecommender}. Nothing is trained ahead of time: the model is computed from the
 * interactions table on demand and cached per interaction-table size, so a newly recorded interaction changes the very
 * next rail with no retrain or redeploy step.
 */
@Service
public class RecommendationService {

    public record RecView(long titleId, String name, List<String> genres, double score, String reason) { }

    private record Data(long version, List<HybridRecommender.Rating> ratings, List<HybridRecommender.TitleMeta> titles,
                        List<HybridRecommender.CoWatch> coWatch, Map<Long, Title> titleById) { }

    private final InteractionRepository interactions;
    private final TitleRepository titles;
    private final UserRepository users;

    private volatile Data data;
    private final Map<Long, List<RecView>> railCache = new ConcurrentHashMap<>();
    private volatile long railVersion = -1;

    public RecommendationService(InteractionRepository interactions, TitleRepository titles, UserRepository users) {
        this.interactions = interactions;
        this.titles = titles;
        this.users = users;
    }

    @Transactional
    public Interaction record(long userId, long titleId, int watchedSeconds, boolean completed, Integer rating,
                              List<Long> coWatched) {
        Title t = titles.findById(titleId).orElseThrow(() -> ApiException.notFound("Title"));
        if (rating != null && (rating < 1 || rating > 5)) {
            throw ApiException.badRequest("rating must be between 1 and 5");
        }
        Interaction i = new Interaction();
        i.user = users.getReferenceById(userId);
        i.title = t;
        i.watchedSeconds = Math.max(0, Math.min(watchedSeconds, t.durationSeconds));
        i.completed = completed;
        i.rating = rating;
        if (coWatched != null && !coWatched.isEmpty()) {
            i.coWatchedWith = String.join(",", coWatched.stream().map(String::valueOf).toList());
        }
        return interactions.save(i);
    }

    /** Called when a Watch Space ends: one interaction per participant, the others recorded as co-watchers. */
    @Transactional
    public void recordSession(long titleId, double positionSeconds, List<Long> participantIds) {
        Title t = titles.findById(titleId).orElseThrow(() -> ApiException.notFound("Title"));
        InteractionRules.Summary s = InteractionRules.fromSession(positionSeconds, t.durationSeconds);
        if (!s.recordable()) {
            return; // a few seconds in a room is not a viewing
        }
        for (Long uid : participantIds) {
            List<Long> others = participantIds.stream().filter(x -> !x.equals(uid)).toList();
            record(uid, titleId, s.watchedSeconds(), s.completed(), null, others);
        }
    }

    @Transactional(readOnly = true)
    public List<RecView> forUser(long userId, int n) {
        Data d = load();
        synchronized (railCache) {
            if (railVersion != d.version()) {
                railCache.clear();
                railVersion = d.version();
            }
        }
        List<RecView> all = railCache.computeIfAbsent(userId, id -> {
            List<RecView> out = new ArrayList<>();
            for (HybridRecommender.Recommendation r : HybridRecommender.recommend(id, d.ratings(), d.titles(), d.coWatch(), 20)) {
                Title t = d.titleById().get(r.titleId());
                if (t != null) {
                    out.add(new RecView(t.id, t.name, List.copyOf(t.genreSet()), Math.round(r.score() * 1000) / 1000.0,
                            r.reason()));
                }
            }
            return List.copyOf(out);
        });
        return all.size() > n ? all.subList(0, n) : all;
    }

    @Transactional(readOnly = true)
    public HybridRecommender.Evaluation evaluate(int k) {
        Data d = load();
        return HybridRecommender.evaluate(d.ratings(), d.titles(), d.coWatch(), k, HybridRecommender.ALPHA);
    }

    private Data load() {
        long version = interactions.count() * 1_000_003L + titles.count();
        Data cur = data;
        if (cur != null && cur.version() == version) {
            return cur;
        }
        List<HybridRecommender.Rating> ratings = new ArrayList<>();
        Map<String, Integer> pairs = new HashMap<>();
        for (Interaction i : interactions.findAll()) {
            double frac = i.title.durationSeconds <= 0 ? 0 : (double) i.watchedSeconds / i.title.durationSeconds;
            ratings.add(new HybridRecommender.Rating(i.user.id, i.title.id, HybridRecommender.score(i.rating, i.completed, frac)));
            if (i.coWatchedWith != null && !i.coWatchedWith.isBlank()) {
                for (String o : i.coWatchedWith.split(",")) {
                    try {
                        long other = Long.parseLong(o.trim());
                        pairs.merge(Math.min(i.user.id, other) + ":" + Math.max(i.user.id, other), 1, Integer::sum);
                    } catch (NumberFormatException ignored) {
                        // malformed co-watch entry: skip it
                    }
                }
            }
        }
        List<HybridRecommender.CoWatch> co = new ArrayList<>();
        pairs.forEach((k, c) -> {
            String[] p = k.split(":");
            // each pair is seen from both users' rows; halve so it counts sessions, not rows
            co.add(new HybridRecommender.CoWatch(Long.parseLong(p[0]), Long.parseLong(p[1]), Math.max(1, c / 2)));
        });
        List<HybridRecommender.TitleMeta> metas = new ArrayList<>();
        Map<Long, Title> byId = new HashMap<>();
        for (Title t : titles.findAll()) {
            byId.put(t.id, t);
            metas.add(new HybridRecommender.TitleMeta(t.id, Set.copyOf(t.genreSet())));
        }
        Data fresh = new Data(version, ratings, metas, co, byId);
        data = fresh;
        return fresh;
    }
}
