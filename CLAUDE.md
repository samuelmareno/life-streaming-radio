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
- Min SDK 21, Target SDK 36, Java 17, Kotlin 2.1.0.

### Media3 / ExoPlayer notes

- Uses `ProgressiveMediaSource` with `DefaultHttpDataSource` for the radio stream.
- The `MediaItem` is configured as a live stream (hides seek bar).
- Still has a legacy dependency on `com.google.android.exoplayer:extension-mediasession` alongside the newer `androidx.media3` libraries.
