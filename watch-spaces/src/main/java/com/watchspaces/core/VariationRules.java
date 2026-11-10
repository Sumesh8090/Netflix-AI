package com.watchspaces.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure rules for pre-authored content variations (no Spring, unit-tested).
 *
 * Options are always passed in position order; index 0 is the ORIGINAL option.
 *  - Locale swap: a viewer whose locale matches an option's locale ("hi-IN" matches "hi") gets that option, otherwise the original.
 *  - Group vote: most votes wins; a tie goes to the earliest option (so the original wins ties); zero votes means the original.
 */
public final class VariationRules {

    private VariationRules() { }

    private static final java.util.regex.Pattern LOCALE = java.util.regex.Pattern.compile("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})?");

    /** Lower-case, '-' separated locale, or null when the input is not a plausible locale tag. */
    public static String normalizeLocale(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (!LOCALE.matcher(t).matches()) {
            return null;
        }
        return t.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Index of the option for the viewer's locale, or 0 (the original) when nothing matches. */
    public static int pickForLocale(List<String> optionLocales, String viewerLocale) {
        if (viewerLocale == null || viewerLocale.isBlank()) {
            return 0;
        }
        String want = viewerLocale.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        String lang = want.contains("-") ? want.substring(0, want.indexOf('-')) : want;
        // exact match first ("pt-br"), then language only ("pt")
        for (int i = 0; i < optionLocales.size(); i++) {
            String l = optionLocales.get(i);
            if (l != null && l.toLowerCase(Locale.ROOT).equals(want)) {
                return i;
            }
        }
        for (int i = 0; i < optionLocales.size(); i++) {
            String l = optionLocales.get(i);
            if (l != null && l.toLowerCase(Locale.ROOT).equals(lang)) {
                return i;
            }
        }
        return 0;
    }

    /** Votes per option key, in option order, including options with zero votes. Votes for unknown keys are ignored. */
    public static Map<String, Integer> tally(List<String> optionKeys, Collection<String> votes) {
        Map<String, Integer> t = new LinkedHashMap<>();
        for (String k : optionKeys) {
            t.put(k, 0);
        }
        for (String v : votes) {
            if (t.containsKey(v)) {
                t.merge(v, 1, Integer::sum);
            }
        }
        return t;
    }

    public static String winner(List<String> optionKeys, Collection<String> votes) {
        if (optionKeys.isEmpty()) {
            throw new IllegalArgumentException("a variation point needs at least one option");
        }
        Map<String, Integer> t = tally(optionKeys, votes);
        String best = optionKeys.get(0);
        int bestCount = -1;
        for (String k : new ArrayList<>(t.keySet())) {
            if (t.get(k) > bestCount) { // strict ">" keeps the earliest option on ties
                bestCount = t.get(k);
                best = k;
            }
        }
        return best;
    }
}
