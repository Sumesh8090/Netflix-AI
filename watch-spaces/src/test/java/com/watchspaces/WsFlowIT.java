package com.watchspaces;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchspaces.entity.Role;
import com.watchspaces.entity.Title;
import com.watchspaces.entity.WatchSpace;
import com.watchspaces.repo.InteractionRepository;
import com.watchspaces.repo.TitleRepository;
import com.watchspaces.security.AuthUser;
import com.watchspaces.service.AuthService;
import com.watchspaces.service.SessionService;
import com.watchspaces.service.TimelineService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end over real sockets: two or three scripted clients exchange exactly the events a browser would.
 * Run with {@code mvn test} (it is a normal test; it takes ~10 s because of real timers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:wsflow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=x",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=test-secret-test-secret-test-secret-1234",
        "app.seed.enabled=false",
        "app.realtime.host-grace-ms=800", "app.realtime.reconnect-grace-ms=300", "app.realtime.trivia-tick-ms=100"})
class WsFlowIT {
    private static final String TIMELINE = """
            {"events":[
              {"type":"scene","ts":0,"endTs":100,"title":"Opening","text":"A boat leaves the harbour.","keywords":["boat","harbour"]},
              {"type":"character","ts":20,"endTs":600,"title":"Detective Rios","text":"The victim's former partner.","keywords":["rios","detective","partner"]},
              {"type":"trivia","ts":12,"text":"Filmed on a real dock.","keywords":["filmed","dock"]},
              {"type":"variation_point","ts":30,"variation":{"variationId":"v_x","kind":"narrative","options":[
                 {"id":"a","label":"Original"},{"id":"b","label":"Alternate"}]}},
              {"type":"variation_point","ts":40,"variation":{"variationId":"v_sub","kind":"subtitle","options":[
                 {"id":"a","label":"Hello"},{"id":"b","label":"Hola","locale":"es"}]}}
            ]}""";

    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired SessionService sessions;
    @Autowired TimelineService timeline;
    @Autowired TitleRepository titles;
    @Autowired InteractionRepository interactionRepo;
    @Autowired ObjectMapper json;

    // ---- tiny scripted client ---------------------------------------------------------------------

    static final class Client implements WebSocket.Listener {
        final BlockingQueue<JsonNode> in = new LinkedBlockingQueue<>();
        final ObjectMapper om;
        WebSocket ws;
        private final StringBuilder buf = new StringBuilder();
        volatile int closeCode = -1;

        Client(ObjectMapper om) {
            this.om = om;
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                try {
                    in.add(om.readTree(buf.toString()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                buf.setLength(0);
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            closeCode = code;
            return null;
        }

        void send(String event, String payloadJson) {
            ws.sendText("{\"event\":\"" + event + "\",\"payload\":" + payloadJson + "}", true).join();
        }

        /** Waits for the next message of this event type, discarding others. */
        JsonNode expect(String event, long timeoutMs) throws InterruptedException {
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = end - System.currentTimeMillis();
                JsonNode n = left <= 0 ? null : in.poll(left, TimeUnit.MILLISECONDS);
                assertNotNull(n, "timed out waiting for " + event);
                if (event.equals(n.get("event").asText())) {
                    return n.get("payload");
                }
            }
        }

        boolean sawWithin(String event, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                JsonNode n = in.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (n != null && event.equals(n.get("event").asText())) {
                    return true;
                }
            }
            return false;
        }
    }

    private Client connect(long wsId, String token) {
        Client c = new Client(json);
        c.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/watch-spaces/" + wsId + "?token=" + token), c).join();
        c.ws.request(1);
        return c;
    }

    private record Room(long id, String code, long hostId, String hostToken, long titleId) { }

    private AuthService.Tokens user(String email, Role role, String locale) {
        return auth.register(email, "password123", email, role, locale);
    }

    private Room newRoom(String tag, boolean voting) {
        Title t = new Title();
        t.name = "Test " + tag;
        t.durationSeconds = 600;
        t.genres = "Crime";
        t = titles.save(t);
        timeline.replace(t.id, TIMELINE);
        AuthService.Tokens host = user(tag + "-host@t.dev", Role.HOST, "en");
        WatchSpace w = sessions.create(new AuthUser(host.user().id(), host.user().email(), Role.HOST),
                new SessionService.CreateRequest(t.id, null, 5, "chatty", voting, true, null));
        return new Room(w.id, w.inviteCode, host.user().id(), host.accessToken(), t.id);
    }

