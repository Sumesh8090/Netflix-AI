import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load / latency test over real HTTP and WebSocket connections (Week 4). Needs only the JDK:
 *
 *   BASE=http://localhost:8080 ROOMS=3 VIEWERS=9 SECONDS=30 java scripts/LoadTest.java
 *
 * It registers throw-away users (host + viewers per room), creates the rooms, connects everybody, then for SECONDS seconds
 *   - the host seeks every 2 s          -> measures how long until EVERY viewer has the update ("seek fan-out")
 *   - every viewer chats every 3 s      -> measures send -> own echo ("chat echo") and checks nobody missed a message
 *   - every viewer asks the co-pilot every 6 s -> measures ask -> answer ("AI answer")
 * and it measures "join" = connect -> first room.state. Finally the host ends each room. Results are printed as
 * count / p50 / p95 / max in ms with PASS/FAIL against the targets below.
 *
 * Latencies are measured with the local clock, so run it on the same machine as the server for exact figures
 * (from another machine they include network time, which is realistic but not comparable).
 * Test accounts are left in the database (emails lt-...@example.com); use a scratch database.
 */
public class LoadTest {
    static final String BASE = env("BASE", "http://localhost:8080");
    static final int ROOMS = Integer.parseInt(env("ROOMS", "3"));
    static final int VIEWERS = Integer.parseInt(env("VIEWERS", "9"));
    static final int SECONDS = Integer.parseInt(env("SECONDS", "30"));
    static final long TITLE_ID = Long.parseLong(env("TITLE_ID", "1"));
    static final String PASSWORD = "loadtest-pass-1";
    static final String RUN = UUID.randomUUID().toString().substring(0, 6);

    // targets (ms)
    static final double JOIN_P95 = 2000, SEEK_P95 = 1000, CHAT_P95 = 1000, AI_P95 = 3000;

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(4);
    static final List<Double> JOIN = new CopyOnWriteArrayList<>();
    static final List<Double> SEEK = new CopyOnWriteArrayList<>();
    static final List<Double> CHAT = new CopyOnWriteArrayList<>();
    static final List<Double> AI = new CopyOnWriteArrayList<>();
    static final AtomicInteger ERRORS = new AtomicInteger();
    static final AtomicInteger UNEXPECTED_CLOSES = new AtomicInteger();
    static volatile boolean ending = false;

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    // ---- one websocket client ------------------------------------------------------------------------------

    static final class Client implements WebSocket.Listener {
        final String name;
        final Room room;
        final boolean host;
        final StringBuilder buf = new StringBuilder();
        final long startedNanos = System.nanoTime();
        final Map<String, Long> chatSent = new ConcurrentHashMap<>();
        final AtomicInteger chatSeen = new AtomicInteger();
        volatile long askSentNanos = 0;
        volatile boolean gotState = false;
        volatile boolean ended = false;
        WebSocket ws;
        long userId;

        Client(String name, Room room, boolean host) {
            this.name = name;
            this.room = room;
            this.host = host;
        }

        void connect(String token) {
            ws = HTTP.newWebSocketBuilder()
                    .buildAsync(URI.create(BASE.replaceFirst("^http", "ws") + "/ws/watch-spaces/" + room.id + "?token=" + token), this)
                    .join();
        }

        void send(String event, String payload) {
            ws.sendText("{\"event\":\"" + event + "\",\"payload\":" + payload + "}", true);
        }

        @Override
        public void onOpen(WebSocket w) {
            w.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                handle(msg);
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            if (!ended && !ending) {
                UNEXPECTED_CLOSES.incrementAndGet();
                System.err.println(name + " closed unexpectedly: " + code + " " + reason);
            }
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) {
            if (!ending) {
                ERRORS.incrementAndGet();
                System.err.println(name + " socket error: " + error);
            }
        }

