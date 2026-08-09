# Retranslator

A proprietary, fully offline-capable multilingual translator app for Android, originally built for and tested on a Retroid Pocket 2+ handheld (Unisoc T310, Android 9 / API 28, arm64-v8a). No root or OS-level modification required — it installs as a normal sideloaded app.

## Why

Built as an alternative to installing existing translator apps or relying on paid cloud translation APIs. Every capability runs on-device: no API keys, no subscriptions, no billing-gated dependencies, no account required.

## Features

- **Translate** — text translation across ~59 languages (Google ML Kit Translate), with automatic source-language detection (ML Kit Language ID) and per-language-pair offline model downloads.
- **Conversations** — a turn-taking two-way interpreter mode: listen (source language) → translate → speak (target language) → switch turn, with a live transcript and optional per-turn audio recording.
- **Practice** — pronunciation practice: hear a reference pronunciation via offline text-to-speech, record your own attempt, and play both back to compare by ear. (No automated pronunciation scoring — this is a listen-and-compare tool, not a grader.)
- **Offline speech-to-text** — [Vosk](https://alphacephei.com/vosk/) (Apache-2.0), 25 languages available as on-demand-downloaded models, fully offline after download.
- **Offline text-to-speech, two tiers, with a male/female choice on both**:
  - **eSpeak NG** (GPL-3.0), 100+ voices bundled directly in the APK, zero setup or download required, always available. Formant-synthesized — intelligible but robotic. **Every one of those 100+ languages has both a male and a female voice option** (`+m`/`+f` gender properties, no download) — this is the universal floor.
  - **Natural voice (neural TTS)** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) running downloaded [Piper](https://github.com/rhasspy/piper) VITS voice models, on-demand per language *and* gender (Wi-Fi required, ~65MB per voice). Piper's own voice catalog only covers a small subset of languages, and most of those ship only one speaker, not a male/female pair — a natural voice is a per-(language, gender) upgrade on top of the eSpeak floor, picked automatically by `TtsRouter` and falling back to eSpeak transparently if a voice isn't downloaded or synthesis fails for any reason. Currently **all 4 of this app's natural-voice languages (English, German, Spanish, French) have both a natural male and a natural female voice** — see the license table below for exactly which.
- **Gender toggle** — a simple Female/Male switch on the Translate, Conversations, and Practice tabs (one shared preference across all three) picks which gender speaks; it affects both eSpeak and, where downloaded, the natural voice.

## Architecture

Three tabs (Translate / Conversations / Practice) under one `BottomNavigationView`, sharing a single `EspeakEngine`, `PiperTtsEngine`, `VoskEngine`, `TranslationEngine`, and `MicPipeline` so speech recognition and audio recording never contend for the microphone. All three tabs speak through one `TtsRouter`, which takes a (language, gender) pair, picks a downloaded natural (Piper) voice when available and falls back to eSpeak otherwise — callers never need to know which engine actually spoke.

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
- **Piper voice models (downloaded on-demand, not bundled in the APK)** — each voice's license was checked individually against its own `MODEL_CARD` (voice weights are model outputs of the *dataset* license, not automatically Apache/MIT just because the tooling is permissive). Only voices with a clear, permissive license were added to `PiperVoiceCatalog`. All 4 supported languages currently have both a male and a female natural voice:

  | Language | Gender | Voice | Quality | Dataset license |
  |---|---|---|---|---|
  | English (en) | Female | `en_US-ljspeech-medium` | medium | Public domain ([LJSpeech](https://keithito.com/LJ-Speech-Dataset/)) |
  | English (en) | Male | `en_US-joe-medium` | medium | CC0 ([OHF-Voice/voice-datasets](https://github.com/OHF-Voice/voice-datasets)) |
  | German (de) | Male | `de_DE-thorsten-medium` | medium | CC0 ([Thorsten-Voice](https://github.com/thorstenMueller/Thorsten-Voice)) |
  | German (de) | Female | `de_DE-kerstin-low` | low | CC0 ([rhasspy/dataset-voice-kerstin](https://github.com/rhasspy/dataset-voice-kerstin)) |
  | Spanish (es) | Male | `es_ES-davefx-medium` | medium | CC0 ([NabuCasa/voice-datasets](https://github.com/NabuCasa/voice-datasets)) |
  | Spanish (es) | Female | `es_MX-claude-high` | high | Apache-2.0 ([HirCoir/Piper-TTS-Spanish](https://huggingface.co/spaces/HirCoir/Piper-TTS-Spanish)) |
  | French (fr) | Female | `fr_FR-siwis-medium` | medium | CC-BY 4.0 ([SIWIS](https://datashare.is.ed.ac.uk/handle/10283/2353)) — attribution required if this voice is redistributed |
  | French (fr) | Male | `fr_FR-gilles-low` | low | CC0 ([French single-speaker dataset](https://www.kaggle.com/datasets/bryanpark/french-single-speaker-speech-dataset)) — base checkpoint was finetuned from an excluded voice (see note below), but this voice's own dataset license is CC0 |

  A few well-known Piper voices were deliberately **not** included after checking their `MODEL_CARD`: `en_US-lessac-*` (dataset is Blizzard 2013 / Lessac Technologies, gated behind a manually-approved *research-use* license, not obviously safe for general redistribution), `en_US-ryan-medium` (CC-BY-**NC**-SA), and `en_US-hfc_female-medium` (CC-BY-**NC**-SA — non-commercial only). Note that `de_DE-thorsten`, `es_ES-davefx`, and `fr_FR-gilles` were themselves finetuned starting from one of these excluded voices' checkpoints — this project records each voice's *own* dataset license (the one actually being distributed here) rather than treating a base checkpoint's license as inherited, consistent with common ML fine-tuning practice, but it's noted here for transparency.