    private Client joinAs(Room r, AuthService.Tokens t) {
        sessions.joinByCode(new AuthUser(t.user().id(), t.user().email(), Role.VIEWER), r.code());
        return connect(r.id(), t.accessToken());
    }

    // ---- tests ----------------------------------------------------------------------------------------

    @Test
    void joinSnapshotPlaybackSyncAndHostOnlyControl() throws Exception {
        Room r = newRoom("sync", false);
        Client host = connect(r.id(), r.hostToken());
        JsonNode hostState = host.expect("room.state", 3000);
        assertEquals(r.hostId(), hostState.get("yourUserId").asLong());
        assertEquals(r.hostId(), hostState.get("hostUserId").asLong());

        host.send("room.playback.command", "{\"action\":\"play\",\"positionSeconds\":100}");
        host.expect("room.playback.update", 2000);

        AuthService.Tokens v = user("sync-v@t.dev", Role.VIEWER, "en");
        long t0 = System.currentTimeMillis();
        Client viewer = joinAs(r, v);
        JsonNode st = viewer.expect("room.state", 3000);
        assertNotEquals(st.get("yourUserId").asLong(), st.get("hostUserId").asLong());
        assertEquals("playing", st.get("playback").get("playbackState").asText());
        double expected = 100 + (System.currentTimeMillis() - t0) / 1000.0;
        assertTrue(Math.abs(st.get("playback").get("positionSeconds").asDouble() - expected) < 2.0);
        assertTrue(System.currentTimeMillis() - t0 < 2000, "join must complete within 2 s");

        // a viewer may not drive playback
        viewer.send("room.playback.command", "{\"action\":\"pause\"}");
        assertEquals("FORBIDDEN", viewer.expect("room.error", 2000).get("code").asText());

        // host seek reaches the viewer within 1 s with the authoritative position
        long s0 = System.currentTimeMillis();
        host.send("room.playback.command", "{\"action\":\"seek\",\"positionSeconds\":300}");
        JsonNode seek = viewer.expect("room.playback.update", 1000);
        assertEquals("seek", seek.get("state").asText());
        assertEquals(300.0, seek.get("positionSeconds").asDouble(), 0.5);
        assertTrue(System.currentTimeMillis() - s0 < 1000);

        // drift correction: about half a second behind a playing room => micro seek
        viewer.send("room.sync.ping", "{\"clientSentAt\":1,\"positionSeconds\":299.5}");
        JsonNode pong = viewer.expect("room.sync.pong", 1000);
        assertEquals("micro_seek", pong.get("correction").get("action").asText());
        host.ws.sendClose(1000, "bye");
        viewer.ws.sendClose(1000, "bye");
    }

    @Test
    void chatIsOrderedSanitisedAndRestoredOnReconnect() throws Exception {
        Room r = newRoom("chat", false);
        Client host = connect(r.id(), r.hostToken());
        host.expect("room.state", 3000);
        AuthService.Tokens v = user("chat-v@t.dev", Role.VIEWER, "en");
        Client viewer = joinAs(r, v);
        viewer.expect("room.state", 3000);
        for (int i = 0; i < 5; i++) {
            host.send("room.chat.send", "{\"body\":\"m" + i + " <script>alert(1)</script>\"}");
        }
        long last = 0;
        for (int i = 0; i < 5; i++) {
            JsonNode m = viewer.expect("room.chat.message", 2000);
            assertTrue(m.get("seq").asLong() > last);
            last = m.get("seq").asLong();
            assertFalse(m.get("body").asText().contains("<script>"));
            assertTrue(m.get("body").asText().startsWith("m" + i));
        }
        host.in.clear();
        viewer.ws.sendClose(1000, "drop");
        JsonNode gone = host.expect("room.presence.update", 3000);
        assertEquals("disconnected", gone.get("action").asText());
        Client again = connect(r.id(), v.accessToken());
        JsonNode state = again.expect("room.state", 3000);
        assertEquals(5, state.get("recentChat").size());
        assertEquals("reconnected", host.expect("room.presence.update", 3000).get("action").asText());
    }

