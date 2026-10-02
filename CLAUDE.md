# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Life Streaming Radio is an Android app for streaming live radio from "House of Life" church (JKI Injil Keselamatan). It plays a single internet radio stream with media notification controls. The UI is in Indonesian.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release AAB (uses ProGuard minification + resource shrinking)
./gradlew bundleRelease

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

## Architecture

This is a single-module Gradle project (`app/`) using **Kotlin**, **View Binding**, and **AndroidX Media3** for audio playback.

### Key components

- **`NewRadioService`** — `MediaSessionService` that owns the player stack and the `MediaSession`: `ExoPlayer` (local) → `CastPlayer` (moves playback between phone and TV; falls back to plain ExoPlayer when Google Play services is missing, e.g. Huawei) → `RadioSessionPlayer` (what the session sees). Also handles the "Stop Radio" and sleep-timer custom commands, playback resumption, and polling the station API while casting.
- **`RadioSessionPlayer`** — `ForwardingSimpleBasePlayer` that cleans state in one place: splits ICY metadata, injects the song title while casting, and makes "play after pause" jump back to the live stream.
- **`RadioMediaItem`** — the only place the radio `MediaItem` is built (local, Cast, resumption). **`RadioTransferCallback`** gives the Cast receiver a station-titled item.
- **`CastSheets.kt`** — `CastSheetDialogFactory` (set on the Cast button in `MainActivity`) replaces mediarouter's Cast dialogs with dark bottom sheets: the device list, and volume + "Hentikan casting" while casting. `MediaRouteButton` only uses it up to Android 16; on Android 17+ it opens the system Output Switcher instead (see Android 17 rules). The sheets override `onCreateDialog` of `MediaRouteChooserDialogFragment`/`MediaRouteControllerDialogFragment`, which is safe because those base classes only touch their own dialog when it isn't null (checked against mediarouter 1.8.1 — recheck after a mediarouter bump).
- **`StreamRetryPolicy`** — load-error policy that retries stream errors for ~10 minutes (see Android 17 notes).
- **`MainActivity`** — binds via `MediaController`, play/pause UI, Cast button, ON AIR/OFFLINE pill, sleep-timer dialog, notification permission flow (Android 13+), in-app updates. Layouts: `layout/` (portrait) and `layout-land/` share `top_controls.xml` and `now_playing_controls.xml` through `<include android:id=…>` (ViewBinding only exposes included views when the include has an id).
- **`extensions/`** — `TrackMetadata.kt` (`parseIcyTrack`), `StationStatus.kt` (AzuraCast now-playing client), `RadioEventListener` (buffering notification, reconnect on `STATE_ENDED`), `Constants`.
- **`docs/cast-receiver/`** — custom Google Cast Web Receiver (static site, served by GitHub Pages from `/docs`). Plays the stream, shows logo + live title/artist from the station API and an audio-reactive spectrum (`spectrum.js`). Its `track.js` mirrors `parseIcyTrack`; run `node --test docs/cast-receiver/*.test.mjs`. Preview in a browser with `?preview` (click to start the audio and the spectrum).

### Configuration

