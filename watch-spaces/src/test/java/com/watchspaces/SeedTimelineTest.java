package com.watchspaces;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchspaces.core.GroundedQa;
import com.watchspaces.core.KnowledgeEntry;
import com.watchspaces.core.TimelineValidator;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** The shipped sample timeline must itself pass the documented schema, and answer representative questions. */
class SeedTimelineTest {
    @SuppressWarnings("unchecked")
    private Map<String, Object> load() throws Exception {
        try (InputStream in = new ClassPathResource("seed/harbor-case-timeline.json").getInputStream()) {
            return new ObjectMapper().readValue(in, Map.class);
        }
    }

    @Test
    void seedTimelineIsValid() throws Exception {
        assertEquals(List.of(), TimelineValidator.validate(load(), 1800));
    }

    @Test
    @SuppressWarnings("unchecked")
    void seedTimelineAnswersCoreQuestionsWithCitations() throws Exception {
        List<Map<String, Object>> events = (List<Map<String, Object>>) load().get("events");
        List<KnowledgeEntry> es = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> e : events) {
            i++;
            if ("variation_point".equals(e.get("type"))) {
                continue;
            }
            es.add(new KnowledgeEntry("evt_" + i, KnowledgeEntry.Type.valueOf(((String) e.get("type")).toUpperCase()),
                    (Integer) e.get("ts"), (Integer) e.get("endTs"), (String) e.getOrDefault("title", ""),
                    (String) e.get("text"), (List<String>) e.getOrDefault("keywords", List.of())));
        }
        GroundedQa.Result who = GroundedQa.answer(es, "Who is the detective that just walked in?", 1000, 2);
        assertTrue(who.answer().contains("Rios"));
        assertTrue(who.grounded());
        assertFalse(GroundedQa.answer(es, "What is the capital of France?", 1000, 2).grounded());
        // spoiler safety on real data: the arrest happens at 1500
        assertFalse(GroundedQa.answer(es, "Who was arrested?", 600, 2).answer().contains("arrested at the harbour office"));
    }
}