    @Test
    void groundedAiAnswerCitesSourcesAndOffTopicIsDeclined() throws Exception {
        Room r = newRoom("ai", false);
        Client host = connect(r.id(), r.hostToken());
        host.expect("room.state", 3000);
        host.send("room.playback.command", "{\"action\":\"seek\",\"positionSeconds\":200}");
        host.expect("room.playback.update", 2000);
        host.send("room.ai.ask", "{\"question\":\"Who is the detective?\",\"currentTs\":200}");
        JsonNode a = host.expect("room.ai.answer", 3000);
        assertTrue(a.get("grounded").asBoolean());
        assertTrue(a.get("sourceEvents").size() >= 1);
        assertTrue(a.get("answer").asText().contains("Detective Rios"));
        host.send("room.ai.ask", "{\"question\":\"What is the capital of France?\"}");
        JsonNode off = host.expect("room.ai.answer", 3000);
        assertFalse(off.get("grounded").asBoolean());
        assertEquals(0, off.get("sourceEvents").size());
    }

    @Test
    void triviaCardFiresWithinOneSecondOfItsMarker() throws Exception {
        Room r = newRoom("trivia", false);
        Client host = connect(r.id(), r.hostToken());
        host.expect("room.state", 3000);
        host.send("room.playback.command", "{\"action\":\"seek\",\"positionSeconds\":10}");
        host.send("room.playback.command", "{\"action\":\"play\"}");
        JsonNode card = host.expect("room.ai.trivia", 4000); // marker at 12 s, so about 2 s after play
        assertTrue(card.get("text").asText().contains("real dock"));
    }

    @Test
    void groupVoteIsAppliedIdenticallyToEveryClient() throws Exception {
        Room r = newRoom("vote", true);
        Client host = connect(r.id(), r.hostToken());
        host.expect("room.state", 3000);
        AuthService.Tokens v = user("vote-v@t.dev", Role.VIEWER, "en");
        AuthService.Tokens w = user("vote-w@t.dev", Role.VIEWER, "en");
        Client c1 = joinAs(r, v);
        Client c2 = joinAs(r, w);
        c1.expect("room.state", 3000);
        c2.expect("room.state", 3000);
        host.send("room.variation.open", "{\"variationId\":\"v_x\",\"durationSeconds\":5}");
        host.expect("room.variation.voteOpen", 2000);
        c1.send("room.variation.vote", "{\"variationId\":\"v_x\",\"optionId\":\"b\"}");
        c2.send("room.variation.vote", "{\"variationId\":\"v_x\",\"optionId\":\"b\"}");
        host.send("room.variation.vote", "{\"variationId\":\"v_x\",\"optionId\":\"a\"}");
        JsonNode a1 = c1.expect("room.variation.applied", 8000);
        JsonNode a2 = c2.expect("room.variation.applied", 8000);
        JsonNode ah = host.expect("room.variation.applied", 8000);
        assertEquals("b", a1.get("winningOptionId").asText());
        assertEquals(2, a1.get("tally").get("b").asInt());
        assertEquals(a1, a2);
        assertEquals(a1, ah);
        // a second vote on the same point is refused
        host.send("room.variation.open", "{\"variationId\":\"v_x\"}");
        assertEquals("VOTE_ALREADY_HELD", host.expect("room.error", 2000).get("code").asText());
        // a late joiner learns the decided result from the snapshot
        AuthService.Tokens late = user("vote-late@t.dev", Role.VIEWER, "en");
        Client c3 = joinAs(r, late);
        assertEquals("b", c3.expect("room.state", 3000).get("variations").get("applied").get("v_x").asText());
    }

