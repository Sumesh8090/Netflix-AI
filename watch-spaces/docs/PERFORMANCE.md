# Performance: what was measured, how, and what it means

Two kinds of measurement, kept apart on purpose.

## 1. In-process numbers (measured; reproducible with `mvn test`)
`PerformanceSmokeTest` times the pure logic (no network, no database) and fails only on a large regression. Figures below were
measured on a small 2-CPU Linux sandbox with Java 21; a laptop is usually faster. Re-measure on your machine: the test prints them.

| Operation | Measured | Why it matters |
|---|---|---|
| Playback state machine `apply` (play/pause/seek) | about 0.06 - 0.1 microseconds | a room's play/pause/seek decision costs nothing |
| Drift evaluation | about 0.02 microseconds | run on every sync ping |
| Grounded Q&A answer, 13 and 20-entry timelines | p50 0.03 ms, p95 0.05 - 0.15 ms | the AI answer is far inside a 3 s budget; the rest of the latency is the network |
| Trivia planner, one 250 ms tick for one room | about 0.4 microseconds | 4 ticks/s x 1000 rooms is still negligible |
| Recommendation for one user (48 titles, 60 users, 639 interactions) | p50 0.12 - 0.36 ms, p95 0.2 - 0.5 ms | computed on demand, no retraining |
| Offline evaluation of all 60 users | about 11 ms | the admin evaluate endpoint is instant at this size |

Caveat: these are tiny datasets. The recommender is O(users x titles) per request; with tens of thousands of users you would add
candidate pre-filtering or a precomputed neighbour table (the per-interaction-version cache in `RecommendationService` is the first
step already in place).

## 2. Recommendation quality (measured, synthetic data)
Protocol (`HybridRecommender.evaluate`): for each user with at least 3 liked titles, hide every 2nd liked title, recommend 5,
count how many hidden titles appear. Precision@5 = hits / 5, averaged over users. A hold-out smaller than 5 caps what any model
can score, so the evaluation also reports the achievable maximum (0.880 here) and the normalised value.

Dataset: `SyntheticData` seed 42 (48 titles, 60 users, 639 interactions). Sweep of the blend weight
(final = alpha x content + (1 - alpha) x collaborative):

| alpha | 0.0 | 0.2 | 0.4 | 0.5 | 0.6 | **0.7** | 0.8 | 0.9 | 1.0 |
|---|---|---|---|---|---|---|---|---|---|
| Precision@5 | 0.357 | 0.490 | 0.610 | 0.670 | 0.700 | **0.703** | 0.707 | 0.703 | 0.703 |
| normalised | 0.405 | 0.557 | 0.693 | 0.761 | 0.795 | **0.799** | 0.803 | 0.799 | 0.799 |

Honest reading: results plateau from alpha 0.6 to 1.0. On this synthetic data, genre affinity explains almost everything
(users were generated from genre preferences), so the collaborative part adds little here; it earns its place on real data
where taste is not purely genre. alpha = 0.7 keeps a collaborative signal while sitting on the plateau. The numbers show the
pipeline works end to end; they are **not** evidence of quality on real viewers.

## 3. Socket-level numbers (tool provided; run it yourself)
`scripts/LoadTest.java` uses only the JDK. It registers throw-away users, creates rooms and drives real WebSocket traffic:

```bash
# server running, scratch database
BASE=http://localhost:8080 ROOMS=3 VIEWERS=9 SECONDS=30 java scripts/LoadTest.java
```
It measures and checks against targets (p95):

| Metric | How it is measured | Target |
|---|---|---|
| join | connect -> first `room.state` | <= 2000 ms |
| seek fan-out | host sends seek -> **slowest** viewer receives the update | <= 1000 ms |
| chat echo | viewer sends -> same viewer receives its own message | <= 1000 ms |
| AI answer | `room.ai.ask` -> matching `room.ai.answer` | <= 3000 ms |
| correctness | no `room.error`, no unexpected close, every client saw every chat message | none allowed |

Output ends with `RESULT: PASS` or `FAIL` and the exit code reflects it. Start small (3 rooms x 9 viewers), then raise `ROOMS` and
`VIEWERS` (max 49 per room) until a target fails; that is your capacity on that machine.

Status of these numbers: **not measured against the real server**, because the Spring layer could not be built where this project
was written. The tool itself was exercised against a small protocol-compatible stand-in server (to check its own logic: it
counted every seek and chat message correctly and exited PASS), but those stand-in latencies mean nothing and are not reported
here. Run the command above on your machine and paste your own table into this file.

## What keeps latency low by design
* Playback position lives in memory and is derived from (anchor + elapsed); nothing is written per heartbeat.
* Chat and the event log are buffered and written in batches (20 rows or 2 s), so the database never sees one write per message.
* Timelines and variation points are cached per title; the 250 ms trivia tick does no database work.
* One serialised JSON frame is built per broadcast and reused for every recipient.
* Per-user rate limits (chat 10/5 s, AI 5/10 s) bound abuse; slow sockets are bounded (5 s send time, 512 KB buffer) so one bad
  client cannot stall a room.

## Known limits
Single server process (room state is in memory), no horizontal scaling, no persistent per-person watch time (a viewing record
uses the room position). See `ARCHITECTURE.md`.