        void handle(String msg) {
            long now = System.nanoTime();
            String event = find(msg, "\"event\":\"([^\"]+)\"");
            if (event == null) {
                return;
            }
            switch (event) {
                case "room.state" -> {
                    if (!gotState) {
                        gotState = true;
                        JOIN.add((now - startedNanos) / 1e6);
                    }
                }
                case "room.playback.update" -> {
                    if (!host && msg.contains("\"state\":\"seek\"")) {
                        Long sent = room.seekSentNanos;
                        if (sent != null && sent > 0) {
                            room.seekArrivals.add((now - sent) / 1e6);
                        }
                    }
                }
                case "room.chat.message" -> {
                    chatSeen.incrementAndGet();
                    String body = find(msg, "\"body\":\"(lt" + RUN + "-[^ \"]*)");
                    if (body != null) {
                        Long sent = chatSent.remove(body);
                        if (sent != null) {
                            CHAT.add((now - sent) / 1e6);
                        }
                    }
                }
                case "room.ai.answer" -> {
                    if (askSentNanos > 0 && msg.contains("\"userId\":" + userId + ",")) {
                        AI.add((now - askSentNanos) / 1e6);
                        askSentNanos = 0;
                    }
                }
                case "room.ended" -> ended = true;
                case "room.error" -> {
                    ERRORS.incrementAndGet();
                    System.err.println(name + " room.error " + msg);
                }
                default -> { }
            }
        }
    }

    static final class Room {
        long id;
        String code;
        Client host;
        final List<Client> viewers = new ArrayList<>();
        volatile Long seekSentNanos;
        /** arrival delays (ms) for the seek currently in flight */
        final List<Double> seekArrivals = new CopyOnWriteArrayList<>();
        final AtomicInteger chatsSent = new AtomicInteger();
    }

