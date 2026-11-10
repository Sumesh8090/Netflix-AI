package com.watchspaces.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimelineValidatorTest {
    private final ObjectMapper json = new ObjectMapper();

    private List<TimelineValidator.Problem> check(String doc) throws Exception {
        return TimelineValidator.validate(json.readValue(doc, Object.class), 2400);
    }

    @Test
    void shippedSeedTimelineIsValid() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/seed/timeline-the-pier.json")) {
            assertNotNull(in);
            assertEquals(List.of(), TimelineValidator.validate(json.readValue(in, Object.class), 2400));
        }
    }

    @Test
    void secondSeedTimelineIsValidForItsTitleDuration() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/seed/harbor-case-timeline.json")) {
            assertNotNull(in);
            assertEquals(List.of(), TimelineValidator.validate(json.readValue(in, Object.class), 1800));
        }
    }

    @Test
    void yamlIsAcceptedToo() throws Exception {
        String yaml = "events:\n  - type: trivia\n    ts: 10\n    text: hello\n";
        Object parsed = new ObjectMapper(new YAMLFactory()).readValue(yaml, Object.class);
        assertEquals(List.of(), TimelineValidator.validate(parsed, 100));
    }

    @Test
    void rootMustBeAnObjectWithEvents() throws Exception {
        assertEquals("$", TimelineValidator.validate(List.of(), 100).get(0).path());
        assertEquals("$.events", check("{}").get(0).path());
        assertEquals("$.events", check("{\"events\":[]}").get(0).path());
    }

    @Test
    void errorsNameTheOffendingField() throws Exception {
        var p = check("{\"events\":[{\"type\":\"trivia\",\"ts\":5}]}");
        assertEquals("$.events[0].text", p.get(0).path());
    }

    @Test
    void rejectsUnknownTypeBadTimestampsAndEndBeforeStart() throws Exception {
        assertEquals("$.events[0].type", check("{\"events\":[{\"type\":\"nope\",\"ts\":1}]}").get(0).path());
        assertTrue(check("{\"events\":[{\"type\":\"trivia\",\"ts\":99999,\"text\":\"x\"}]}").stream()
                .anyMatch(x -> x.path().equals("$.events[0].ts")));
        assertTrue(check("{\"events\":[{\"type\":\"scene\",\"ts\":50,\"endTs\":10,\"title\":\"t\",\"text\":\"x\"}]}").stream()
                .anyMatch(x -> x.path().equals("$.events[0].endTs")));
    }

    @Test
    void variationPointsNeedTwoUniqueOptionsAndAUniqueId() throws Exception {
        String one = "{\"events\":[{\"type\":\"variation_point\",\"ts\":5,\"variation\":{\"variationId\":\"v\",\"kind\":\"subtitle\","
                + "\"options\":[{\"id\":\"a\",\"label\":\"x\"}]}}]}";
        assertTrue(check(one).stream().anyMatch(x -> x.path().endsWith(".options")));
        String dup = "{\"events\":[{\"type\":\"variation_point\",\"ts\":5,\"variation\":{\"variationId\":\"v\",\"kind\":\"narrative\","
                + "\"options\":[{\"id\":\"a\",\"label\":\"x\"},{\"id\":\"a\",\"label\":\"y\"}]}}]}";
        assertTrue(check(dup).stream().anyMatch(x -> x.message().contains("duplicated")));
    }
}
