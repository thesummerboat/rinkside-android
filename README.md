# Rinkside for Android

A small native Android wrapper around the [Rinkside](https://rinkside.jonaspettersson1991-18b.workers.dev) hockey web app.
It shows the web app full screen and adds one thing a web page cannot do reliably on its own: **match notifications** for the teams you follow.

| Event | Notification (English / Swedish) |
| --- | --- |
| Match start | `Game start: Home – Away` / `Matchstart: Home – Away` |
| Goal | `Goal! Home 2–1 Away` / `Mål! Home 2–1 Away` (one notification per goal; for SHL goals the text also names the scorer and the game time, `Home scored · Name (35:53)`) |
| Disallowed goal (the score goes down) | `Goal disallowed: Home 1–1 Away` / `Målet bortdömt: Home 1–1 Away` |
| Match end | `Final: Home 3–2 Away` / `Slut: Home 3–2 Away`, with `OT` / `SO` (`förl.` / `str.` in Swedish) when it went past regulation time |

Texts are shown in Swedish when the phone language is Swedish and in English otherwise. Leagues covered: NHL, SHL, HockeyAllsvenskan and Hockeyettan. Other leagues in the web app are not watched.

> Status: a test build. The goal is to find out whether notifications on a real phone are reliable enough, before anything bigger is built.

## How it works

1. **The web app is the source of truth for what you follow.** After the page has loaded (and again when the app is paused) the app reads your followed teams and their upcoming games out of the web app's own data with a small, read-only JavaScript snippet (`app/src/main/assets/read-favourites.js`). Nothing is changed in the page. The result (followed teams plus games in the next 8 days) is stored on the phone in `SharedPreferences`.
   If you follow no team, nothing is scheduled and no notification is ever sent. The settings screen says so.
2. **An exact alarm starts the watcher.** The app schedules an alarm 10 minutes before the next followed game (`setExactAndAllowWhileIdle`; if the phone refuses exact alarms it falls back to `setAndAllowWhileIdle`). The alarm is re-created after a reboot and after the app is updated.
3. **A foreground service polls the live data.** `GameWatchService` shows a quiet, low-importance ongoing notification (`Rinkside följer N matcher`) and fetches the *same live overlay files the web app itself uses*, from `<site>/api/live/<name>.json`, every 15 seconds. Only the overlays needed for the games being watched are fetched (`nhl-today`, `shl-today`, `swe-today`, `shl-schedule-status`).
   **SHL games also get the live stream.** While at least one watched SHL game is inside its watch window, the service holds one connection to the SHL's public live score stream (`https://game-broadcaster.s8y.se/live/game`, Server-Sent Events), the same stream the web app reads. A goal arrives there within seconds, where the overlay files are up to about a minute behind (the Worker refreshes them every 30 seconds). The overlay polling goes on in parallel: it is the fallback when the stream is down, and it carries match start and end for the other leagues and the NHL.
4. **A pure Kotlin engine decides what happened.** `app/src/main/java/se/jonasp/rinkside/engine/` has no Android dependencies and is covered by JVM unit tests. The first reading of a game only initialises its state (so opening the watcher mid-game does not spam you). After that:
   - scheduled → live gives *match start*;
   - the score going up gives one *goal* per goal;
   - the score going down gives *goal disallowed*;
   - live → finished gives *match end* with the final score (and OT/SO).
   Per-game state is persisted before notifications are posted, so a restarted service never repeats a notification.
   The stream and the overlays feed this same engine, so a goal that one of them announced is never announced again by the other. A goal is identified by the game and its score line (`2-1`), and one score line gives one notification, as in the web app; a score that goes down is announced once per drop (`2-1>1-1`). Both sets are persisted with the game state.
   When the stream connects it first replays recent messages, old goals included. That replay is never treated as news: it only fills in scorer names. Only a game the overlay files have already started can be followed by the stream, so a game that was half over when the watch began does not announce its earlier goals.
5. **The service stops itself** when all watched games are finished (or given up) and schedules the next alarm.

Tapping a notification opens the app on that match (`<site>/#match/<id>`).

Scorer names are not in the overlay files, only in the live stream. A goal that the stream announces carries the scorer and the cumulative game time (a goal at 15:53 in the second period shows as `35:53`). When the overlay is first to see a goal, the notification is posted without a name and updated in place (without a new sound) if the stream names the scorer a moment later. Goals in the other leagues and in the NHL have no scorer.

## The settings screen "Notiser"

Long-press the launcher icon and choose **Notiser** or **Testa notiser** (static launcher shortcuts; the web UI has no link to this screen). It shows:

- whether notifications are allowed (with a button to ask / to open system settings),
- whether exact alarms are allowed,
- whether Rinkside is exempt from battery optimisation (with a button),
- the followed teams and the next followed games,
- the watcher state, last poll time and a short event log,
- the live stream: off (no SHL game on), connecting, connected (with the time of the last message about a followed game) or reconnecting (attempt number and next try),
- test buttons: **Testa: matchstart**, **Testa: mål**, **Testa: bortdömt mål**, **Testa: matchslut**,
- **Simulera en match**: plays a scripted SHL game (start, 1-0, 2-0, disallowed goal, 1-1, overtime, 2-1 final) through the *real* parser and detection engine, one step every 3 seconds, and posts real notifications,
- **Starta bevakning nu**: starts the watcher immediately for the followed games.

The test buttons only post notifications. The simulation is the closest thing to a real match without waiting for one.

On Android 13 and later the app asks for notification permission the first time it starts.

## Privacy

- The app talks to **two hosts**: the Rinkside site (`BuildConfig.BASE_URL`), both in the WebView and for the live overlay files, and, only while a followed SHL game is on, the SHL's public live stream at `game-broadcaster.s8y.se` (the stream the web app reads too; no account or key, nothing is sent but the request itself). That host sees the phone's IP address and a `RinksideAndroid/<version>` User-Agent. Links to any other host open in the phone's browser instead.
- **No Google services, no Firebase, no analytics, no crash reporting, no ads, no push service.** Notifications are produced locally on the phone from the live files it fetches itself.
- The app has **no third-party runtime libraries** at all, only the Android framework. (JUnit and `org.json` are used by the unit tests only.)
- Followed teams and per-game state are stored in the app's private storage and are not sent anywhere. Android backup is disabled for the app.
- Cleartext HTTP is disabled; only HTTPS is allowed.

## Install on your phone with Obtainium

[Obtainium](https://obtainium.imranr.dev/) installs and updates apps straight from GitHub releases.

1. Install Obtainium.
2. In Obtainium choose **Add app** and paste `https://github.com/thesummerboat/rinkside-android`, then **Add**.
3. If the repository is **private**, first add a GitHub access token in Obtainium: Settings → GitHub → Personal access token. A fine-grained token with read access to this repository's *Contents* (or a classic token with the `repo` scope) is enough.
4. Tap **Install**. Android asks once whether Obtainium may install apps; allow it.
5. Open Rinkside, allow notifications, and follow a team in the web app.

A step-by-step guide in Swedish is in [`docs/OBTAINIUM_sv.md`](docs/OBTAINIUM_sv.md).

Tags with a suffix, such as `v0.2.0-beta1`, are published as *pre-releases*. Obtainium ignores those unless **Include prereleases** is turned on for the app.

## Making a release

Releases are built by GitHub Actions (`.github/workflows/build.yml`).

**One-time setup:** create four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the release keystore, base64-encoded on one line (`base64 -w0 rinkside-release.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | the key alias |
| `ANDROID_KEY_PASSWORD` | the key password (for a PKCS12 keystore the same as the keystore password) |

The keystore is **not** in this repository and must never be committed (`*.jks` and `*.keystore` are git-ignored). Keep a backup: Android refuses to update an installed app that is signed with a different key, so losing the keystore means every user has to uninstall and reinstall.

**For each release:** change the version in the `VERSION` file (e.g. `0.1.0` -> `0.1.1`) and push to `main`. When no GitHub Release `v<VERSION>` exists yet, the workflow makes it (GitHub creates the tag at that commit). Pushing a tag `vX.Y.Z` works too.

The workflow runs the unit tests, builds a **signed** release APK with `versionName` taken from the tag and `versionCode = major*1000000 + minor*1000 + patch`, checks with `apksigner` that it is signed and not debug-signed, and creates a GitHub Release named after the tag with the APK attached as `rinkside-<version>.apk`. If one of the secrets is missing, a tag build fails with a clear message and a push to main only warns and makes no release; an unsigned or debug-signed release is never published.

Every push to `main` (and every manual run) builds the unit tests and a **debug** APK, uploaded as a workflow artifact (`rinkside-debug-apk`). Debug APKs are signed with a throw-away debug key, so they cannot update a release install; use them only for quick testing.

## Limits and honest caveats

- **Polling, not push.** There is no push server. The app polls the live files every 15 seconds, but only while a followed game is on (from 10 minutes before the start until the game is finished, at most 4 hours after the start). Between games the app does nothing at all. SHL games add one open connection to the live stream for the same period, inside the same foreground service and under the same wake lock (no extra permission, no extra service). If it drops, it reconnects after 2, 5, 10 and 30 seconds and then every 60 seconds; a connection that is silent for 90 seconds is treated as dead. While the stream is down, goals come from the overlay files as before.
- **Android may delay or kill things.** Under battery saver, aggressive manufacturer task killers or without the battery-optimisation exemption, the alarm and the service can be delayed or stopped. The settings screen shows the relevant states; allow exact alarms, exempt the app from battery optimisation, and allow notifications for the best result. A watchdog alarm restarts the service if it is killed during a game.
- **Notification delay.** For SHL goals the stream makes it a matter of seconds. For everything else a notification can be at best as fresh as the live data on the Rinkside server (refreshed every 30 seconds), plus up to 15 seconds of polling, so about a minute. It is not a replacement for a live-score service during very fast sequences.
- **The stream is an unofficial feed.** It is not documented by the SHL and may change or go away; the app then falls back to the overlay files. A goal scored in the first seconds after a (re)connection may reach you through the overlay files rather than the stream, since the replay of old messages is held back while it lasts.
- **One notification per score line.** If a goal is disallowed and the same score is reached again, the second one is silent (the web app does the same).
- **Disallowed goals** are detected only as "the score went down". In an SHL game the stream makes a goal that is scored and removed within seconds visible; elsewhere, a goal that is scored and removed between two polls (within 15 seconds) is not seen at all.
- **Shootouts** do not produce goal notifications; the final score and `SO` are shown at the end.
- **Followed teams are read from the web app.** If you change what you follow, open the app once so the list is refreshed (it is also refreshed when the app goes to the background).
- Only the leagues listed above are watched, and only teams you follow.
- Android 8 (API 26) or newer.

## Changing the site address

The address is a build-time constant: `BuildConfig.BASE_URL`, set in `app/build.gradle.kts` from the Gradle property `rinksideBaseUrl`. To point the app somewhere else, change the default there or build with `-PrinksideBaseUrl=https://example.org`.

## Building and testing locally

Requirements: JDK 17 and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest   # engine unit tests (pure JVM)
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

The detection engine (`engine/`) and its tests need no Android SDK. The tests use synthetic overlay payloads in `app/src/test/resources/overlays/`, shaped like the real ones; each file is marked with a `_note` saying so. The stream tests use `app/src/test/resources/stream/`: a trimmed copy of the web app's own `shl-live-game.json` fixture and a replay burst in the SSE wire format. The real stream could not be reached while this was written, so its message shapes come from the web app's reader and from lines supplied by the maintainer.

## Project layout

```
app/src/main/java/se/jonasp/rinkside/
  MainActivity.kt        WebView, favourites snapshot, deep links
  SettingsActivity.kt    the "Notiser" screen
  GameWatchService.kt    foreground service, 15 s polling loop, owns the live stream
  LiveStream.kt          the SHL live stream connection (HttpURLConnection, back-off, status)
  Scheduler.kt           exact alarms (+ watchdog / retry)
  AlarmReceiver.kt  BootReceiver.kt
  Notifier.kt            channels and notification texts
  OverlayHttp.kt         HttpURLConnection fetch of the overlay files
  Simulator.kt           the "Simulera en match" playback
  Store.kt               SharedPreferences
  engine/                pure Kotlin: parser, detector, watch plan, snapshot, simulation script,
                         ShlStream (SSE reader and stream parser), StreamFeed (replay burst, de-dup), GameClock
app/src/main/assets/read-favourites.js
app/src/test/            JUnit 4 tests and synthetic example payloads
docs/OBTAINIUM_sv.md     Swedish install guide
```
