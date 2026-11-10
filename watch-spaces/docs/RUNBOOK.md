# Runbook: how to set up, run, test and demo this project properly

Follow the steps in order. Each step has a **check**: do not move on until it passes.
Windows + Eclipse is assumed; terminal equivalents are given where they differ.

## 0. What you need installed
| Tool | Version | Check |
|---|---|---|
| JDK | 17 or newer (21 is fine) | `java -version` |
| MySQL Server | 8.x | `mysql --version` (or MySQL Workbench opens) |
| Eclipse IDE for Enterprise Java and Web Developers | recent (has Maven support built in) | Help > About |
| Git/Postman (optional) | any | for the Postman collection |

## 1. Prepare MySQL
1. Start MySQL and note the root password (or create a user).
2. Optional, the app creates the database for you. To create it yourself:
   ```sql
   CREATE DATABASE watchspaces CHARACTER SET utf8mb4;
   ```
**Check:** you can log in with `mysql -u root -p`.

## 2. Get the project onto disk
1. **Extract** `watch-spaces-100percent.zip` to a **new, empty folder** such as `C:\work\watch-spaces`
   (do not import the zip itself and do not reuse an old folder).
2. If an older copy is open in Eclipse: right-click it > Delete > **untick** "Delete project contents" > OK. Then close any program
   that has the old folder open. This avoids the "cannot delete content for read only resource" error.

## 3. Create your settings file
In the project root (the folder that contains `pom.xml`) copy `.env.example` to `.env` and edit it:
```
DB_URL=jdbc:mysql://localhost:3306/watchspaces?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
DB_USER=root
DB_PASSWORD=your-mysql-password
JWT_SECRET=any-random-text-at-least-32-characters-long
SEED_ENABLED=true
SEED_PASSWORD=Demo-Passw0rd
```
* `JWT_SECRET` must be at least 32 characters. `SEED_PASSWORD` (8+ characters) becomes the password of every demo account.
* No quotes, no spaces around `=`. `.env` is git-ignored, so your password never gets committed.
* An empty MySQL password is written as `DB_PASSWORD=`.
**Check:** the file is named exactly `.env` (Windows Explorer: enable "File name extensions" so it is not `.env.txt`).

## 4. Import into Eclipse
1. File > Import > Maven > **Existing Maven Projects** > Next.
2. Root directory: your extracted folder > it shows `pom.xml` ticked > Finish.
3. Wait for the progress bar at the bottom right (Maven downloads libraries, 1-3 minutes the first time, needs internet).
4. Right-click the project > Maven > **Update Project...** > tick "Force Update of Snapshots/Releases" > OK.
5. Project > Properties > Java Compiler: compliance 17 or higher (if the project shows red).

**Check:** the **Problems** tab (Window > Show View > Problems) has **no errors**. Warnings are fine.
If it has errors: copy them (all of them) and send them to whoever is helping you; do not try to run yet. See section 9.

## 5. Run the tests first
Right-click the project > Run As > **Maven test** (or in a terminal: `mvn test`).
* Takes about 1 minute, plus about 30 seconds for `WsFlowIT`, which uses real timers.
* The tests use an in-memory H2 database, so they do **not** need MySQL.
**Check:** `BUILD SUCCESS`. The console prints measured performance numbers (`state machine`, `QA`, `recommend()`, `Precision@5`).
If some tests fail, copy the failed test names and the first lines of each error.

## 6. Start the server
In the Project Explorer open `src/main/java/com.watchspaces/WatchSpacesApplication.java`, then
right-click that file > Run As > **Java Application**. (Do not use the toolbar green button; it may run the last-used, different program.)
Terminal alternative: `mvn spring-boot:run`.

**Check** (console): the last lines say `Started WatchSpacesApplication in ... seconds` and `seed.title ...`, `seed.recommendations ...`.
Then open in a browser: `http://localhost:8080/actuator/health` -> `{"status":"UP"}`.