    // ---- main -----------------------------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (ROOMS < 1 || VIEWERS < 1 || VIEWERS > 49) {
            System.err.println("ROOMS >= 1 and 1 <= VIEWERS <= 49 (a room holds at most 50 people)");
            System.exit(2);
        }
        System.out.printf("load test run %s: %d rooms x (1 host + %d viewers), %d s, server %s%n", RUN, ROOMS, VIEWERS, SECONDS, BASE);
        System.out.println("java " + System.getProperty("java.version") + ", cpus=" + Runtime.getRuntime().availableProcessors());

        List<Room> rooms = new ArrayList<>();
        for (int r = 0; r < ROOMS; r++) {
            Room room = new Room();
            String hostToken = register("lt-" + RUN + "-h" + r, "HOST");
            long hostId = lastUserId;
            String created = post("/api/v1/watch-spaces", hostToken,
                    "{\"titleId\":" + TITLE_ID + ",\"maxParticipants\":" + (VIEWERS + 1) + ",\"aiVerbosity\":\"normal\"}");
            room.id = Long.parseLong(require(find(created, "\"watchSpaceId\":(\\d+)"), "watchSpaceId in " + created));
            room.code = require(find(created, "\"inviteCode\":\"([^\"]+)\""), "inviteCode in " + created);
            room.host = new Client("room" + r + "-host", room, true);
            room.host.userId = hostId;
            room.host.connect(hostToken);
            for (int v = 0; v < VIEWERS; v++) {
                String token = register("lt-" + RUN + "-r" + r + "v" + v, "VIEWER");
                Client c = new Client("room" + r + "-v" + v, room, false);
                c.userId = lastUserId;
                post("/api/v1/watch-spaces/join", token, "{\"inviteCode\":\"" + room.code + "\"}");
                c.connect(token);
                room.viewers.add(c);
            }
            rooms.add(room);
        }
        Thread.sleep(1500); // let every room.state arrive
        for (Room room : rooms) {
            for (Client c : all(room)) {
                if (!c.gotState) {
                    System.err.println(c.name + " did not receive room.state");
                    ERRORS.incrementAndGet();
                }
            }
        }

        List<Double> seekFanOut = new CopyOnWriteArrayList<>();
        for (Room room : rooms) {
            room.host.send("room.playback.command", "{\"action\":\"play\",\"positionSeconds\":0}");
            // seek every 2 s; 1.5 s later record the slowest arrival among the viewers (the fan-out time) if all arrived
            POOL.scheduleAtFixedRate(() -> {
                try {
                    room.seekArrivals.clear();
                    room.seekSentNanos = System.nanoTime();
                    room.host.send("room.playback.command", "{\"action\":\"seek\",\"positionSeconds\":" + (100 + Math.random() * 1000) + "}");
                    POOL.schedule(() -> {
                        List<Double> a = new ArrayList<>(room.seekArrivals);
                        if (a.size() >= room.viewers.size()) {
                            seekFanOut.add(a.stream().mapToDouble(Double::doubleValue).max().orElse(0));
                        } else {
                            ERRORS.incrementAndGet();
                            System.err.println("room " + room.id + ": only " + a.size() + "/" + room.viewers.size() + " viewers got the seek");
                        }
                    }, 1500, TimeUnit.MILLISECONDS);
                } catch (RuntimeException e) {
                    ERRORS.incrementAndGet();
                }
            }, 1000, 2000, TimeUnit.MILLISECONDS);
            int i = 0;
            for (Client c : room.viewers) {
                final int offset = (i++ * 97) % 3000;
                POOL.scheduleAtFixedRate(() -> {
                    String token = "lt" + RUN + "-" + c.name + "-" + System.nanoTime();
                    c.chatSent.put(token, System.nanoTime());
                    room.chatsSent.incrementAndGet();
                    c.send("room.chat.send", "{\"body\":\"" + token + "\"}");
                }, 500 + offset, 3000, TimeUnit.MILLISECONDS);
                POOL.scheduleAtFixedRate(() -> {
                    if (c.askSentNanos == 0) {
                        c.askSentNanos = System.nanoTime();
                        c.send("room.ai.ask", "{\"question\":\"Who is the detective?\"}");
                    }
                }, 1000 + offset, 6000, TimeUnit.MILLISECONDS);
            }
        }

        Thread.sleep(SECONDS * 1000L);
        ending = true;
        POOL.shutdownNow();
        Thread.sleep(1500); // let the last echoes land
        int missing = 0;
        for (Room room : rooms) {
            int sent = room.chatsSent.get();
            for (Client c : all(room)) {
                // every member should have seen every chat message of the room (allow the last 1-2 still in flight)
                if (c.chatSeen.get() < sent - room.viewers.size()) {
                    missing++;
                    System.err.println(c.name + " saw " + c.chatSeen.get() + " chat messages, room sent " + sent);
                }
            }
            room.host.send("room.session.end", "{}");
        }
        Thread.sleep(1000);
        for (Room room : rooms) {
            for (Client c : all(room)) {
                c.ended = true;
            }
        }

        System.out.println();
        System.out.printf("%-14s %6s %9s %9s %9s   target(p95)%n", "metric", "count", "p50 ms", "p95 ms", "max ms");
        boolean ok = true;
        ok &= row("join", JOIN, JOIN_P95);
        ok &= row("seek fan-out", seekFanOut, SEEK_P95);
        ok &= row("chat echo", CHAT, CHAT_P95);
        ok &= row("AI answer", AI, AI_P95);
        System.out.printf("errors=%d unexpectedCloses=%d clientsMissingChat=%d%n", ERRORS.get(), UNEXPECTED_CLOSES.get(), missing);
        ok &= ERRORS.get() == 0 && UNEXPECTED_CLOSES.get() == 0 && missing == 0;
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(ok ? 0 : 1);
    }

    static List<Client> all(Room room) {
        List<Client> l = new ArrayList<>();
        l.add(room.host);
        l.addAll(room.viewers);
        return l;
    }

    static boolean row(String name, List<Double> samples, double targetP95) {
        if (samples.isEmpty()) {
            System.out.printf("%-14s %6d %9s %9s %9s   <= %.0f  FAIL (no samples)%n", name, 0, "-", "-", "-", targetP95);
            return false;
        }
        double[] a = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double p50 = pct(a, 50), p95 = pct(a, 95);
        boolean pass = p95 <= targetP95;
        System.out.printf("%-14s %6d %9.1f %9.1f %9.1f   <= %.0f  %s%n", name, a.length, p50, p95, a[a.length - 1], targetP95,
                pass ? "PASS" : "FAIL");
        return pass;
    }

    static double pct(double[] sorted, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    // ---- tiny HTTP helpers -------------------------------------------------------------------------------------

    static long lastUserId;

    static String register(String local, String role) throws Exception {
        String body = "{\"email\":\"" + local + "@example.com\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"" + local
                + "\",\"role\":\"" + role + "\"}";
        String res = post("/api/v1/auth/register", null, body);
        lastUserId = Long.parseLong(require(find(res, "\"user\":\\{\"id\":(\\d+)"), "user id in " + res));
        return require(find(res, "\"accessToken\":\"([^\"]+)\""), "accessToken in " + res);
    }

    static String post(String path, String token, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("POST " + path + " -> " + r.statusCode() + " " + r.body());
        }
        return r.body();
    }

    static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    static String require(String v, String what) {
        if (v == null) {
            throw new IllegalStateException("missing " + what);
        }
        return v;
    }
}
