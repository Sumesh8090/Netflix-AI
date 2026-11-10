# Watch Spaces: AI-driven social viewing (Java / Spring Boot / MySQL)

Track **B (backend-only)** of the intern PRD on **Option A (Java)**: Spring Boot 3.3, Spring Security + JWT, Spring Data JPA,
Spring WebSocket, **MySQL 8**. No UI: behaviour is proven with REST, a scripted WebSocket client and automated tests.

## Status: 100% (PRD Weeks 1-4)

| Area | State |
|---|---|
| Auth (register/login/refresh/logout), JWT, roles VIEWER / HOST / ADMIN, server-side RBAC | done (Week 1) |
| MySQL schema, seeded titles "The Pier" (15-event timeline) and "The Harbor Case" | done (Week 1, 3) |
| Create room, invite code, join, host-driven play / pause / seek over WebSocket, drift correction | done (Week 1) |
| Host-disconnect policy, presence, reconnect grace | done (Weeks 1-2) |
| Chat (ordered, persisted, rate-limited, HTML-escaped), typing, reactions, history + replay on reconnect | done (Week 2) |
| Moderation: mute, remove, lock, transfer host; end session | done (Week 2) |
| Trivia cards pushed at authored timestamps (verbosity-aware) | done (Week 2) |
| AI Q&A (`POST /ai/ask` + `room.ai.ask`): retrieval-only, cites source events, declines instead of guessing, no spoilers | done (Week 2) |
| Session analytics computed from the event log, empty-room auto-end | done (Week 2) |
| Subtitle localisation swap: each viewer gets the option for their locale, pushed when playback crosses the point | done (Week 3) |
| Narrative voting: host opens a timed vote, one vote per member, winner broadcast once and identical for everyone, late joiners see it | done (Week 3) |
| Recommendations: hybrid content + collaborative + co-watch ranker computed from interactions, evaluated with Precision@5 | done (Week 3) |
| Viewing records created automatically when a session ends; `POST /interactions` for explicit ratings | done (Week 3) |
| User dashboard: recently watched, live / upcoming rooms (quick rejoin), past-session analytics, recommended rail | done (Week 3) |
| Admin timeline upload with field-level validation | done (Weeks 1, 3) |
| Load-test tool (`scripts/LoadTest.java`), in-process performance gates, measured numbers, recommender evaluation | done (Week 4) |
| Docs: `docs/RUNBOOK.md` (step-by-step), `docs/ARCHITECTURE.md`, `docs/PERFORMANCE.md`, Postman collection | done (Week 4) |
| `.env` file support for easy local configuration | done (Week 4) |

**Start with `docs/RUNBOOK.md`.** Honest status: the pure logic (100 unit tests) is verified; the Spring layer and its integration
tests have not been run by the author (no Maven access), so the first `mvn test` on your machine is the real verification.

## Run it

Needs JDK 17+, Maven 3.9+ (Eclipse has it built in), MySQL 8. Full step-by-step: `docs/RUNBOOK.md`. From a terminal:

```bash
export DB_PASSWORD=your-mysql-password
export JWT_SECRET=$(openssl rand -hex 32)      # >= 32 chars
export SEED_PASSWORD=Demo-Passw0rd              # password of the demo accounts
mvn spring-boot:run
```
The database `watchspaces` is created automatically (or run `db/schema.sql` and start with `DDL_AUTO=validate`). Settings are
environment variables, or a git-ignored `.env` file in the project root (copy `.env.example`); no secret is in the repo. Demo accounts: `admin@`, `host@`, `viewer@` (locale `hi`, Comedy history), `viewer2@` (locale `es`) `@watchspaces.local`, plus 60 synthetic
`demo01@` ... `demo60@watchspaces.local` viewers whose history (fixed seed 42) feeds the recommender. **Use a fresh database** when moving
from the 50% build, because seeding only runs on empty tables.
Health: `GET /api/v1/health/api`, `/api/v1/health/gateway`, `/actuator/health`.

### Demo (host + two viewers, everything printed with delivery delays)
```bash
SEED_PASSWORD=Demo-Passw0rd java scripts/SyncDemo.java
```
It shows: sync, rejected non-host pause, drift correction, a trivia card firing by itself at its timestamp, chat (HTML arrives
escaped), reaction, AI answers (one grounded with sources, one declined: no spoiler), mute / muted-chat rejection,
reconnect with chat replay, and session end.

