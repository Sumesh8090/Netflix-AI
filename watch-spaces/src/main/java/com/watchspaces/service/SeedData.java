package com.watchspaces.service;

import com.watchspaces.config.AppProperties;
import com.watchspaces.core.SyntheticData;
import com.watchspaces.entity.Interaction;
import com.watchspaces.entity.Role;
import com.watchspaces.entity.Title;
import com.watchspaces.entity.User;
import com.watchspaces.repo.InteractionRepository;
import com.watchspaces.repo.TitleRepository;
import com.watchspaces.repo.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Idempotent seed: demo accounts (admin@/host@/viewer@watchspaces.local, password from SEED_PASSWORD) and one fully
 * authored titles ("The Pier", "The Harbor Case") with timelines, plus synthetic recommender data. Runs only when
 * app.seed.enabled=true and the tables are empty.
 */
@Component
public class SeedData implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    public static final long SEED = 42;

    private final AppProperties props;
    private final UserRepository users;
    private final TitleRepository titles;
    private final InteractionRepository interactions;
    private final TimelineService timeline;
    private final PasswordEncoder encoder;

    public SeedData(AppProperties props, UserRepository users, TitleRepository titles,
                    InteractionRepository interactions, TimelineService timeline, PasswordEncoder encoder) {
        this.interactions = interactions;
        this.props = props;
        this.users = users;
        this.titles = titles;
        this.timeline = timeline;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!props.seed().enabled()) {
            return;
        }
        String pw = props.seed().defaultPassword();
        if (pw == null || pw.length() < 8) {
            throw new IllegalStateException("SEED_PASSWORD must be set (min 8 chars) when SEED_ENABLED=true");
        }
        if (users.count() == 0) {
            user("admin@watchspaces.local", "Ada Admin", Role.ADMIN, pw, "en");
            user("host@watchspaces.local", "Harper Host", Role.HOST, pw, "en");
            user("viewer@watchspaces.local", "Vik Viewer", Role.VIEWER, pw, "hi");
            user("viewer2@watchspaces.local", "Vera Viewer", Role.VIEWER, pw, "es");
        }
        if (titles.count() == 0) {
            Title t = new Title();
            t.name = "The Pier";
            t.durationSeconds = 2400;
            t.genres = "Crime,Thriller";
            t.videoAssetUrl = "/media/the-pier.mp4"; // placeholder: any local sample clip works
            t = titles.save(t);
            String json = new String(new ClassPathResource("seed/timeline-the-pier.json").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            var summary = timeline.replace(t.id, json);
            log.info("seed.title id={} events={} variationPoints={}", t.id, summary.events(), summary.variationPoints());

            Title h = new Title();
            h.name = "The Harbor Case";
            h.durationSeconds = 1800;
            h.genres = "Crime,Mystery";
            h.videoAssetUrl = "/media/harbor-case.mp4";
            h = titles.save(h);
            String harbor = new String(new ClassPathResource("seed/harbor-case-timeline.json").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            var hs = timeline.replace(h.id, harbor);
            log.info("seed.title id={} events={} variationPoints={}", h.id, hs.events(), hs.variationPoints());
        }
        if (interactions.count() == 0 && !users.existsByEmailIgnoreCase("demo01@watchspaces.local")) {
            seedRecommendationData(pw);
        }
    }

    /**
     * Reproducible demo data for the recommender: 48 synthetic titles (6 genres x 8), 60 demo viewers and their viewing
     * history (SyntheticData, fixed seed 42). viewer@ also gets a Comedy-heavy history so their rail is visibly personal.
     */
    private void seedRecommendationData(String pw) {
        SyntheticData.Dataset ds = SyntheticData.generate(SEED);
        List<Title> synthTitles = new ArrayList<>();
        for (SyntheticData.SynthTitle st : ds.titles()) {
            Title t = new Title();
            t.name = st.name();
            t.durationSeconds = st.durationSeconds();
            t.genres = String.join(",", st.genres());
            synthTitles.add(t);
        }
        synthTitles = titles.saveAll(synthTitles);
        Map<Integer, Title> titleByOrdinal = new HashMap<>();
        for (int i = 0; i < synthTitles.size(); i++) {
            titleByOrdinal.put(ds.titles().get(i).ordinal(), synthTitles.get(i));
        }
        String hash = encoder.encode(pw);
        List<User> demo = new ArrayList<>();
        for (int u = 1; u <= SyntheticData.USERS; u++) {
            demo.add(newUser(String.format("demo%02d@watchspaces.local", u), "Demo Viewer " + u, Role.VIEWER, hash, "en"));
        }
        demo = users.saveAll(demo);
        List<Interaction> rows = new ArrayList<>();
        for (SyntheticData.SynthInteraction si : ds.interactions()) {
            Title t = titleByOrdinal.get(si.titleOrdinal());
            Interaction i = new Interaction();
            i.user = demo.get(si.userOrdinal() - 1);
            i.title = t;
            i.watchedSeconds = (int) Math.round(t.durationSeconds * Math.min(1.0, si.watchedFraction()));
            i.completed = si.completed();
            i.rating = si.rating();
            rows.add(i);
        }
        users.findByEmailIgnoreCase("viewer@watchspaces.local").ifPresent(viewer -> {
            for (int ord = 9; ord <= 13; ord++) { // ordinals 9..16 are the Comedy titles
                Title t = titleByOrdinal.get(ord);
                Interaction i = new Interaction();
                i.user = viewer;
                i.title = t;
                i.watchedSeconds = t.durationSeconds;
                i.completed = true;
                i.rating = 5;
                rows.add(i);
            }
        });
        interactions.saveAll(rows);
        log.info("seed.recommendations titles={} users={} interactions={}", synthTitles.size(), demo.size(), rows.size());
    }

    private void user(String email, String name, Role role, String pw, String locale) {
        users.save(newUser(email, name, role, encoder.encode(pw), locale));
    }

    private static User newUser(String email, String name, Role role, String hash, String locale) {
        User u = new User();
        u.email = email;
        u.displayName = name;
        u.role = role;
        u.passwordHash = hash;
        u.subtitleLocale = locale;
        return u;
    }
}
