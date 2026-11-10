package com.watchspaces.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchspaces.entity.Role;
import com.watchspaces.entity.Title;
import com.watchspaces.entity.User;
import com.watchspaces.repo.TitleRepository;
import com.watchspaces.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Server-side RBAC and auth, exercised through the real security filter chain (H2 in-memory database, no seed data).
 * Every "hidden in the UI" style protection would still pass a UI test; these tests hit the HTTP layer directly.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rbac;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=x",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "app.seed.enabled=false"})
@AutoConfigureMockMvc
class RbacIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired TitleRepository titles;
    @Autowired PasswordEncoder encoder;

    private long titleId;

    @BeforeEach
    void data() {
        if (titles.count() == 0) {
            Title t = new Title();
            t.name = "T";
            t.durationSeconds = 600;
            t.genres = "Crime";
            titleId = titles.save(t).id;
        } else {
            titleId = titles.findAll().get(0).id;
        }
        if (users.findByEmailIgnoreCase("admin@t.dev").isEmpty()) {
            User a = new User();
            a.email = "admin@t.dev";
            a.displayName = "Admin";
            a.role = Role.ADMIN;
            a.passwordHash = encoder.encode("password123");
            users.save(a);
        }
    }

    private String register(String email, String role) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"password123\",\"displayName\":\"U\",\"role\":\"" + role + "\"}";
        var res = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
        if (res.andReturn().getResponse().getStatus() == 409) {
            return login(email);
        }
        res.andExpect(status().isCreated());
        return json.readTree(res.andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String login(String email) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}")).andExpect(status().isOk());
        return json.readTree(res.andReturn().getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static final String TIMELINE = "{\"events\":[{\"type\":\"trivia\",\"ts\":5,\"text\":\"A fact\"}]}";

    @Test
    void anonymousIsRejectedWith401() throws Exception {
        mvc.perform(get("/api/v1/titles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void garbageAndTamperedTokensAreRejected() throws Exception {
        mvc.perform(get("/api/v1/titles").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
        String good = register("tamper@t.dev", "VIEWER");
        mvc.perform(get("/api/v1/titles").header("Authorization", "Bearer " + good.substring(0, good.length() - 3) + "abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCannotUploadTimelineButAdminCan() throws Exception {
        String viewer = register("viewer1@t.dev", "VIEWER");
        mvc.perform(put("/api/v1/admin/titles/" + titleId + "/timeline").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content(TIMELINE)).andExpect(status().isForbidden());
        String host = register("host1@t.dev", "HOST");
        mvc.perform(put("/api/v1/admin/titles/" + titleId + "/timeline").header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON).content(TIMELINE)).andExpect(status().isForbidden());
        String admin = login("admin@t.dev");
        mvc.perform(put("/api/v1/admin/titles/" + titleId + "/timeline").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(TIMELINE)).andExpect(status().isOk());
    }

    @Test
    void viewerCannotCreateWatchSpaceButHostCan() throws Exception {
        String body = "{\"titleId\":" + titleId + ",\"maxParticipants\":10}";
        String viewer = register("viewer2@t.dev", "VIEWER");
        mvc.perform(post("/api/v1/watch-spaces").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String host = register("host2@t.dev", "HOST");
        mvc.perform(post("/api/v1/watch-spaces").header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.inviteCode").exists());
    }

    @Test
    void metricsAndAdminEndpointsAreAdminOnly() throws Exception {
        String host = register("host3@t.dev", "HOST");
        mvc.perform(get("/api/v1/metrics/summary").header("Authorization", "Bearer " + host)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/recommendations/evaluate").header("Authorization", "Bearer " + host)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/metrics/summary").header("Authorization", "Bearer " + login("admin@t.dev"))).andExpect(status().isOk());
    }

    @Test
    void cannotSelfRegisterAsAdmin() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"evil@t.dev\",\"password\":\"password123\",\"displayName\":\"E\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void privateWatchSpaceIsNotReadableByOutsiders() throws Exception {
        String host = register("host4@t.dev", "HOST");
        var created = mvc.perform(post("/api/v1/watch-spaces").header("Authorization", "Bearer " + host)
                .contentType(MediaType.APPLICATION_JSON).content("{\"titleId\":" + titleId + "}")).andExpect(status().isCreated());
        JsonNode n = json.readTree(created.andReturn().getResponse().getContentAsString());
        long id = n.get("watchSpaceId").asLong();
        String stranger = register("stranger@t.dev", "VIEWER");
        mvc.perform(get("/api/v1/watch-spaces/" + id).header("Authorization", "Bearer " + stranger)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/watch-spaces/" + id + "/analytics").header("Authorization", "Bearer " + stranger)).andExpect(status().isForbidden());
        // joining with the invite code grants read access, but never analytics (host/admin only)
        mvc.perform(post("/api/v1/watch-spaces/join").header("Authorization", "Bearer " + stranger)
                .contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\"" + n.get("inviteCode").asText() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/watch-spaces/" + id).header("Authorization", "Bearer " + stranger)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/watch-spaces/" + id + "/analytics").header("Authorization", "Bearer " + stranger)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/watch-spaces/" + id + "/end").header("Authorization", "Bearer " + stranger)).andExpect(status().isForbidden());
    }

    @Test
    void invalidTimelineIsRejectedWithFieldErrors() throws Exception {
        String admin = login("admin@t.dev");
        mvc.perform(put("/api/v1/admin/titles/" + titleId + "/timeline").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"events\":[{\"type\":\"nope\",\"ts\":1}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_TIMELINE"))
                .andExpect(jsonPath("$.error.details[0].path").value("$.events[0].type"));
        mvc.perform(put("/api/v1/admin/titles/" + titleId + "/timeline").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PARSE_ERROR"));
    }

    @Test
    void refreshTokenRotatesAndOldOneStopsWorking() throws Exception {
        var reg = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"rot@t.dev\",\"password\":\"password123\",\"displayName\":\"R\"}")).andExpect(status().isCreated());
        String refresh = json.readTree(reg.andReturn().getResponse().getContentAsString()).get("refreshToken").asText();
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIs401WithoutRevealingWhichPartWasWrong() throws Exception {
        register("pw@t.dev", "VIEWER");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"pw@t.dev\",\"password\":\"wrongwrong\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@t.dev\",\"password\":\"wrongwrong\"}")).andExpect(status().isUnauthorized());
    }
}