### Tests
`mvn test` runs: `core/*Test` (state machine, drift, room rules, validation of both seed timelines, grounded Q&A, trivia planner,
variation rules, interaction rules, rate limiter, chat rules, write buffer, recommender), `AuthRbacIntegrationTest` and `RbacIntegrationTest`
(H2: 401/403 behaviour, create/join, AI endpoint, end-session and analytics reconciliation, dashboard, recommendations,
evaluation) and `WsFlowIT`, a real-socket end-to-end test (sync, chat, AI, trivia, voting, subtitle swap, failover, moderation,
analytics, viewing records; takes about 30 s because of real timers).

## REST API

| Method & path | Who |
|---|---|
| `POST /api/v1/auth/register` `login` `refresh` `logout` | public (register: VIEWER/HOST only) |
| `GET /api/v1/me`, `PATCH /api/v1/me/preferences` | any user |
| `GET /api/v1/titles`, `/titles/{id}`, `/titles/{id}/timeline?from=&to=` | any user |
| `POST /api/v1/watch-spaces` -> `201 {watchSpaceId, inviteCode, status, hostUserId, createdAt}` | HOST, ADMIN |
| `POST /api/v1/watch-spaces/join` `{inviteCode}` | any user |
| `GET /api/v1/watch-spaces/{id}` and `/{id}/messages?beforeSeq=&limit=` | host / joined / admin |
| `POST /api/v1/watch-spaces/{id}/ai/ask` `{currentTs?, question}` -> `{answer, sourceEvents, generatedAt, grounded, sources, questionId}` | room members |
| `POST /api/v1/watch-spaces/{id}/end`, `GET /{id}/analytics` | host or admin |
| `GET /api/v1/dashboard` -> `{recentlyWatched, upcoming, liveNow, pastSessions, recommendedForYou}` | any user |
| `GET /api/v1/recommendations?limit=` -> `[{titleId, name, genres, score, reason}]` | any user |
| `POST /api/v1/interactions` `{titleId, watchedSeconds, completed, rating?}` -> `201` | any user |
| `POST /api/v1/admin/titles`, `PUT /admin/titles/{id}/timeline`, `POST .../timeline/validate`, `GET /api/v1/metrics/summary`, `GET /api/v1/admin/recommendations/evaluate?k=5` | ADMIN |