Common start-up problems:
| Message | Fix |
|---|---|
| `Could not resolve placeholder 'DB_PASSWORD'` / `'JWT_SECRET'` | `.env` is missing, misnamed, or not in the project root. Eclipse's working directory must be the project root (the default). |
| `Access denied for user 'root'` | wrong `DB_PASSWORD` or `DB_USER` |
| `Communications link failure` | MySQL is not running or the port in `DB_URL` is wrong |
| `Port 8080 was already in use` | stop the other program, or add `PORT=8081` to `.env` |
| `SEED_PASSWORD must be set (min 8 chars)` | set it in `.env`, or `SEED_ENABLED=false` |
| JWT secret too short / weak-key error | make `JWT_SECRET` 32+ characters |
| Seeds do not appear after changing data | seeding only runs on empty tables: drop the database (`DROP DATABASE watchspaces;`) and restart |
| Console shows output of some other program | you ran the wrong class; stop it and run `WatchSpacesApplication` as in step 6 |

## 7. See it working
### 7a. REST with Postman (or curl)
Import `docs/watch-spaces.postman_collection.json`.
1. Run the three **Login** requests and paste each `accessToken` into the collection variables `hostToken`, `viewerToken`, `adminToken`
   (accounts: `host@`, `viewer@`, `admin@watchspaces.local`, password = your `SEED_PASSWORD`).
2. **Create watch space (host)** -> `201` with an `inviteCode`. **Create (viewer)** -> `403`: proof the role rules are enforced by the server.
3. **Join by code (viewer)**, then **Dashboard**, **Recommendations**, **Record an interaction**, **Admin: recommender evaluation**.

### 7b. Real-time demo (three scripted clients)
Open a terminal in the project folder:
```
set SEED_PASSWORD=Demo-Passw0rd         (PowerShell: $env:SEED_PASSWORD="Demo-Passw0rd")
java scripts/SyncDemo.java
```
It prints, with delays: sync, a viewer being refused when it tries to pause, drift correction, a trivia card appearing by itself, chat
with HTML escaped, a reaction, AI answers (one with sources, one declined), mute, reconnect with chat replay, and session end.

### 7c. Try the new Week 3 features by hand
Connect a WebSocket client (Postman > New > WebSocket) to `ws://localhost:8080/ws/watch-spaces/<id>?token=<accessToken>&locale=hi`.
Create the room with `"votingEnabled":true`. Then send (host):
```
{"event":"room.variation.open","payload":{"variationId":"v_02","durationSeconds":10}}
```
and from each member `{"event":"room.variation.vote","payload":{"variationId":"v_02","optionId":"b"}}`. After 10 s everyone receives the same
`room.variation.applied`. ("The Pier" has two variation points: `v_01` a subtitle at 1000 s and `v_02` a narrative choice at 1800 s; list them with
`GET /api/v1/titles/1/timeline?from=0&to=2400`.)
Seek the host to 995 s (`{"event":"room.playback.command","payload":{"action":"seek","positionSeconds":995}}`), then play. About 5 s later a viewer connected with `locale=hi` receives `room.subtitle.swap` in Hindi.

## 8. Load test and numbers
With the server running (use a scratch database; it creates throw-away users):
```
set BASE=http://localhost:8080
set ROOMS=3
set VIEWERS=9
set SECONDS=30
java scripts/LoadTest.java
```
It prints join / seek fan-out / chat echo / AI answer latency (p50, p95, max) with PASS/FAIL. Increase `ROOMS` and `VIEWERS` until something fails,
then write your table into `docs/PERFORMANCE.md` section 3. Explanation of every number: `docs/PERFORMANCE.md`.

## 9. If something fails
Send, in this order: (1) the full **Problems** tab list or the first 30 lines of the Maven/console error, (2) the Java version (`java -version`),
(3) which step number failed. Do not edit code to "make it compile" before sharing the error; most failures here are one-line fixes
once the exact message is known, and nothing in the Spring layer has been compiled by its author yet.

## 10. Before you present or submit
* Run `mvn test` and see BUILD SUCCESS.
* Run the demo (7b) once and read its output so you can explain each line.
* Run the load test (8) once and keep your table.
* Read `docs/ARCHITECTURE.md` (one page) and the "Behaviour worth knowing" part of `README.md`.
* Be ready to say what is limited: single process, retrieval-only AI, synthetic recommender data, no UI.
