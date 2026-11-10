package com.watchspaces.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a parsed timeline document (the result of parsing JSON or YAML into Maps/Lists) against the
 * documented schema (see docs/TIMELINE_SCHEMA.md). Returns every problem with a JSON-path-like location so the
 * admin upload endpoint can return a clear error message.
 *
 * <pre>
 * { "schemaVersion": 1,
 *   "events": [
 *     { "type": "scene|character|trivia|glossary|variation_point",
 *       "ts": 0, "endTs": 120,                 // endTs optional, must be >= ts
 *       "title": "...",                        // required for scene, character, glossary
 *       "text": "...",                         // required except variation_point
 *       "keywords": ["..."],                   // optional
 *       "variation": {                         // required for variation_point
 *         "variationId": "v_01", "kind": "subtitle|narrative",
 *         "options": [ {"id":"a","label":"...","locale":"hi","assetRef":"..."} , ... ] } } ] }
 * </pre>
 */
public final class TimelineValidator {

    public record Problem(String path, String message) { }

    public static final Set<String> TYPES = Set.of("scene", "character", "trivia", "glossary", "variation_point");
    public static final Set<String> VARIATION_KINDS = Set.of("subtitle", "narrative");
    public static final int MAX_EVENTS = 2000;
    public static final int MAX_TEXT = 2000;

    private TimelineValidator() { }

    @SuppressWarnings("unchecked")
    public static List<Problem> validate(Object root, int durationSeconds) {
        List<Problem> problems = new ArrayList<>();
        if (!(root instanceof Map<?, ?> doc)) {
            problems.add(new Problem("$", "Timeline must be an object with an 'events' array"));
            return problems;
        }
        Object eventsObj = doc.get("events");
        if (!(eventsObj instanceof List<?> events)) {
            problems.add(new Problem("$.events", "'events' is required and must be an array"));
            return problems;
        }
        if (events.isEmpty()) {
            problems.add(new Problem("$.events", "'events' must contain at least one event"));
        }
        if (events.size() > MAX_EVENTS) {
            problems.add(new Problem("$.events", "Too many events (max " + MAX_EVENTS + ")"));
            return problems;
        }
        Set<String> variationIds = new HashSet<>();
        for (int i = 0; i < events.size(); i++) {
            String p = "$.events[" + i + "]";
            if (!(events.get(i) instanceof Map<?, ?> ev)) {
                problems.add(new Problem(p, "Event must be an object"));
                continue;
            }
            Object typeObj = ev.get("type");
            String type = typeObj instanceof String s ? s : null;
            if (type == null || !TYPES.contains(type)) {
                problems.add(new Problem(p + ".type", "type must be one of " + new java.util.TreeSet<>(TYPES)));
                continue;
            }
            Integer ts = intOf(ev.get("ts"));
            if (ts == null) {
                problems.add(new Problem(p + ".ts", "ts is required and must be an integer number of seconds"));
            } else if (ts < 0 || ts > durationSeconds) {
                problems.add(new Problem(p + ".ts", "ts must be between 0 and the title duration (" + durationSeconds + ")"));
            }
            if (ev.get("endTs") != null) {
                Integer end = intOf(ev.get("endTs"));
                if (end == null) {
                    problems.add(new Problem(p + ".endTs", "endTs must be an integer"));
                } else if (ts != null && end < ts) {
                    problems.add(new Problem(p + ".endTs", "endTs must be >= ts"));
                } else if (end > durationSeconds) {
                    problems.add(new Problem(p + ".endTs", "endTs must not exceed the title duration"));
                }
            }
            boolean needsTitle = type.equals("scene") || type.equals("character") || type.equals("glossary");
            if (needsTitle && isBlank(ev.get("title"))) {
                problems.add(new Problem(p + ".title", "title is required for type '" + type + "'"));
            }
            if (!type.equals("variation_point")) {
                if (isBlank(ev.get("text"))) {
                    problems.add(new Problem(p + ".text", "text is required for type '" + type + "'"));
                } else if (((String) ev.get("text")).length() > MAX_TEXT) {
                    problems.add(new Problem(p + ".text", "text must be at most " + MAX_TEXT + " characters"));
                }
            }
            Object kw = ev.get("keywords");
            if (kw != null) {
                if (!(kw instanceof List<?> kws)) {
                    problems.add(new Problem(p + ".keywords", "keywords must be an array of strings"));
                } else {
                    for (int k = 0; k < kws.size(); k++) {
                        if (!(kws.get(k) instanceof String)) {
                            problems.add(new Problem(p + ".keywords[" + k + "]", "keyword must be a string"));
                        }
                    }
                }
            }
            if (type.equals("variation_point")) {
                validateVariation(ev.get("variation"), p + ".variation", variationIds, problems);
            }
        }
        return problems;
    }

    private static void validateVariation(Object v, String p, Set<String> variationIds, List<Problem> problems) {
        if (!(v instanceof Map<?, ?> var)) {
            problems.add(new Problem(p, "variation object is required for type 'variation_point'"));
            return;
        }
        Object idObj = var.get("variationId");
        if (isBlank(idObj)) {
            problems.add(new Problem(p + ".variationId", "variationId is required"));
        } else if (!variationIds.add((String) idObj)) {
            problems.add(new Problem(p + ".variationId", "variationId '" + idObj + "' is duplicated"));
        }
        Object kind = var.get("kind");
        if (!(kind instanceof String ks) || !VARIATION_KINDS.contains(ks)) {
            problems.add(new Problem(p + ".kind", "kind must be one of " + new java.util.TreeSet<>(VARIATION_KINDS)));
        }
        Object optsObj = var.get("options");
        if (!(optsObj instanceof List<?> opts) || opts.size() < 2) {
            problems.add(new Problem(p + ".options", "options must be an array with at least 2 entries"));
            return;
        }
        Set<String> optionIds = new HashSet<>();
        for (int i = 0; i < opts.size(); i++) {
            String op = p + ".options[" + i + "]";
            if (!(opts.get(i) instanceof Map<?, ?> o)) {
                problems.add(new Problem(op, "option must be an object"));
                continue;
            }
            if (isBlank(o.get("id"))) {
                problems.add(new Problem(op + ".id", "id is required"));
            } else if (!optionIds.add((String) o.get("id"))) {
                problems.add(new Problem(op + ".id", "option id '" + o.get("id") + "' is duplicated"));
            }
            if (isBlank(o.get("label"))) {
                problems.add(new Problem(op + ".label", "label is required"));
            }
            if (o.get("locale") != null && !(o.get("locale") instanceof String)) {
                problems.add(new Problem(op + ".locale", "locale must be a string such as 'hi' or 'es'"));
            }
        }
    }

    private static boolean isBlank(Object o) {
        return !(o instanceof String s) || s.isBlank();
    }

    private static Integer intOf(Object o) {
        if (o instanceof Integer i) {
            return i;
        }
        if (o instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return l.intValue();
        }
        if (o instanceof Double d && d == Math.rint(d)) {
            return d.intValue();
        }
        return null;
    }
}
