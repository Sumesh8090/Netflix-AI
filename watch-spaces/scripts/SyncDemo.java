import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scripted multi-client demo (Weeks 1-2) (backend-only track). Run with the server up:
 *   BASE=http://localhost:8080 SEED_PASSWORD=... java scripts/SyncDemo.java
 * Logs in as the seeded host + two viewers, creates a Watch Space, connects three WebSocket clients, then the host
 * plays / seeks / pauses and every client prints what it received plus the delivery delay.
 */
public class SyncDemo {
    static final String BASE = System.getenv().getOrDefault("BASE", "http://localhost:8080");
    static final String PASSWORD = System.getenv("SEED_PASSWORD");
    static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        if (PASSWORD == null) {
            System.err.println("Set SEED_PASSWORD to the password used to seed the demo accounts");
            System.exit(2);
        }
        String host = login("host@watchspaces.local");
        String v1 = login("viewer@watchspaces.local");
        String v2 = login("viewer2@watchspaces.local");

        String created = post("/api/v1/watch-spaces", host, "{\"titleId\":1,\"maxParticipants\":10,\"votingEnabled\":true}");
        long id = Long.parseLong(find(created, "\"watchSpaceId\":(\\d+)"));
        String code = find(created, "\"inviteCode\":\"([^\"]+)\"");
        System.out.println("created watch space " + id + " invite code " + code);
        post("/api/v1/watch-spaces/join", v1, "{\"inviteCode\":\"" + code + "\"}");
        post("/api/v1/watch-spaces/join", v2, "{\"inviteCode\":\"" + code + "\"}");

        Client h = new Client("host", id, host);
        Thread.sleep(300);
        Client a = new Client("viewer1", id, v1);
        Client b = new Client("viewer2", id, v2);
        Thread.sleep(500);

        System.out.println("\n--- host: play from 100s");
        h.send("room.playback.command", "{\"action\":\"play\",\"positionSeconds\":100}");
        Thread.sleep(1500);
        System.out.println("\n--- viewer1 (not host) tries to pause -> must be rejected (FORBIDDEN)");
        a.send("room.playback.command", "{\"action\":\"pause\"}");
        Thread.sleep(500);
        System.out.println("\n--- viewer2 drift ping (claims 110s while room is ~101s) -> correction");
        b.send("room.sync.ping", "{\"clientSentAt\":" + System.currentTimeMillis() + ",\"positionSeconds\":110}");
        Thread.sleep(500);

        System.out.println("\n--- host: seek to 415s, keep playing -> trivia 'Location' (authored at 420s) fires on its own");
        h.send("room.playback.command", "{\"action\":\"seek\",\"positionSeconds\":415}");
        Thread.sleep(7000);

        System.out.println("\n--- chat: viewer1 types, sends a message with HTML (must arrive escaped), reacts");
        a.send("room.chat.typing", "{\"typing\":true}");
        a.send("room.chat.send", "{\"body\":\"Hello <b>everyone</b> & welcome\"}");
        a.send("room.reaction", "{\"emoji\":\"\uD83D\uDD25\"}");
        Thread.sleep(700);

        System.out.println("\n--- AI co-pilot: viewer2 asks questions (answer is broadcast to the room, with source events)");
        b.send("room.ai.ask", "{\"question\":\"Where was this filmed?\"}");
        Thread.sleep(500);
        b.send("room.ai.ask", "{\"question\":\"Who is Elena Vance?\"}");   // she appears later: must decline, no spoiler
        Thread.sleep(500);

        System.out.println("\n--- moderation: host mutes viewer2, viewer2 chats (MUTED), host unmutes");
        h.send("room.mod.mute", "{\"userId\":" + b.userId + ",\"muted\":true}");
        Thread.sleep(300);
        b.send("room.chat.send", "{\"body\":\"can anyone hear me?\"}");
        Thread.sleep(300);
        h.send("room.mod.mute", "{\"userId\":" + b.userId + ",\"muted\":false}");
        Thread.sleep(300);

        System.out.println("\n--- reconnect: viewer1 drops and comes back within the grace window (no duplicate 'joined'; chat history is replayed)");
        a.close();
        Thread.sleep(800);
        a = new Client("viewer1b", id, v1);
        Thread.sleep(800);

        System.out.println("\n--- host: pause, then end the session");
        h.send("room.playback.command", "{\"action\":\"pause\"}");
        Thread.sleep(400);
        h.send("room.session.end", "{}");
        Thread.sleep(1000);
        System.exit(0);
    }

    static String login(String email) throws Exception {
        String r = post("/api/v1/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
        return find(r, "\"accessToken\":\"([^\"]+)\"");
    }

    static String post(String path, String token, String body) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(BASE + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            rb.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> r = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) {
            throw new IllegalStateException(path + " -> " + r.statusCode() + " " + r.body());
        }
        return r.body();
    }

    static String find(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        if (!m.find()) {
            throw new IllegalStateException("no match for " + regex + " in " + s);
        }
        return m.group(1);
    }

    static class Client implements WebSocket.Listener {
        final String name;
        final WebSocket ws;
        final StringBuilder buf = new StringBuilder();
        final List<String> received = new CopyOnWriteArrayList<>();
        volatile long userId = -1;

        Client(String name, long roomId, String token) throws Exception {
            this.name = name;
            String url = BASE.replaceFirst("^http", "ws") + "/ws/watch-spaces/" + roomId + "?token=" + token;
            this.ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(url), this).get();
        }

        void send(String event, String payload) {
            ws.sendText("{\"event\":\"" + event + "\",\"payload\":" + payload + "}", true);
        }

        void close() {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                received.add(msg);
                if (userId < 0 && msg.contains("\"event\":\"room.state\"")) {
                    userId = Long.parseLong(find(msg, "\"yourUserId\":(\\d+)"));
                }
                String ev = find(msg, "\"event\":\"([^\"]+)\"");
                String sent = find(msg, "\"ts\":(\\d+)\\}$");
                long delay = System.currentTimeMillis() - Long.parseLong(sent);
                System.out.printf("[%-7s] %-26s +%dms  %s%n", name, ev, delay, msg.length() > 170 ? msg.substring(0, 170) + "..." : msg);
            }
            w.request(1);
            return null;
        }
    }
}
