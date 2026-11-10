package com.watchspaces.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic synthetic catalogue + interaction generator used to seed the database and to evaluate the
 * recommender offline (Precision@5). Users have a primary genre (strong affinity), often a secondary genre
 * (moderate affinity) and a little random noise (disliked titles). Fixed seed => reproducible numbers.
 */
public final class SyntheticData {

    public static final String[] GENRES = {"Crime", "Comedy", "Sci-Fi", "Romance", "Documentary", "Horror"};
    public static final int TITLES_PER_GENRE = 8;
    public static final int USERS = 60;

    public record SynthTitle(int ordinal, String name, List<String> genres, int durationSeconds) { }

    public record SynthInteraction(int userOrdinal, int titleOrdinal, double watchedFraction, boolean completed,
                                   Integer rating) { }

    public record SynthCoWatch(int userA, int userB, int count) { }

    public record Dataset(List<SynthTitle> titles, List<SynthInteraction> interactions, List<SynthCoWatch> coWatch) { }

    private SyntheticData() { }

    public static Dataset generate(long seed) {
        Random rnd = new Random(seed);
        List<SynthTitle> titles = new ArrayList<>();
        int ord = 1;
        for (int g = 0; g < GENRES.length; g++) {
            for (int i = 1; i <= TITLES_PER_GENRE; i++) {
                List<String> genres = new ArrayList<>();
                genres.add(GENRES[g]);
                if (rnd.nextInt(4) == 0) {
                    genres.add(GENRES[(g + 1 + rnd.nextInt(GENRES.length - 1)) % GENRES.length]);
                }
                titles.add(new SynthTitle(ord++, GENRES[g] + " Title " + i, genres, 1800 + rnd.nextInt(5400)));
            }
        }
        List<SynthInteraction> interactions = new ArrayList<>();
        for (int u = 1; u <= USERS; u++) {
            int primary = u % GENRES.length;
            int secondary = rnd.nextBoolean() ? (primary + 1 + rnd.nextInt(GENRES.length - 1)) % GENRES.length : -1;
            for (SynthTitle t : titles) {
                int tg = indexOf(t.genres().get(0));
                boolean inPrimary = t.genres().contains(GENRES[primary]);
                boolean inSecondary = secondary >= 0 && t.genres().contains(GENRES[secondary]);
                if (inPrimary && rnd.nextDouble() < 0.85) {
                    interactions.add(liked(rnd, u, t.ordinal()));
                } else if (inSecondary && rnd.nextDouble() < 0.5) {
                    interactions.add(liked(rnd, u, t.ordinal()));
                } else if (!inPrimary && !inSecondary && rnd.nextDouble() < 0.06) {
                    interactions.add(new SynthInteraction(u, t.ordinal(), 0.1 + rnd.nextDouble() * 0.2, false,
                            1 + rnd.nextInt(2)));
                }
                if (tg < 0) {
                    throw new IllegalStateException();
                }
            }
        }
        List<SynthCoWatch> co = new ArrayList<>();
        for (int u = 1; u + GENRES.length <= USERS; u++) {
            if (rnd.nextInt(3) == 0) {
                co.add(new SynthCoWatch(u, u + GENRES.length, 1 + rnd.nextInt(3)));
            }
        }
        return new Dataset(titles, interactions, co);
    }

    private static SynthInteraction liked(Random rnd, int user, int title) {
        boolean rated = rnd.nextDouble() < 0.7;
        return new SynthInteraction(user, title, 0.85 + rnd.nextDouble() * 0.15, true,
                rated ? 4 + rnd.nextInt(2) : null);
    }

    private static int indexOf(String genre) {
        for (int i = 0; i < GENRES.length; i++) {
            if (GENRES[i].equals(genre)) {
                return i;
            }
        }
        return -1;
    }
}
