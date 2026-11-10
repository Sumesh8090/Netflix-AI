package com.watchspaces.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.watchspaces.core.KnowledgeEntry;
import com.watchspaces.core.TimelineValidator;
import com.watchspaces.entity.TimelineEvent;
import com.watchspaces.entity.TimelineEvent.EventType;
import com.watchspaces.entity.Title;
import com.watchspaces.entity.VariationOption;
import com.watchspaces.repo.RoomVariationRepository;
import com.watchspaces.repo.TimelineEventRepository;
import com.watchspaces.repo.TitleRepository;
import com.watchspaces.util.InputSanitizer;
import com.watchspaces.web.ApiException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Timeline ingestion + indexing. Timelines are read far more than written, so the retrieval view
 * (KnowledgeEntry list) and the variation points of a title are cached in memory and invalidated on upload.
 */
@Service
public class TimelineService {

    public record IngestSummary(long titleId, int events, int variationPoints) { }

    public record Validation(boolean valid, List<TimelineValidator.Problem> errors) { }

    private static final int MAX_BODY_CHARS = 1_000_000;

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    private final TitleRepository titles;
    private final TimelineEventRepository events;
    private final RoomVariationRepository roomVariations;

    private final Map<Long, List<KnowledgeEntry>> knowledgeCache = new ConcurrentHashMap<>();
    private final Map<Long, List<TimelineEvent>> variationCache = new ConcurrentHashMap<>();

    public TimelineService(TitleRepository titles, TimelineEventRepository events, RoomVariationRepository roomVariations) {
        this.titles = titles;
        this.events = events;
        this.roomVariations = roomVariations;
    }

    // ---- reads ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TimelineEvent> range(long titleId, int from, int to) {
        requireTitle(titleId);
        return events.findInRange(titleId, from, to);
    }

    /** Retrieval view of a title: everything except variation points. Cached (no transaction: hit every trivia tick). */
    public List<KnowledgeEntry> knowledge(long titleId) {
        return knowledgeCache.computeIfAbsent(titleId, id -> {
            List<KnowledgeEntry> out = new ArrayList<>();
            for (TimelineEvent e : events.findByTitle_IdOrderByTsSecondsAscIdAsc(id)) {
                if (e.eventType == EventType.VARIATION_POINT) {
                    continue;
                }
                out.add(new KnowledgeEntry("evt_" + e.id, KnowledgeEntry.Type.valueOf(e.eventType.name()), e.tsSeconds,
                        e.endTsSeconds, e.name == null ? "" : e.name, e.body == null ? "" : e.body, keywordList(e.keywords)));
            }
            return List.copyOf(out);
        });
    }

    public List<TimelineEvent> variationPoints(long titleId) {
        return variationCache.computeIfAbsent(titleId, id -> events.findByTitle_IdOrderByTsSecondsAscIdAsc(id).stream()
                .filter(e -> e.eventType == EventType.VARIATION_POINT).toList());
    }

    public TimelineEvent variationPoint(long titleId, String variationId) {
        return variationPoints(titleId).stream().filter(e -> variationId.equals(e.variationId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Variation point " + variationId));
    }

    // ---- ingestion --------------------------------------------------------------------------------

    public Validation validate(long titleId, String raw) {
        Title t = requireTitle(titleId);
        Object parsed = parse(raw);
        List<TimelineValidator.Problem> problems = TimelineValidator.validate(parsed, t.durationSeconds);
        return new Validation(problems.isEmpty(), problems);
    }

    /** Validates then atomically replaces the title's whole timeline. Rejects with 400 and a per-field error list. */
    @Transactional
    @SuppressWarnings("unchecked")
    public IngestSummary replace(long titleId, String raw) {
        Title t = requireTitle(titleId);
        Object parsed = parse(raw);
        List<TimelineValidator.Problem> problems = TimelineValidator.validate(parsed, t.durationSeconds);
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIMELINE", "Timeline failed validation", problems);
        }
        if (roomVariations.existsByEvent_Title_Id(titleId)) {
            throw ApiException.conflict("TIMELINE_IN_USE",
                    "This title's variation points already have votes recorded in a Watch Space; the timeline can no longer be replaced");
        }
        events.deleteAll(events.findByTitle_IdOrderByTsSecondsAscIdAsc(titleId));
        events.flush();

        List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<String, Object>) parsed).get("events");
        List<TimelineEvent> toSave = new ArrayList<>();
        int variations = 0;
        for (Map<String, Object> m : list) {
            TimelineEvent e = new TimelineEvent();
            e.title = t;
            e.tsSeconds = ((Number) m.get("ts")).intValue();
            e.endTsSeconds = m.get("endTs") == null ? null : ((Number) m.get("endTs")).intValue();
            String type = (String) m.get("type");
            e.eventType = EventType.valueOf(type.toUpperCase());
            e.name = InputSanitizer.plain((String) m.get("title"));
            e.body = InputSanitizer.plain((String) m.get("text"));
            Object kws = m.get("keywords");
            if (kws instanceof List<?> l) {
                e.keywords = l.stream().map(o -> InputSanitizer.plain(String.valueOf(o)).replace(",", " "))
                        .collect(Collectors.joining(","));
            }
            if (e.eventType == EventType.VARIATION_POINT) {
                variations++;
                Map<String, Object> v = (Map<String, Object>) m.get("variation");
                e.variationId = InputSanitizer.plain((String) v.get("variationId"));
                e.variationKind = TimelineEvent.VariationKind.valueOf(((String) v.get("kind")).toUpperCase());
                int pos = 0;
                for (Map<String, Object> o : (List<Map<String, Object>>) v.get("options")) {
                    VariationOption vo = new VariationOption();
                    vo.event = e;
                    vo.optionKey = InputSanitizer.plain((String) o.get("id"));
                    vo.label = InputSanitizer.plain((String) o.get("label"));
                    vo.locale = o.get("locale") == null ? null : InputSanitizer.plain((String) o.get("locale")).toLowerCase();
                    vo.assetRef = o.get("assetRef") == null ? null : InputSanitizer.plain(String.valueOf(o.get("assetRef")));
                    vo.position = pos++;
                    e.options.add(vo);
                }
            }
            toSave.add(e);
        }
        events.saveAll(toSave);
        invalidate(titleId);
        return new IngestSummary(titleId, toSave.size(), variations);
    }

    public void invalidate(long titleId) {
        knowledgeCache.remove(titleId);
        variationCache.remove(titleId);
    }

    // ---- internals ---------------------------------------------------------------------------------

    private Title requireTitle(long titleId) {
        return titles.findById(titleId).orElseThrow(() -> ApiException.notFound("Title"));
    }

    private Object parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARSE_ERROR", "Request body is empty");
        }
        if (raw.length() > MAX_BODY_CHARS) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "Timeline document is too large");
        }
        String trimmed = raw.strip();
        try {
            return (trimmed.startsWith("{") ? json : yaml).readValue(trimmed, Object.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARSE_ERROR",
                    "Could not parse timeline as JSON/YAML: " + e.getOriginalMessage());
        }
    }

    private static List<String> keywordList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