    @Test
    void onlyTheHostMayOpenAVoteAndVotingMustBeEnabled() throws Exception {
        Room off = newRoom("novote", false);
        Client h = connect(off.id(), off.hostToken());
        h.expect("room.state", 3000);
        h.send("room.variation.open", "{\"variationId\":\"v_x\"}");
        assertEquals("VOTING_DISABLED", h.expect("room.error", 2000).get("code").asText());

        Room on = newRoom("hostonly", true);
        Client host = connect(on.id(), on.hostToken());
        host.expect("room.state", 3000);
        Client viewer = joinAs(on, user("hostonly-v@t.dev", Role.VIEWER, "en"));
        viewer.expect("room.state", 3000);
        viewer.send("room.variation.open", "{\"variationId\":\"v_x\"}");
        assertEquals("FORBIDDEN", viewer.expect("room.error", 2000).get("code").asText());
        viewer.send("room.variation.vote", "{\"variationId\":\"v_x\",\"optionId\":\"a\"}");
        assertEquals("NO_OPEN_VOTE", viewer.expect("room.error", 2000).get("code").asText());
    }

    @Test
    void localisedSubtitleChoiceIsPerViewerInTheSnapshot() throws Exception {
        Room r = newRoom("subs", false);
        AuthService.Tokens es = user("subs-es@t.dev", Role.VIEWER, "es-MX");
        Client c = joinAs(r, es);
        JsonNode st = c.expect("room.state", 3000);
        boolean found = false;
        for (JsonNode ch : st.get("variations").get("choices")) {
            if ("v_sub".equals(ch.get("variationId").asText())) {
                assertEquals("b", ch.get("optionId").asText());
                found = true;
            }
        }
        assertTrue(found);
        // switching locale answers with that viewer's own choices
        c.send("room.locale.set", "{\"locale\":\"en\"}");
        JsonNode choices = c.expect("room.subtitle.choices", 2000);
        assertEquals("a", choices.get("choices").get(0).get("optionId").asText());
        c.send("room.locale.set", "{\"locale\":\"<bad>\"}");
        assertEquals("BAD_LOCALE", c.expect("room.error", 2000).get("code").asText());
    }

    @Test
    void subtitleSwapIsPushedToEachViewerInTheirLocaleWhenPlaybackCrossesThePoint() throws Exception {
        Room r = newRoom("swap", false);
        Client host = connect(r.id(), r.hostToken());
        host.expect("room.state", 3000);
        Client es = joinAs(r, user("swap-es@t.dev", Role.VIEWER, "es"));
        Client en = joinAs(r, user("swap-en@t.dev", Role.VIEWER, "en"));
        es.expect("room.state", 3000);
        en.expect("room.state", 3000);
        host.send("room.playback.command", "{\"action\":\"seek\",\"positionSeconds\":38}");
        host.send("room.playback.command", "{\"action\":\"play\"}");   // crosses 40 s about 2 s later
        assertEquals("b", es.expect("room.subtitle.swap", 5000).get("optionId").asText());
        assertEquals("a", en.expect("room.subtitle.swap", 5000).get("optionId").asText());
    }

    @Test
    void hostDisconnectPausesThenPromotesDeterministically() throws Exception {
        Room r = newRoom("failover", false);
        Client host = connect(r.id(), r.hostToken());
        host.expect("room.state", 3000);
        AuthService.Tokens v = user("fo-v@t.dev", Role.VIEWER, "en");
        Client viewer = joinAs(r, v);
        viewer.expect("room.state", 3000);
        host.send("room.playback.command", "{\"action\":\"play\",\"positionSeconds\":50}");
        viewer.expect("room.playback.update", 2000);
        host.ws.sendClose(1000, "gone");
        assertEquals(r.hostId(), viewer.expect("room.host.disconnected", 2000).get("hostUserId").asLong());
        assertEquals("paused", viewer.expect("room.playback.update", 2000).get("playbackState").asText());
        // while the host is away nobody can drive playback
        viewer.send("room.playback.command", "{\"action\":\"play\"}");
        assertEquals("FORBIDDEN", viewer.expect("room.error", 2000).get("code").asText());
        // after the grace period the only connected member is promoted
        JsonNode changed = viewer.expect("room.host.changed", 4000);
        assertEquals(v.user().id(), changed.get("hostUserId").asLong());
        assertEquals("host_timeout", changed.get("reason").asText());
        viewer.send("room.playback.command", "{\"action\":\"play\"}");
        assertEquals("playing", viewer.expect("room.playback.update", 2000).get("playbackState").asText());
    }

