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

- **`NewRadioService`** — A `MediaSessionService` that owns the `ExoPlayer` and `MediaSession`. Handles foreground service lifecycle, media notifications (via `DefaultMediaNotificationProvider`), and a custom "Stop" command button. Streams from the URL defined in `BuildConfig.RADIO_URL`.
- **`MainActivity`** — Connects to `NewRadioService` via `MediaController`, manages play/pause UI, notification permission flow (Android 13+), and in-app updates via Google Play Core.
- **`RadioEventListener`** — `Player.Listener` that handles buffering notifications, auto-reconnect on stream end, and foreground service state transitions.
- **`Constants`** — Notification channel IDs, media IDs, and display strings.

### Configuration

- The radio stream URL is set in `gradle.properties` as `RADIO_URL` and exposed via `BuildConfig.RADIO_URL`.
- Package: `com.injilkeselamatan.lifestreamingradio` (recently migrated from `com.church.injilkeselamatan.radiostream`).
- Min SDK 23, compile/target SDK 37 (Android 17), Java 17.
- Toolchain: AGP 9.4.0 + Gradle 9.7.1. AGP 9 provides **built-in Kotlin** — the `org.jetbrains.kotlin.android` plugin must NOT be applied. The Kotlin version (2.4.10) is raised above AGP's bundled KGP by the `kotlin-gradle-plugin` classpath entry in the root `build.gradle`.
- Repositories are declared centrally in `settings.gradle` (`dependencyResolutionManagement`, `FAIL_ON_PROJECT_REPOS`) — do not add `repositories {}` to module build files.
- `android.nonTransitiveRClass=true`, so library resources need a fully-qualified R class (e.g. `androidx.media3.session.R.drawable.…`).
- 16 KB page size (required by Play for Android 15+): `packaging.jniLibs.useLegacyPackaging = false`. The app ships no native libraries of its own, so compliance depends only on dependencies staying `.so`-free. Verify with `zipalign -c -P 16 -v 4 <apk>`.

### Media3 / ExoPlayer notes

- Uses `ProgressiveMediaSource` with `DefaultHttpDataSource` for the radio stream.
- The `MediaItem` is configured as a live stream (hides seek bar).
- Only `media3-exoplayer` and `media3-session` are needed; there is no `PlayerView` (so no `media3-ui`) and no HLS source (so no `media3-exoplayer-hls`).
- **Never build `onConnect` permissions on top of `super.onConnect()`.** Since Media3 1.11.0 the default `MediaSession.Callback.onConnect` grants read-only access, so it returns an empty command set — including for the internal media-notification controller. Deriving from it silently kills the media notification and, with it, the foreground service (playback keeps running with no FGS and no controls). Grant commands explicitly via `ConnectionResult.AcceptedResultBuilder(session, controller)` + `DEFAULT_SESSION_COMMANDS` (the single-argument constructor is deprecated in 1.11.0).
- **ICY metadata (`"Artis - Judul"`) is parsed in exactly one place:** `parseIcyTrack()` in `extensions/TrackMetadata.kt`, covered by unit tests. The service applies it via a `ForwardingPlayer.getMediaMetadata()` override so the notification gets clean fields. Caveat: that override reaches the *notification* (built from `player.getMediaMetadata()`) but **not** `MediaController`, which receives the raw metadata object through the ForwardingPlayer listener callback — so `MainActivity` calls the same `parseIcyTrack()` too. Never write a second, separate parser; that is what made the activity and the notification disagree.
- Regression check after any media3 bump: with the app playing, `adb shell dumpsys activity services <pkg>` must show `isForeground=true` and `types=0x00000002` (mediaPlayback). Absence of the `isForeground` line means the FGS is gone — `startForegroundCount` alone does NOT prove it.