- `gradle.properties` → `BuildConfig`: `RADIO_URL` (AzuraCast canonical `/listen/life_radio/radio.mp3`; do not use the port-based `/radio/8430/` path), `NOWPLAYING_URL` (`/api/nowplaying_static/life_radio.json`), `CAST_RECEIVER_APP_ID` (`87CED758` = the custom receiver in `docs/cast-receiver`, registered in the Google Cast SDK Developer Console; `CC1AD845` is Google's Default Media Receiver; must match the iOS app).
- Package: `com.injilkeselamatan.lifestreamingradio` (recently migrated from `com.church.injilkeselamatan.radiostream`).
- Min SDK 23, compile/target SDK 37 (Android 17), Java 17.
- Toolchain: AGP 9.4.1 + Gradle 9.8.0. AGP 9 provides **built-in Kotlin** — the `org.jetbrains.kotlin.android` plugin must NOT be applied. The Kotlin version (2.4.20) is raised above AGP's bundled KGP by the `kotlin-gradle-plugin` classpath entry in the root `build.gradle`. The Gradle 11 deprecation warning about `Configuration.setVisible` comes from AGP itself, not from our scripts.
- Repositories are declared centrally in `settings.gradle` (`dependencyResolutionManagement`, `FAIL_ON_PROJECT_REPOS`) — do not add `repositories {}` to module build files.
- `android.nonTransitiveRClass=true`, so library resources need a fully-qualified R class (e.g. `androidx.media3.session.R.drawable.…`).
- 16 KB page size (required by Play for Android 15+): `packaging.jniLibs.useLegacyPackaging = false`. The app has no native code of its own, but `media3-cast` pulls Compose, which ships `libandroidx.graphics.path.so`. `zipalign -c -P 16 -v 4 <apk>` only proves zip alignment; also check that every ELF `PT_LOAD` segment has `p_align >= 0x4000`.

### Media3 / ExoPlayer notes

- Playback uses `setMediaItem` (never `setMediaSource`, which Cast cannot transfer) through `DefaultMediaSourceFactory` with `StreamRetryPolicy`.
- `RadioMediaItem`: the local item has **no title** on purpose — ExoPlayer prioritizes `MediaItem` metadata over in-stream ICY, so a static title would hide every song. Live config must **not** equal `LiveConfiguration.UNSET` (`Builder().build()` does), otherwise Cast never sends `STREAM_TYPE_LIVE`; `mediaType = MEDIA_TYPE_RADIO_STATION`, otherwise the Cast converter assumes a movie.
- Only `media3-exoplayer`, `media3-session` and `media3-cast` (+ explicit `mediarouter`, which is runtime-only in media3-cast) are needed.
- **Never build `onConnect` permissions on top of `super.onConnect()`.** Since Media3 1.11.0 the default `MediaSession.Callback.onConnect` grants read-only access, so it returns an empty command set — including for the internal media-notification controller. Deriving from it silently kills the media notification and, with it, the foreground service. Grant commands explicitly via `ConnectionResult.AcceptedResultBuilder(session, controller)` + `DEFAULT_SESSION_COMMANDS` (the single-argument constructor is deprecated in 1.11.0).
- **ICY metadata (`"Artis - Judul"`) is parsed in exactly one place:** `parseIcyTrack()` in `extensions/TrackMetadata.kt` (unit-tested; the web receiver's `track.js` and iOS mirror it case-for-case). `RadioSessionPlayer.getState()` applies it, and because `ForwardingSimpleBasePlayer` derives events from that cleaned state, every controller — including `MainActivity` — already receives split metadata. **`MainActivity` must not parse again:** a title containing " - " would be split twice.
- Regression check after any media3 bump: with the app playing, `adb shell dumpsys activity services <pkg>` must show `isForeground=true` and `types=0x00000002` (mediaPlayback). Absence of the `isForeground` line means the FGS is gone — `startForegroundCount` alone does NOT prove it.

### Android 17 (targetSdk 37) rules — all verified on an API 37 emulator

- **Background audio hardening**: audio started from the background without a foreground service is muted silently (`dumpsys audio` shows `mutedState:op`, logcat `AudioHardening background playback muted`). Therefore:
  - Playback starts only from user actions. The service never auto-plays in `onCreate()`; `MainActivity` auto-plays once when the user opens the app; media buttons and the system media card resume through `onPlaybackResumption`.
  - `onCreate()` calls `addSession(mediaSession)`. When the system restarts a killed service there is no controller yet, but media keys still reach the session; without `addSession` Media3 never goes foreground and the audio is muted.
  - `StreamRetryPolicy` keeps the player in `BUFFERING` (so Media3 keeps the FGS) during network outages up to ~10 minutes, as the platform guidance requires. Test: play, go home, `adb shell svc wifi disable` + `svc data disable`, check `isForeground=true` while buffering, re-enable, and confirm audio resumes by itself with `mutedState:none`.
  - Don't call `startForegroundService()` manually; binding a `MediaController` is enough.
- **Local network permission**: casting needs no `ACCESS_LOCAL_NETWORK` because `CastParams.setShowSystemOutputSwitcherOnCastButtonClick(true)` uses the system Output Switcher on Android 17+ (older versions show the in-app Cast dialog and don't enforce the permission).
- **Large screens ignore orientation locks**: keep `layout-land/` working (phone landscape, tablets, foldables, split screen) and apply window insets (edge-to-edge is mandatory).

### Google Cast

- `MyApplication` calls `Cast.getSingletonInstance(this).initialize(CastParams…)` — don't use the old manifest `OPTIONS_PROVIDER_CLASS_NAME` path (it enables the Cast SDK's own media session, which conflicts with Media3).
- While casting, the receiver plays the stream itself, so the phone gets no ICY; `NewRadioService` polls `NOWPLAYING_URL` every 15 s and feeds `RadioSessionPlayer.setRemoteSongText`. The receiver's media metadata deliberately stays the station name ("Life Streaming Radio" / "House of Life" + logo): that is what the Google TV screensaver card, Google Home and other phones' Cast controls show, as the owner wants. The song title is only drawn on the receiver page. The round icon on that screensaver card is Google TV's Cast icon for every Cast app; a web receiver can't change it.
- `spectrum.js` routes the `<audio>` element through Web Audio. It sets `crossOrigin` only after probing the stream's CORS headers (without them the stream would fail to load), and never connects the element when the `AudioContext` can't run (the TV would go silent). The bars are DOM elements animated with `transform` + a CSS transition that runs on the compositor; per-frame canvas drawing stuttered on a real Google TV, so don't go back to it.
- Google's Default Media Receiver can only show the metadata sent at load time (no live titles, no bundled images, and it displays its own name) — that is why `docs/cast-receiver` exists.
- The receiver is served at `https://samuelmareno.github.io/life-streaming-radio/cast-receiver/` (GitHub Pages, `/docs` of the branch selected under Settings → Pages), and App ID `87CED758` in the Cast console points at that URL. A push to that branch changes the receiver for every user at once, with no app release. Before deleting or renaming the Pages branch (e.g. after merging into `main`), switch Pages to the new branch, otherwise casting breaks for everyone.
