# Retranslator

A proprietary, fully offline-capable multilingual translator app for Android, originally built for and tested on a Retroid Pocket 2+ handheld (Unisoc T310, Android 9 / API 28, arm64-v8a). No root or OS-level modification required — it installs as a normal sideloaded app.

## Why

Built as an alternative to installing existing translator apps or relying on paid cloud translation APIs. Every capability runs on-device: no API keys, no subscriptions, no billing-gated dependencies, no account required.

## Features

- **Translate** — text translation across ~59 languages (Google ML Kit Translate), with automatic source-language detection (ML Kit Language ID) and per-language-pair offline model downloads.
- **Conversations** — a turn-taking two-way interpreter mode: listen (source language) → translate → speak (target language) → switch turn, with a live transcript and optional per-turn audio recording.
- **Practice** — pronunciation practice: hear a reference pronunciation via offline text-to-speech, record your own attempt, and play both back to compare by ear. (No automated pronunciation scoring — this is a listen-and-compare tool, not a grader.)
- **Offline speech-to-text** — [Vosk](https://alphacephei.com/vosk/) (Apache-2.0), 25 languages available as on-demand-downloaded models, fully offline after download.
- **Offline text-to-speech** — [eSpeak NG](https://github.com/espeak-ng/espeak-ng) (GPL-3.0), 100+ voices bundled directly in the APK, zero setup or download required.

## Architecture

Three tabs (Translate / Conversations / Practice) under one `BottomNavigationView`, sharing a single `EspeakEngine`, `VoskEngine`, and `MicPipeline` so speech recognition and audio recording never contend for the microphone.

## Requirements

- minSdk 28 (Android 9+)
- arm64-v8a device
- ~3.5GB free storage recommended for downloaded language packs (translation packs ~30MB each, Vosk STT models ~40–140MB each)

## Building

```bash
./gradlew assembleDebug
```

## Installing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License notes

This project bundles eSpeak NG, which is licensed GPL-3.0. Redistributing this app (beyond personal sideloading) carries GPL source-availability obligations for that component.
