package com.watchspaces.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Retrieval-only grounded Q&amp;A over a title's metadata. No generative model is involved: every answer is
 * assembled from templates around retrieved entries, so it can never contain a fact that is not in the
 * ingested metadata, and it always cites the entries it used.
 *
 * Retrieval rules:
 *  - Spoiler safety: only entries that start at or before {@code currentTs} are eligible.
 *  - Lexical score: TF-IDF-style overlap between the question and the entry (title/keywords weighted x3).
 *  - Intent boosts: who/character, meaning/glossary, recap/scene, trivia questions favour the matching type;
 *    "who" questions also favour characters whose window covers (or just ended before) the current time.
 *  - If nothing relevant is found the engine declines instead of guessing.
 */
public final class GroundedQa {

    public record Source(String eventId, KnowledgeEntry.Type type, int ts, String title, String excerpt) { }

    public record Result(String answer, List<String> sourceEvents, List<Source> sources, boolean grounded) { }

    public static final String DECLINE =
            "I don't have anything in this title's metadata about that up to this point in the story, "
                    + "so I'd rather not guess.";

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "of", "to", "in",
            "on", "at", "for", "and", "or", "it", "its", "this", "that", "these", "those", "who", "what", "which",
            "when", "where", "why", "how", "just", "me", "my", "i", "you", "he", "she", "they", "them", "his", "her",
            "with", "about", "tell", "can", "could", "would", "please", "there", "so", "as", "by", "from", "up",
            "now", "right", "going", "got", "get", "has", "have", "had"));

    private static final Set<String> WHO = Set.of("who", "character", "actor", "actress", "person", "guy",
            "woman", "man", "lady", "detective", "played");
    private static final Set<String> MEANING = Set.of("mean", "meaning", "definition", "define", "word", "term",
            "phrase", "stand");
    private static final Set<String> RECAP = Set.of("happen", "happened", "recap", "earlier", "far", "previously",
            "before", "story", "plot", "missed", "summary", "summarize", "summarise", "going");
    private static final Set<String> TRIVIA = Set.of("filmed", "shot", "made", "behind", "trivia", "fact", "location",
            "cast", "produce", "produced", "real", "actually", "fun");

    /** Words that point at "what is on screen right now" rather than naming something specific. */
    private static final Set<String> DEICTIC = Set.of("just", "walked", "walk", "walks", "walking", "came", "come", "comes",
            "enter", "entered", "enters", "appear", "appeared", "appears", "new", "here", "screen", "onscreen", "speaking",
            "talking", "playing", "played", "show", "showing", "scene", "now", "currently", "present", "name", "called");

    private static final double MIN_SCORE = 1.0;
    private static final int RECENT_WINDOW_SECONDS = 180;

    private GroundedQa() { }

    public static Result answer(List<KnowledgeEntry> all, String question, int currentTs, int maxSources) {
        if (question == null || question.isBlank()) {
            return new Result(DECLINE, List.of(), List.of(), false);
        }
        List<KnowledgeEntry> eligible = new ArrayList<>();
        for (KnowledgeEntry e : all) {
            if (e.startTs() <= currentTs) {
                eligible.add(e);
            }
        }
        if (eligible.isEmpty()) {
            return new Result(DECLINE, List.of(), List.of(), false);
        }

        String lower = question.toLowerCase(Locale.ROOT);
        List<String> rawTokens = rawTokens(lower);
        Set<String> raw = new HashSet<>(rawTokens);
        Set<String> qTerms = new HashSet<>();
        for (String t : rawTokens) {
            if (!STOP.contains(t)) {
                qTerms.add(stem(t));
            }
        }
        boolean who = intersects(raw, WHO);
        boolean meaning = intersects(raw, MEANING) || lower.contains("what is a ") || lower.contains("what's a ");
        boolean recap = intersects(raw, RECAP) || lower.contains("what happened");
        boolean trivia = intersects(raw, TRIVIA);

        // Intent words say what KIND of answer is wanted ("recap", "mean"); they are not content to match against entries,
        // otherwise "what happened so far" would match any entry whose text happens to contain "happened".
        if (recap) {
            RECAP.forEach(w -> qTerms.remove(stem(w)));
        }
        if (meaning) {
            MEANING.forEach(w -> qTerms.remove(stem(w)));
        }

        // A question is "deictic" when, besides intent words, it names nothing specific ("who just walked in?"). Only then
        // may the character on screen / recent scenes answer it. "Who is Elena Vance?" names something specific, so it must
        // match that name in the metadata or be declined instead of being answered with whoever happens to be on screen.
        Set<String> residual = new HashSet<>(qTerms);
        WHO.forEach(w -> residual.remove(stem(w)));
        DEICTIC.forEach(w -> residual.remove(stem(w)));
        boolean deictic = residual.isEmpty();

        // document frequency over eligible entries
        Map<String, Integer> df = new HashMap<>();
        List<Map<String, Double>> docWeights = new ArrayList<>();
        for (KnowledgeEntry e : eligible) {
            Map<String, Double> w = new HashMap<>();
            addTokens(w, e.title(), 3.0);
            if (e.keywords() != null) {
                for (String k : e.keywords()) {
                    addTokens(w, k, 3.0);
                }
            }
            addTokens(w, e.text(), 1.0);
            docWeights.add(w);
            for (String term : w.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }

        record Scored(KnowledgeEntry entry, double score) { }
        List<Scored> scored = new ArrayList<>();
        int n = eligible.size();
        for (int i = 0; i < n; i++) {
            KnowledgeEntry e = eligible.get(i);
            Map<String, Double> w = docWeights.get(i);
            double lexical = 0;
            for (String term : qTerms) {
                Double weight = w.get(term);
                if (weight != null) {
                    double idf = Math.log(1.0 + (double) n / (1 + df.getOrDefault(term, 0)));
                    lexical += idf * Math.min(weight, 6.0);
                }
            }
            double boost = 0;
            boolean contextual = false;
            switch (e.type()) {
                case CHARACTER -> {
                    if (who) {
                        boost += 1.5;
                        int end = e.endTs() != null ? e.endTs() : e.startTs() + RECENT_WINDOW_SECONDS;
                        boolean covers = currentTs >= e.startTs() && currentTs <= end;
                        boolean recent = currentTs - e.startTs() <= RECENT_WINDOW_SECONDS;
                        if (covers) {
                            boost += 2.0;
                            contextual = deictic;
                        } else if (recent) {
                            boost += 1.0;
                            contextual = deictic;
                        }
                    }
                }
                case GLOSSARY -> {
                    if (meaning) {
                        boost += 1.5;
                    }
                }
                case SCENE -> {
                    if (recap) {
                        boost += 1.5;
                        contextual = deictic;
                    }
                }
                case TRIVIA -> {
                    if (trivia) {
                        boost += 1.5;
                    }
                }
            }
            if (lexical > 0 || contextual) {
                scored.add(new Scored(e, lexical + boost));
            }
        }

        if (scored.isEmpty()) {
            return new Result(DECLINE, List.of(), List.of(), false);
        }
        Comparator<Scored> byScore = Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(s -> -s.entry().startTs())
                .thenComparing(s -> s.entry().id());
        scored.sort(byScore);
        double top = scored.get(0).score();
        if (top < MIN_SCORE) {
            return new Result(DECLINE, List.of(), List.of(), false);
        }

        List<KnowledgeEntry> picked = new ArrayList<>();
        for (Scored s : scored) {
            if (picked.size() >= Math.max(1, maxSources)) {
                break;
            }
            if (s.score() >= top * 0.5) {
                picked.add(s.entry());
            }
        }
        if (recap) {
            // present story beats chronologically
            picked.sort(Comparator.comparingInt(KnowledgeEntry::startTs));
        }

        StringBuilder answer = new StringBuilder();
        List<String> ids = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        List<String> cites = new ArrayList<>();
        for (KnowledgeEntry e : picked) {
            if (answer.length() > 0) {
                answer.append(' ');
            }
            answer.append(render(e));
            ids.add(e.id());
            sources.add(new Source(e.id(), e.type(), e.startTs(), e.title(), excerpt(e.text())));
            cites.add(label(e.type()) + " \"" + e.title() + "\" (" + TimeFmt.hms(e.startTs()) + ")");
        }
        answer.append(" [Source: ").append(String.join("; ", cites)).append("]");
        return new Result(answer.toString(), ids, sources, true);
    }

    /** Renders a proactive trivia card for an entry (used by the trivia scheduler). */
    public static String renderCard(KnowledgeEntry e) {
        return switch (e.type()) {
            case TRIVIA -> e.text();
            case CHARACTER -> e.title() + ": " + e.text();
            case GLOSSARY -> e.title() + " - " + e.text();
            case SCENE -> e.title() + ": " + e.text();
        };
    }

    // --- internals -----------------------------------------------------------------------------

    private static String render(KnowledgeEntry e) {
        return switch (e.type()) {
            case CHARACTER -> "That's " + e.title() + ", first introduced at " + TimeFmt.hms(e.startTs())
                    + ". " + e.text();
            case GLOSSARY -> e.title() + ": " + e.text();
            case SCENE -> "Around " + TimeFmt.hms(e.startTs()) + " (" + e.title() + "): " + e.text();
            case TRIVIA -> "Fun fact (from " + TimeFmt.hms(e.startTs()) + "): " + e.text();
        };
    }

    private static String label(KnowledgeEntry.Type t) {
        return switch (t) {
            case SCENE -> "Scene";
            case CHARACTER -> "Character";
            case GLOSSARY -> "Glossary";
            case TRIVIA -> "Trivia";
        };
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 140 ? text : text.substring(0, 137) + "...";
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String s : a) {
            if (b.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static void addTokens(Map<String, Double> weights, String text, double weight) {
        if (text == null) {
            return;
        }
        for (String t : rawTokens(text.toLowerCase(Locale.ROOT))) {
            if (!STOP.contains(t)) {
                weights.merge(stem(t), weight, Double::sum);
            }
        }
    }

    static List<String> rawTokens(String lower) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    static String stem(String t) {
        if (t.length() > 4 && t.endsWith("ies")) {
            return t.substring(0, t.length() - 3) + "y";
        }
        if (t.length() > 5 && t.endsWith("ing")) {
            return t.substring(0, t.length() - 3);
        }
        if (t.length() > 4 && t.endsWith("ed")) {
            return t.substring(0, t.length() - 2);
        }
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) {
            return t.substring(0, t.length() - 1);
        }
        return t;
    }
}
