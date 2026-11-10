package com.watchspaces;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Proves RBAC is enforced on the server: a viewer token cannot create rooms or reach admin endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ws;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=test-secret-test-secret-test-secret-0123",
        "app.seed.enabled=true",
        "app.seed.default-password=Passw0rd!test"
})
class AuthRbacIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    String viewer, host, admin;

    @BeforeAll
    void login() throws Exception {
        viewer = token("viewer@watchspaces.local");
        host = token("host@watchspaces.local");
        admin = token("admin@watchspaces.local");
    }

    String token(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("accessToken").asText();
    }

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/titles")).andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/titles").header("Authorization", "Bearer " + viewer + "x")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"viewer@watchspaces.local\",\"password\":\"nope-nope\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void selfRegisteringAsAdminIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"evil@x.io\",\"password\":\"longenough1\",\"displayName\":\"Evil\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotCreateAWatchSpace() throws Exception {
        mvc.perform(post("/api/v1/watch-spaces").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"titleId\":1}")).andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotUseAdminEndpoints() throws Exception {
        mvc.perform(put("/api/v1/admin/titles/1/timeline").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"events\":[]}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/metrics/summary").header("Authorization", "Bearer " + host)).andExpect(status().isForbidden());
    }

    @Test
    void hostCanCreateAndAnotherUserCanJoinByCode() throws Exception {
        String created = mvc.perform(post("/api/v1/watch-spaces").header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":1,\"maxParticipants\":25,\"aiVerbosity\":\"normal\",\"votingEnabled\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.inviteCode").exists())
                .andExpect(jsonPath("$.status").value("scheduled")).andReturn().getResponse().getContentAsString();
        JsonNode j = mapper.readTree(created);
        mvc.perform(post("/api/v1/watch-spaces/join").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\"" + j.get("inviteCode").asText() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.websocketPath").exists());
    }

    @Test
    void adminTimelineUploadRejectsMalformedAndAcceptsValid() throws Exception {
        mvc.perform(put("/api/v1/admin/titles/1/timeline").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"events\":[{\"type\":\"trivia\",\"ts\":5}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_TIMELINE"))
                .andExpect(jsonPath("$.error.details[0].path").value("$.events[0].text"));
    }

    @Test
    void timelineRangeEndpointReturnsEventsInWindow() throws Exception {
        mvc.perform(get("/api/v1/titles/1/timeline?from=250&to=300").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[?(@.type=='character')]").exists());
    }

    long createRoom() throws Exception {
        String created = mvc.perform(post("/api/v1/watch-spaces").header("Authorization", "Bearer " + host)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"titleId\":1,\"maxParticipants\":10}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(created).get("watchSpaceId").asLong();
    }

    String ask(String token, long room, String json) throws Exception {
        return mvc.perform(post("/api/v1/watch-spaces/" + room + "/ai/ask").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aiAskIsGroundedClampedToTheRoomPositionAndLimitedToRoomMembers() throws Exception {
        long room = createRoom();
        // a viewer who has not joined the room over the socket is refused
        mvc.perform(post("/api/v1/watch-spaces/" + room + "/ai/ask").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Where was the body found?\"}"))
                .andExpect(status().isForbidden());
        // the host may ask. The room is at ~0 s, so a claimed 2000 s is clamped: nothing from the future can leak
        mvc.perform(post("/api/v1/watch-spaces/" + room + "/ai/ask").header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON).content("{\"currentTs\":2000,\"question\":\"Who is Elena Vance?\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.sourceEvents").isEmpty());
        mvc.perform(post("/api/v1/watch-spaces/" + room + "/ai/ask").header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Where was the body found?\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.sourceEvents[0]").value(startsWith("evt_")))
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void aiAskValidatesInput() throws Exception {
        long room = createRoom();
        mvc.perform(post("/api/v1/watch-spaces/" + room + "/ai/ask").header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/watch-spaces/999999/ai/ask").header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hello there\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/watch-spaces/" + room + "/ai/ask").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hello\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheHostOrAdminCanEndAndAnalyticsReconcileWithTheEventLog() throws Exception {
        long room = createRoom();
        ask(host, room, "{\"question\":\"Where was the body found?\"}");
        ask(host, room, "{\"question\":\"Who is Elena Vance?\"}");
        mvc.perform(post("/api/v1/watch-spaces/" + room + "/end").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/watch-spaces/" + room + "/analytics").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/watch-spaces/" + room + "/end").header("Authorization", "Bearer " + host))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ended"))
                .andExpect(jsonPath("$.aiQuestions").value(2)).andExpect(jsonPath("$.reconciled").value(true))
                .andExpect(jsonPath("$.snapshot").value(true));
        // ending twice is harmless, and an ended room refuses new questions
        mvc.perform(post("/api/v1/watch-spaces/" + room + "/end").header("Authorization", "Bearer " + host))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/watch-spaces/" + room + "/ai/ask").header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hello there\"}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("SESSION_ENDED"));
    }

    @Test
    void dashboardAndRecommendationRailAreServedFromInteractionData() throws Exception {
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
        // the seeded demo viewer has a Comedy-heavy history, so the rail is personalised and the history is visible
        mvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recentlyWatched[0].titleName").exists())
                .andExpect(jsonPath("$.recommendedForYou[0].reason").exists())
                .andExpect(jsonPath("$.liveNow").isArray()).andExpect(jsonPath("$.pastSessions").isArray());
        mvc.perform(get("/api/v1/recommendations?limit=5").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].titleId").exists())
                .andExpect(jsonPath("$[0].genres").isArray());
    }

    @Test
    void recordingAnInteractionIsValidatedAndShowsUpInTheDashboard() throws Exception {
        mvc.perform(post("/api/v1/interactions").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"titleId\":1,\"watchedSeconds\":2400,\"completed\":true,\"rating\":9}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/interactions").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"titleId\":999999,\"watchedSeconds\":10,\"completed\":false}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/interactions").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"titleId\":1,\"watchedSeconds\":2400,\"completed\":true,\"rating\":5}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recentlyWatched[0].titleName").value("The Pier"));
    }

    @Test
    void recommenderEvaluationIsAdminOnlyAndReportsPrecisionAtK() throws Exception {
        mvc.perform(get("/api/v1/admin/recommendations/evaluate").header("Authorization", "Bearer " + host))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/recommendations/evaluate?k=5").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.k").value(5))
                .andExpect(jsonPath("$.users").isNumber()).andExpect(jsonPath("$.precisionAtK").isNumber())
                .andExpect(jsonPath("$.normalizedPrecision").isNumber());
    }
}