    @Test
    void moderationMuteRemoveAndLock() throws Exception {
        Room r = newRoom("mod", false);
        Client host = connect(r.id(), r.hostToken());
        host.expect("room.state", 3000);
        AuthService.Tokens v = user("mod-v@t.dev", Role.VIEWER, "en");
        Client viewer = joinAs(r, v);
        viewer.expect("room.state", 3000);
        viewer.send("room.mod.mute", "{\"userId\":" + r.hostId() + "}");
        assertEquals("FORBIDDEN", viewer.expect("room.error", 2000).get("code").asText());
        host.send("room.mod.mute", "{\"userId\":" + v.user().id() + ",\"muted\":true}");
        assertEquals("muted", viewer.expect("room.moderation", 2000).get("action").asText());
        viewer.send("room.chat.send", "{\"body\":\"hi\"}");
        assertEquals("MUTED", viewer.expect("room.error", 2000).get("code").asText());
        host.send("room.mod.remove", "{\"userId\":" + v.user().id() + "}");
        assertEquals("removed", viewer.expect("room.moderation", 2000).get("action").asText());
        Thread.sleep(300);
        assertEquals(4403, viewer.closeCode);
        // removed users cannot come back
        Client again = connect(r.id(), v.accessToken());
        assertEquals("REMOVED", again.expect("room.error", 3000).get("code").asText());
    }

    @Test
    void invalidTokenCannotOpenASocket() {
        Room r = newRoom("badtoken", false);
        assertThrows(Exception.class, () -> connect(r.id(), "garbage"));
    }

    @Test
    void sessionEndPersistsAnalyticsThatReconcileWithTheEventLog() throws Exception {
        Room r = newRoom("analytics", false);
        Client host = connect(r.id(), r.hostToken());
        host.expect("room.state", 3000);
        AuthService.Tokens v = user("an-v@t.dev", Role.VIEWER, "en");
        Client viewer = joinAs(r, v);
        viewer.expect("room.state", 3000);
        for (int i = 0; i < 3; i++) {
            host.send("room.chat.send", "{\"body\":\"hello " + i + "\"}");
        }
        host.send("room.ai.ask", "{\"question\":\"Who is the detective?\",\"currentTs\":100}");
        host.expect("room.ai.answer", 3000);
        host.send("room.session.end", "{}");
        host.expect("room.ended", 3000);
        Thread.sleep(500);
        SessionService.Analytics a = sessions.analytics(r.id(), new AuthUser(r.hostId(), "", Role.ADMIN));
        assertEquals(2, a.peakConcurrentParticipants());
        assertEquals(3, a.chatMessages());
        assertEquals(1, a.aiQuestions());
        assertTrue(a.reconciled());
        assertEquals("ended", a.status());
    }

    @Test
    void endedSessionRecordsAViewingForEveryoneWhoWasInTheRoom() throws Exception {
        Room r = newRoom("interactions", false);
        Client host = connect(r.id(), r.hostToken());
        host.expect("room.state", 3000);
        AuthService.Tokens v = user("int-v@t.dev", Role.VIEWER, "en");
        Client viewer = joinAs(r, v);
        viewer.expect("room.state", 3000);
        host.send("room.playback.command", "{\"action\":\"seek\",\"positionSeconds\":200}");
        host.expect("room.playback.update", 2000);
        host.send("room.session.end", "{}");
        host.expect("room.ended", 3000);
        Thread.sleep(800);
        var hostRows = interactionRepo.findByUser_IdOrderByCreatedAtDescIdDesc(r.hostId(), PageRequest.of(0, 5));
        var viewerRows = interactionRepo.findByUser_IdOrderByCreatedAtDescIdDesc(v.user().id(), PageRequest.of(0, 5));
        assertEquals(1, hostRows.size());
        assertEquals(1, viewerRows.size());
        assertEquals(200, viewerRows.get(0).watchedSeconds);
        assertFalse(viewerRows.get(0).completed);
        assertEquals(String.valueOf(r.hostId()), viewerRows.get(0).coWatchedWith);
    }
}
