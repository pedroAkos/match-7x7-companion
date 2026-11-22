# Match 7x7 companion – Wear OS proof of concept

## What this is
An existing web app tracks attendance and statistics for casual football matches. Today, whoever scores has the match's logger step off the pitch to record the event on a phone, including who scored and who assisted.

This project is a **proof of concept** of a Wear OS watch app that lets one player (the single writer) log goals live in a few taps: **Goal -> team -> scorer -> assist -> Confirm** (see "Tap flow" below). It runs on the Wear OS emulator only (no real watch yet). There is no real backend yet either: a small mock server stands in for the web app.

The proof of concept must demonstrate the technical risks, not polish:
1. The watch UI flow works and is fast to use.
2. Events are queued locally on the watch and survive restarts and no connectivity.
3. Queued events upload to an HTTP endpoint, idempotently.

## Project facts (already set up)
- Package / applicationId: `com.example.match_7x7_companiom`
- Wear Compose Material 3 template; `MainActivity` in `presentation/`, theme in `presentation/theme/`
- minSdk 30, compileSdk/targetSdk 37, AGP 9.5.2, Kotlin 2.2.10
- Dependencies are managed in `gradle/libs.versions.toml`; add new ones there, not inline
- Standalone watch app (`com.google.android.wearable.standalone` = true)
- Emulator: a Wear OS virtual device created in Android Studio. The Android SDK path is in `local.properties`.

## Match life cycle on the watch
The watch is an offline recorder; the server holds the match and roster before kick-off. Phases (derived from the stored match, see `Phase` in `MatchViewModel.kt`):
**No match** -> (Load match: pick from the server's list, confirm details, load) -> **Loaded** -> (Kick-off) -> **Running** -> (End match) -> **Ended** -> (Sync) -> **Synced** -> (Close match, after the server's summary is shown) -> **No match**.
- Unload is allowed only before kick-off. After kick-off the data can only go through Close, after a confirmed sync.
- Editing anything after a sync makes the match Ended (unsynced) again; it must sync again before it can be closed.
- Only scheduled matches can be loaded; finished ones are listed read-only with their score.
- Sync is available only after End match. Nothing is sent during the match.

## Tap flow (the core of the app)
1. **Home:** match time (mm:ss), score, a big GOAL button, Own | Log, and a phase button (Unload / End match / Sync / Close match). Tapping Goal stamps `occurredAt` (UTC, now) and immediately stores a PARTIAL event, so nothing is lost even if the app dies.
2. **Team:** Team A | Team B (own goal: the team of the player who put it in; the goal is credited to the other team).
3. **Scorer:** that team's roster. 4. **Assist:** same team without the scorer, plus **None** (own goals have no assist step).
5. **Confirm:** Confirm commits the event; letting the 30 s timer run out keeps the partial event (time only); swipe back goes to the previous step.
- Inactivity timeout on every step: 30 s (`ABANDON_AFTER_MS`). Abandoning keeps the partial event.
- The Log lists all events; tap one to edit: move in time (+-1 min, +-10 s), change players, or delete (two taps).
- Large touch targets; keep text short.

## Data model
Each event (Room table `events`):
| field | notes |
|---|---|
| eventId | UUID generated on the watch |
| matchId | from the loaded match |
| type | GOAL or OWN_GOAL |
| teamId | derived from scorer (opposing team for own goals); null while partial |
| scorerId | null for partial events |
| assistId | null = none or not filled in |
| occurredAt | UTC timestamp of the first tap |
| status | COMPLETE or PARTIAL |

The match row (`local_match`) holds kickoffAt, endedAt, a `revision` that increases on every change, and `syncedRevision` (equal = synced) plus what the server reported at the last sync. There is no per-event synced flag.

## Server API and sync
The mock server is a dumb in-memory state holder (`mockserver/main.go`, standard library only, `go run .` in that folder, port 8080, state resets on restart):
- `GET /matches` -> list of summaries (id, scheduledAt, status SCHEDULED/FINISHED, teams, playerCount, score, goalCount, partialCount, eventIds).
- `GET /matches/{id}` -> match with teams and players.
- `PUT /matches/{id}/result` -> body `{kickoffAt, endedAt, events:[...]}`; the server **replaces** its copy (idempotent, so retries are safe and deletions/edits need no special handling) and answers with the summary.
- The watch counts the match as synced only if the answer lists every event id it sent. Afterwards it shows the server's numbers next to its own.
- From the Android emulator the Mac is at `http://10.0.2.2:8080`; cleartext HTTP is allowed for debug builds only (`app/src/debug`).
- No extra dependency for HTTP: `HttpURLConnection` + `org.json`.

## Status
Done: hello world, UI flow, Room persistence, match life cycle with list/load/sync/close, mock server. Not done: WorkManager retry, ambient mode, anything in "not in this proof of concept".

## Working rules
- After each step, build and run, and report what was verified. Do not claim something works without having built it.
- Keep the scope tight. Not in this proof of concept: auth/tokens, the real web app, a phone companion app, Play Store distribution, ambient-mode and battery work.
- Prefer small, readable Kotlin and Compose over abstractions. No dependency injection framework.
- Ask before adding a dependency that is not listed above.
