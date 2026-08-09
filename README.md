# Retranslator

A proprietary, fully offline-capable multilingual translator app for Android, originally built for and tested on a Retroid Pocket 2+ handheld (Unisoc T310, Android 9 / API 28, arm64-v8a). No root or OS-level modification required — it installs as a normal sideloaded app.

## Why

Built as an alternative to installing existing translator apps or relying on paid cloud translation APIs. Every capability runs on-device: no API keys, no subscriptions, no billing-gated dependencies, no account required.

## Features

- **Translate** — text translation across ~59 languages (Google ML Kit Translate), with automatic source-language detection (ML Kit Language ID) and per-language-pair offline model downloads.
- **Conversations** — a turn-taking two-way interpreter mode: listen (source language) → translate → speak (target language) → switch turn, with a live transcript and optional per-turn audio recording.
- **Practice** — pronunciation practice: hear a reference pronunciation via offline text-to-speech, record your own attempt, and play both back to compare by ear. (No automated pronunciation scoring — this is a listen-and-compare tool, not a grader.)
- **Offline speech-to-text** — [Vosk](https://alphacephei.com/vosk/) (Apache-2.0), 25 languages available as on-demand-downloaded models, fully offline after download.
- **Offline text-to-speech, two tiers**:
  - **eSpeak NG** (GPL-3.0), 100+ voices bundled directly in the APK, zero setup or download required, always available. Formant-synthesized — intelligible but robotic.
  - **Natural voice (neural TTS)** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) running downloaded [Piper](https://github.com/rhasspy/piper) VITS voice models, on-demand per language (Wi-Fi required, ~65MB per voice). Every eSpeak-covered language always has *some* voice; a downloaded natural voice is a per-language upgrade on top of that, picked automatically by `TtsRouter` and falling back to eSpeak transparently if a voice isn't downloaded or synthesis fails for any reason.

## Architecture

Three tabs (Translate / Conversations / Practice) under one `BottomNavigationView`, sharing a single `EspeakEngine`, `PiperTtsEngine`, `VoskEngine`, and `MicPipeline` so speech recognition and audio recording never contend for the microphone. All three tabs speak through one `TtsRouter`, which picks a downloaded natural (Piper) voice when available for the target language and falls back to eSpeak otherwise — callers never need to know which engine actually spoke.

## Requirements

- minSdk 28 (Android 9+)
- arm64-v8a device
- ~3.5GB free storage recommended for downloaded language packs (translation packs ~30MB each, Vosk STT models ~40–140MB each, natural-voice packs ~65MB each)

## Building

```bash
./gradlew assembleDebug
```

## Installing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License notes

- **eSpeak NG** — GPL-3.0. Bundled directly (`jniLibs/libttsespeak.so` + `assets/espeak-ng-data`). Redistributing this app (beyond personal sideloading) carries GPL source-availability obligations for this component.
- **sherpa-onnx** — Apache-2.0 ([k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)). Vendored as `app/libs/sherpa-onnx-classes.jar` (compiled Kotlin/Java API) + `jniLibs/arm64-v8a/lib{onnxruntime,sherpa-onnx-c-api,sherpa-onnx-cxx-api,sherpa-onnx-jni}.so`, extracted from the upstream `sherpa-onnx-1.13.4.aar` release asset (arm64-v8a only, to match this project's single-ABI build). No copyleft obligation beyond attribution.
  - Piper (the upstream training/inference project whose voice format sherpa-onnx supports) relicensed to GPL-3.0 in October 2025. **sherpa-onnx is not affected by this** — it's a separate, clean Apache-2.0 C++/ONNX Runtime implementation that only reads Piper's `.onnx` model *weights* as data; it does not link against or derive from Piper's (GPL) codebase. This app links only sherpa-onnx, never Piper itself, so the GPL-3.0 relicense does not propagate to this app.
- **Piper voice models (downloaded on-demand, not bundled in the APK)** — each voice's license was checked individually against its own `MODEL_CARD` (voice weights are model outputs of the *dataset* license, not automatically Apache/MIT just because the tooling is permissive). Only voices with a clear, permissive license were added to `PiperVoiceCatalog`:

  | Language | Voice | Dataset license |
  |---|---|---|
  | English (en) | `en_US-ljspeech-medium` | Public domain ([LJSpeech](https://keithito.com/LJ-Speech-Dataset/)) |
  | German (de) | `de_DE-thorsten-medium` | CC0 ([Thorsten-Voice](https://github.com/thorstenMueller/Thorsten-Voice)) |
  | Spanish (es) | `es_ES-davefx-medium` | CC0 ([NabuCasa/voice-datasets](https://github.com/NabuCasa/voice-datasets)) |
  | French (fr) | `fr_FR-siwis-medium` | CC-BY 4.0 ([SIWIS](https://datashare.is.ed.ac.uk/handle/10283/2353)) — attribution required if this voice is redistributed |

  A few well-known Piper voices were deliberately **not** included after checking their `MODEL_CARD`: `en_US-lessac-*` (dataset is Blizzard 2013 / Lessac Technologies, gated behind a manually-approved *research-use* license, not obviously safe for general redistribution) and `en_US-hfc_female-medium` (dataset is CC-BY-**NC**-SA — non-commercial only).
