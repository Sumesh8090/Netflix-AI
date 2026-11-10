# Architecture

Backend-only Watch Spaces: Spring Boot 3.3 (Java 17), Spring Security + JWT, Spring Data JPA (Hibernate), Spring WebSocket, MySQL 8.

```
 clients (Postman / scripts / a future UI)
     |  REST (JWT)                          |  WebSocket  ws://.../ws/watch-spaces/{id}?token=JWT
     v                                      v
 web/*Controller  --->  service/*  <---  ws/RoomSocketHandler  <--- ws/TriviaScheduler (250 ms tick)
                          |                    |       |
                          |                    |       +--> ws/VariationCoordinator (votes, subtitle choice)
                          v                    v
                     repo/* (JPA)         ws/RoomRegistry --> ws/RoomRuntime (live room, in memory)
                          |                                          |
                          v                                          v
                        MySQL                              core/*  (pure Java, no Spring, unit tested)
```

## Packages
| Package | Responsibility |
|---|---|
| `core` | All decision logic, with no framework: `PlaybackStateMachine`, `DriftCorrector`, `RoomModel` (members, host, mute, lock), `GroundedQa`, `TriviaPlanner`, `VariationRules`, `HybridRecommender`, `InteractionRules`, `TimelineValidator`, `RateLimiter`, `WriteBuffer`, `LatencyStats`, `SyntheticData`. Fast to test, no I/O. |
| `security` | JWT creation/validation, request filter, role rules (`SecurityConfig`). |
| `web` | REST controllers, request/response shapes, one error format `{"error":{"code","message","details?"}}`. |
| `service` | Transactions and persistence: auth, sessions, timelines, AI answers, variations store, recommendations, dashboard, seeding, buffered writers, metrics. |
| `ws` | The real-time layer: handshake auth, room registry, live room state, event handler, schedulers, lifecycle (end exactly once). |
| `entity` / `repo` | JPA entities and Spring Data repositories. |

Rule of thumb: decisions in `core` (testable here), glue in Spring (thin).

## Data model (MySQL, see `db/schema.sql`)
`users`, `refresh_tokens` (hashed), `titles`, `timeline_events` (+ `variation_options` for variation points), `watch_spaces`,
`watch_space_participants`, `chat_messages`, `interactions` (feeds the recommender), `room_variations` + `variation_votes`,
`event_log` (append-only; analytics are replayed from it). Hibernate creates/updates tables by default (`DDL_AUTO=update`);
`schema.sql` is the equivalent hand-written DDL (use with `DDL_AUTO=validate`).

## Key flows
**Join.** REST `join` (invite code) records the participant; the client then opens the socket. The handshake interceptor
validates the JWT and (optionally) `?locale=`. The handler adds the user to the in-memory `RoomModel`, sends `room.state`
(playback, participants, last 30 chat messages, subtitle choices, decided votes, open votes) and announces presence.

**Playback.** Only the current host may send `room.playback.command`. The server applies it to the state machine, re-bases the trivia
ticker, and broadcasts `room.playback.update` with the authoritative position and server time. Viewers send `room.sync.ping`;
the server answers with a correction (none / micro seek / hard seek) computed by `DriftCorrector`.

**Host disconnect.** `RoomModel` pauses playback at once; viewers are told; after 15 s the earliest-joined connected member is promoted
(`room.host.changed`). Viewers who drop get a 5 s reconnect window before a "left" is announced.

**Chat.** Validate (length, rate, muted?) -> escape HTML -> assign the next sequence number and broadcast in one locked step (same order
for everyone) -> buffer the row for batched insert.

**AI co-pilot.** `GroundedQa` scores the question against timeline entries up to the room position only (the client's claimed
timestamp is clamped), builds the answer from templates around the best entries, cites them, and declines when nothing matches.
No generative model is used, so it cannot state anything not in the metadata.

**Trivia and subtitle swaps.** One ticker (250 ms) per process checks every occupied room: markers crossed since the last tick fire once;
seeks never fire markers. Subtitle points are pushed to each viewer in their own locale.

**Voting.** Host opens a vote on a variation point (`votingEnabled` rooms only). Members vote once (re-vote replaces). A timer, or the
host, closes it: most votes wins, ties and no votes keep the original, the result is persisted once and broadcast identically.

**End of session.** `RoomLifecycle.end` runs exactly once: announce `room.ended`, flush buffers, freeze analytics on the row, close sockets,
then write a viewing record for everyone who was in the room (feeds recommendations).

## Security
Server-side RBAC on every route and on the socket; refresh tokens stored hashed and rotated; BCrypt passwords; all secrets from environment
variables (or a git-ignored `.env`); stored user text is HTML-escaped; admin accounts cannot be self-registered; per-user rate limits;
the AI endpoint ignores any user id in the body and clamps the claimed timestamp.

## Decisions and trade-offs
* **Retrieval-only AI** instead of an LLM: no key, no cost, no hallucination, millisecond latency; less fluent and limited to authored metadata.
* **In-memory room state:** fastest, simplest; a restart loses live position (rooms can be re-joined) and open votes, and it limits you to one process.
  Scaling out would need sticky routing or a shared store.
* **Batched writes:** far fewer database round trips; up to 2 s of chat could be lost if the process crashes.
* **Recommender computed on demand:** no training pipeline and always fresh; cost grows with data (see `PERFORMANCE.md`).
* **Pure `core` package:** the risky logic is covered by 100 unit tests that run without Spring or a database.

## Known limitations
* Single server process; no sample video; no UI.
* A viewing record uses the room's position, not each person's own watch time.
* The Spring layer and the `*IT` / `*IntegrationTest` tests were written without being able to build them (see README); expect to fix small issues on first run.