`ai/ask`: the `userId` in the body is ignored (the asker is the token's user) and `currentTs` is clamped to the room's real
position, so a client cannot ask for spoilers. Errors are `{"error":{"code","message","details?"}}`.

## WebSocket: `ws://localhost:8080/ws/watch-spaces/{id}?token=<accessToken>`
Envelope: `{"event","watchSpaceId","payload","ts"}`.

| Client -> server | Payload | Who |
|---|---|---|
| `room.playback.command` | `{action: play\|pause\|seek\|buffer_start\|buffer_end, positionSeconds}` | current host |
| `room.sync.ping` | `{clientSentAt, positionSeconds, rttMs?}` | anyone |
| `room.state.request` | `{}` | anyone |
| `room.chat.send` | `{body}` (max 500 chars, 10 per 5 s) | non-muted members |
| `room.chat.typing` | `{typing}` | non-muted members |
| `room.reaction` | `{emoji}` (fixed set: 👍 ❤️ 😂 😮 😢 👏 🔥 🎉) | non-muted members |
| `room.ai.ask` | `{question, currentTs?}` (5 per 10 s) | members |
| `room.mod.mute` / `remove` / `lock` / `transferHost` | `{userId, muted}` / `{userId}` / `{locked}` / `{userId}` | host |
| `room.session.end` | `{}` | host |
| `room.locale.set` | `{locale}` e.g. `hi`, `pt-BR` (also `?locale=` on connect; default: profile locale) | anyone |
| `room.variation.open` | `{variationId, durationSeconds?}` (5-120 s, default 20; needs `votingEnabled`) | host |
| `room.variation.vote` | `{variationId, optionId}` (re-voting replaces) | members |
| `room.variation.close` | `{variationId}` (close early) | host |

| Server -> client | Meaning |
|---|---|
| `room.state` | snapshot on connect: playback, participants, host, **recentChat** (last 30), **variations** (`choices` for your locale, `applied` results, `openVotes`) |
| `room.playback.update` | `{state, playbackState, positionSeconds, serverTimeMs, version, issuedBy}` |
| `room.presence.update` | `action`: `joined`, `reconnected`, `disconnected`, `left` |
| `room.chat.message` | `{messageId, seq, userId, displayName, body, tsSeconds, sentAt}`, `seq` gives the same order to everyone |
| `room.chat.typing`, `room.reaction` | `{userId, ...}` |
| `room.ai.trivia` | `{eventId, kind, text, tsSeconds, seq}` pushed at the authored timestamp |
| `room.ai.answer` | `{questionId, userId, question, answer, sourceEvents, grounded}` |
| `room.moderation` | `{action: muted\|unmuted\|removed\|locked\|unlocked, targetUserId, by}` |
| `room.host.disconnected` / `reconnected` / `changed` | host policy; `changed.reason`: `host_timeout` or `transfer` |
| `room.sync.pong` | `{clientSentAt, serverReceivedAt, state, authoritativePositionSeconds, correction{action,targetPositionSeconds,driftMs}}` |
| `room.subtitle.swap` | **to one viewer**: `{variationId, ts, kind, optionId, label, locale}` when playback crosses a subtitle point |
| `room.subtitle.choices` | to one viewer, after `room.locale.set`: that viewer's choices |
| `room.variation.voteOpen` / `voted` / `applied` / `reached` | `voteOpen {variationId, ts, kind, options, closesAt}`; `voted` goes to the voter; `applied {variationId, winningOptionId, label, appliesAtTs, tally}` is the same for everyone; `reached` announces a decided narrative point when playback crosses it |
| `room.ended` | `{reason, endedBy}`, then sockets close |
| `room.error` | `{code, message}`: `FORBIDDEN`, `ILLEGAL_TRANSITION`, `MUTED`, `RATE_LIMITED`, `EMPTY_MESSAGE`, `MESSAGE_TOO_LONG`, `BAD_REACTION`, `BAD_MESSAGE`, ... |

Close codes: `4401` token expired, `4403` refused (`ROOM_FULL`, `ROOM_LOCKED`, `REMOVED`), `4404` room not found/ended.

### Behaviour worth knowing
* **Host disconnect:** playback pauses for everyone at once; if the host is back within 15 s they keep the role, otherwise the
  earliest-joined connected member is promoted. **Viewers** get 5 s to reconnect without a "left".
* **AI is retrieval-only:** answers are assembled from the title's metadata, always cite `sourceEvents`, only use entries that
  start at or before the room's position, and decline when nothing matches (also when the question names something the
  metadata does not contain, e.g. a character who has not appeared yet).
* **Trivia** fires within one 250 ms tick of its timestamp, exactly once per pass; seeks fire nothing. Verbosity: MINIMAL = trivia,
  NORMAL = + glossary, CHATTY = + characters. Scenes only feed Q&A.
* **Persistence:** playback position never touches the DB. Chat and the event log are written in batches (20 rows or 2 s).
  Analytics (peak concurrency, AI questions, trivia cards, chat count) are replayed from the event log and cross-checked
  against the chat table (`reconciled`).
* **Variations are pre-authored**, never generated. Subtitle points follow each viewer's locale (`hi-IN` matches `hi`; no match
  means the original, option 0) and never touch playback, so they cannot desync the room. A vote winner is the option with the most
  votes; a tie or no votes keeps the earliest option (the original). A vote is held once per point per room; open votes are
  held in memory and written once when they close (a restart mid-vote drops that vote).
* **Recommendations** are computed from the interactions table on request and cached per table size, so a new interaction shows up in the
  next call with no retrain. Final score = 0.7 x content (genre affinity) + 0.3 x collaborative (similar users, boosted by co-watching).
  Users with no history get a popularity list. `GET /admin/recommendations/evaluate` reports Precision@k with the best achievable value.
* **Viewing records:** when a room ends, everyone who was connected gets one interaction (watched = the room's position, completed at
  90 %, ignored below 30 s) with the others as co-watchers. This is an approximation: it does not track each person's own screen time.
* Rooms empty for 10 minutes are ended automatically (`EMPTY_ROOM_TTL_MS`).

## Known limitations
* **The Spring layer has not been compiled or run** where this was written (Maven Central was unreachable). The framework-free
  `core/` package was compiled and its tests run (100 passing). The Spring classes were only syntax- and symbol-checked against the
  project's own types; the Spring/JPA/WebSocket tests (`*IT`, `*IntegrationTest`) have never run. Run `mvn test` first and expect to fix small issues.
* No sample video is bundled (`videoAssetUrl` is a placeholder). One server process is assumed.
