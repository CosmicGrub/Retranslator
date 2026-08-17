# Engine inventory: scope and capacity of every built-in engine

Not a spec for new work — a factual audit of what's already built, compiled 2026-08-16 by reading every engine class, catalog file, build script, and cross-checking every capacity/accuracy claim against the real on-device evidence already recorded in `fold5-adaptation.md`, `galaxy-tab-s9fe-adaptation.md`, and `watch6-classic-adaptation.md`. Where a number in this doc conflicts with something stated elsewhere, this doc is the one that re-verified it against source and/or git history — see §4.2's RTF correction for a concrete example of a previously-unquestioned figure that didn't hold up.

Five distinct engines/subsystems, one shared pack-download system, one Wear OS companion with its own narrower engine set.

## 1. Speech-to-text — Vosk

| | |
|---|---|
| Library | `com.alphacephei:vosk-android:0.3.75` + `net.java.dev.jna:jna:5.18.1@aar` |
| License | Apache-2.0 |
| Wrapper | `app/src/main/java/com/retroid/translator/engine/VoskEngine.kt` |
| Catalog | `app/src/main/java/com/retroid/translator/engine/VoskModelCatalog.kt` |

**Design**: one model resident at a time, by explicit design (`VoskEngine`'s own doc comment: *"this device has ~1GB usable RAM"* — written against the original Retroid Pocket 2+ target, left unchanged since it still correctly describes that device, not a bug). Switching source language unloads the previous model before loading the new one.

**Concurrency**: `VoskEngine` itself has no multi-instance awareness. The dual-recognizer auto-detect feature (Conversations' continuous-listening mode) works around this by simply constructing **two independent `VoskEngine` objects** — each unaware of the other — rather than making the engine itself concurrency-aware. Each gets its own `Recognizer` per utterance, decoding the identical audio stream concurrently on two separate threads.

**Completeness validation**: `effectiveModelPath()` checks for a `conf` or `am` subfolder (Vosk zips extract one level nested) before treating a model as present — a real download-integrity check, not just "does the directory exist."

### Catalog: 25 languages

Hand-curated from `alphacephei.com/vosk/models/model-list.json`, keeping only small (~30–290MB) non-obsolete models, intersected against ML Kit's language set at runtime so only languages both engines support ever appear selectable.

| Code | Language | Size (MiB) | Code | Language | Size (MiB) |
|---|---|---|---|---|---|
| en | English | 39 | tr | Turkish | 35 |
| es | Spanish | 38 | pl | Polish | 51 |
| fr | French | 40 | cs | Czech | 44 |
| de | German | 44 | ca | Catalan | 41 |
| it | Italian | 47 | fa | Persian | 51 |
| pt | Portuguese | 31 | uk | Ukrainian | 137 |
| ru | Russian | 44 | vi | Vietnamese | 32 |
| nl | Dutch | 39 | ar | Arabic | 100 |
| zh | Chinese | 42 | eo | Esperanto | 42 |
| ja | Japanese | 47 | gu | Gujarati | 103 |
| ko | Korean | 83 | te | Telugu | 58 |
| hi | Hindi | 42 | sv | Swedish | 289 |
| kk | Kazakh | 57 | | | |

### Real measurements (Galaxy Z Fold 5, `RFCW80CK2RW`, English+Spanish loaded simultaneously)

- Native heap: ~7.5–10MB idle → **~222MB with both models loaded** (~214MB delta).
- Total process PSS: **330,617KB** (`dumpsys meminfo`).
- Decode timing, two concurrent recognizers vs. one: **~1.9×** wall-time cost (not the naive 2×) — RTF (dual wall time / audio duration) topped out at **~0.60** on the slowest clip, comfortably real-time.
- Accuracy: **12/12** on synthetic eSpeak-TTS clips; **3/6 on real human speech** (OpenSLR SLR83/SLR72 corpus) — measured on the **Retroid Pocket 2+ substitute**, never re-verified on the actual Fold 5 target. Two of six real clips produced completely empty transcription from both recognizers; one had the *wrong*-language recognizer confidently hallucinate a full sentence while the correct one returned nothing.
- **Only English and Spanish have any real accuracy measurement anywhere in this codebase.** The other 22 of 25 catalog languages are cataloged (size/URL) but never decode-tested.

### Wear module

Curated **12-language subset** (`wear/src/main/java/com/retroid/translator/wear/engine/WearLanguages.kt`), not the full 25 — chosen for travel/global-usage relevance. Real target device (Galaxy Watch6 Classic, SM-R965U) reports `ro.product.cpu.abilist=armeabi-v7a,armeabi` — **no 64-bit ABI**, despite the Exynos W930 chip being 64-bit-capable (Samsung's Wear userspace is 32-bit-only). Native load confirmed working via a dedicated `VoskEngine.probeNativeLoad()` diagnostic (constructs a `Model` against a guaranteed-missing path, so it can be tested without a real download): `libvosk.so`/`libjnidispatch.so` loaded and threw a clean, catchable exception rather than crashing, across three separate install/launch cycles. **Real decode accuracy on the watch was never verified** — no Vosk model was ever actually downloaded/loaded in any pass (blocked on an explicit download-permission gate).

## 2. Translation — ML Kit Translate

| | |
|---|---|
| Library | `com.google.mlkit:translate:17.0.3` |
| Wrapper | `app/src/main/java/com/retroid/translator/engine/TranslationEngine.kt` |
| Catalog | `app/src/main/java/com/retroid/translator/engine/LanguageCatalog.kt` |

Thin stateless wrapper — `LanguageCatalog.codes` doesn't hardcode a list, it calls `TranslateLanguage.getAllLanguages()` live and sorts by display name. **59 languages** currently, confirmed both by catalog count and by a real on-device "X of 92 packs" summary-card reading (`galaxy-tab-s9fe-adaptation.md`). ~30MB per pack (measured on-device, per README), ~1.77GB for the full set.

**No completeness validation** — a real, documented asymmetry against the other two download-based engines here. Vosk and Piper both verify a downloaded pack is actually intact on disk before trusting it; `TranslationEngine.isModelDownloaded()` just relays whatever `RemoteModelManager.getDownloadedModels()` reports, because ML Kit exposes no independent integrity check for translate packs. The "Check for updates" feature in Manage Packs can only re-query ML Kit's own snapshot for this engine specifically, unlike its real disk-completeness re-check for Vosk/Piper.

`translate:17.0.2`'s bundled `libtranslate_jni.so` failed Android's 16KB page-size alignment check (confirmed via direct ELF inspection: `PT_LOAD p_align=0x1000`, 4KB-only); bumped to **17.0.3** (2026-08-16, this session) after confirming the newer version is 16KB-aligned. No accuracy/quality measurement of translation output exists anywhere in this codebase — every accuracy figure in the spec docs is about Vosk STT, not translation quality.

## 3. Language detection — ML Kit Language-ID

| | |
|---|---|
| Library | `com.google.mlkit:language-id:17.0.5` |
| Used in | `TranslateFragment.kt` only — not present in `:wear` at all |

**What it's for**: detecting the language of already-typed or already-transcribed **text** (Translate tab's auto-detect toggle) — `identifyLanguage(text)`, falling back to manual selection on the `"und"` sentinel.

**What it's explicitly not for, and why this distinction matters**: spoken-language auto-detect in Conversations. `LanguageIdentification` operates on text, not raw audio, so it fundamentally cannot decide *which Vosk model to run* before something has already been transcribed. Conversations solves that different problem with §1's dual-Vosk-recognizer race instead — the spec docs are explicit that this is "the app's existing spoken-language auto-detect precedent" being cited only to explain why it *doesn't* apply to the audio case, not a description of what actually powers Conversations.

No accuracy measurement of language-ID exists in this codebase either.

## 4. Text-to-speech — two tiers plus a router

### 4.1 eSpeak-NG — always-available floor

| | |
|---|---|
| Wrapper | `app/src/main/java/com/retroid/translator/engine/EspeakEngine.kt` |
| JNI bridge | `app/src/main/java/com/reecedunn/espeak/*.java` (vendored, Apache-2.0, from the official espeak-ng Android bindings, trimmed of unused helpers) |
| Native lib | `libttsespeak.so` (arm64-v8a only) |
| Data | `app/src/main/assets/espeak-ng-data/` — 18MB on disk, 141 lang/locale-variant files |
| License | **GPL-3.0** — redistributing this app beyond personal sideloading carries source-availability obligations for this component specifically |

Talks to `libttsespeak.so` directly via JNI — does **not** go through Android's `TextToSpeech` framework. Streams raw PCM into an `AudioTrack` itself via a synth callback. ~114 languages per the code's own comments (README claims "100+ voices, every one with both a male and female option"); one-time unpack from APK assets to app-private storage on first run, since native code can't read out of the compressed APK directly. No per-voice download needed — gender is picked dynamically per-language rather than hardcoding a numbered variant that might not exist for all 114.

Robotic but unconditionally available — this is the fallback floor, not the quality tier. Also fails the 16KB page-size alignment check (`libttsespeak.so`, "LOAD segment not aligned") with **no upstream fix available** as of this audit — unlike ML Kit translate, there's no newer vendored version to bump to; closing this would require an in-repo NDK cross-compile of espeak-ng, not attempted.

### 4.2 sherpa-onnx + Piper VITS — natural voice, opt-in

| | |
|---|---|
| Wrapper | `app/src/main/java/com/retroid/translator/engine/PiperTtsEngine.kt` |
| Runtime | sherpa-onnx **v1.13.4** (k2-fsa), vendored — no Maven Central artifact exists, hand-extracted from the GitHub release AAR (`libs/sherpa-onnx-classes.jar` + `jniLibs/arm64-v8a/lib{onnxruntime,sherpa-onnx-*}.so`) |
| License | Apache-2.0 (sherpa-onnx; unaffected by Piper's Oct-2025 GPL-3.0 relicense since sherpa-onnx only reads Piper's `.onnx` weights as data) |
| Catalog | `app/src/main/java/com/retroid/translator/engine/PiperVoiceCatalog.kt` |

One voice resident at a time (~60MB ONNX weights per voice; same "~1GB usable RAM" reasoning as Vosk).

**Catalog: 8 voices**, ~65MB each, ~520MB total:

| Lang | Female | Male |
|---|---|---|
| English | `en_US-ljspeech-medium` (public domain) | `en_US-joe-medium` (CC0) |
| German | `de_DE-kerstin-low` (CC0) | `de_DE-thorsten-medium` (CC0) |
| Spanish | `es_MX-claude-high` (Apache-2.0) | `es_ES-davefx-medium` (CC0) |
| French | `fr_FR-siwis-medium` (CC-BY 4.0, attribution required) | `fr_FR-gilles-low` (CC0) |

Only these 4 languages have both a male and female option — not a general guarantee across a larger catalog. Several other Piper voices exist upstream but are deliberately excluded here for licensing reasons (`en_US-lessac-*` research-only; `en_US-ryan-medium`/`en_US-hfc_female-medium` CC-BY-NC-SA).

**Real completeness validation** (`effectiveVoiceDir()`/`isCompleteVoiceDir()`): checks the `.onnx` file is ≥10MB (catches truncated downloads), `tokens.txt` is non-empty, and all four of `phontab`/`phonindex`/`phondata`/`intonations` exist under `espeak-ng-data/` (Piper's VITS loader needs espeak-ng's phonemizer data too). This exists because of a real bug: an interrupted download once left the small early files present but not the larger `espeak-ng-data`, and loading that directory segfaulted natively — unrecoverable from Kotlin. `downloadVoice()` re-checks completeness after every download and deletes-and-fails rather than leaving a half-extracted pack.

**Correction to a previously-unquestioned figure**: `fold5-adaptation.md`'s Goal section states Piper "runs at RTF 0.35–0.63 on the weaker chip (see README.md)." This was re-checked against source directly and **does not hold up** — that figure appears nowhere in README.md's current content or its full git history, and the commit that introduced Piper explicitly states the on-device RTF measurement was in progress when the test device disconnected and was never completed. The code does log a real per-utterance RTF (`PiperTtsEngine.kt`: `synthMs=... audioMs=... rtf=%.2f`), but no evidence file in this repo contains an actual captured value. **Treat "RTF 0.35–0.63" as an unverified claim, not a measurement**, until someone actually captures and records one.

### 4.3 TtsRouter — selection logic

`app/src/main/java/com/retroid/translator/engine/TtsRouter.kt`: Piper is used only if a catalog entry exists for the requested language+gender **and** it's fully downloaded (passes the completeness check above); any load or synthesis failure at any point falls back to eSpeak transparently. Piper is strictly additive — never a hard requirement for speech output to work.

### 4.4 Wear module — system TextToSpeech, not a port

`wear/src/main/java/com/retroid/translator/wear/tts/SystemTtsSpeaker.kt` wraps Android's framework `TextToSpeech` (real device bound to Samsung's own engine, `com.samsung.SMT`) instead of eSpeak/Piper. Explicitly documented as not a port: both TTS engines are vendored as arm64-v8a-only prebuilt binaries with no in-repo build recipe, and the real Watch6 Classic's 32-bit-only ABI means those exact binaries could never load there regardless. Building 32-bit versions from source is scoped as real follow-up work, not attempted. **Never exercised with real text on-device** as of the last pass touching it (blocked on a downloaded translation model).

## 5. OCR — ML Kit Text Recognition v2

| | |
|---|---|
| Wrapper | `app/src/main/java/com/retroid/translator/ocr/OcrEngine.kt` |
| Bundled | `com.google.mlkit:text-recognition:16.0.1` (Latin script) |
| On-demand | `com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1` + `play-services-base:18.5.0` |
| Camera | CameraX `camera-camera2`/`camera-lifecycle`/`camera-view` **1.3.4** |

Single-shot capture, not live/continuous OCR — matches the app's existing "one explicit action, one result" pattern rather than adding an AR-style live overlay. CameraX pinned to 1.3.4 rather than the actual latest 1.5.1 because that needs compileSdk 35 + AGP 8.6+, confirmed by a real failed build attempt against this project's compileSdk 34/AGP 8.3.2 — not assumed.

- **Latin script**: bundled in-APK (~4MB), no download step at all, covers the large majority of this app's supported languages.
- **Chinese**: on-demand via `ModuleInstallClient` — a genuinely different download mechanism from `RemoteModelManager` (an earlier assumption that it'd be the same API was wrong and corrected in the spec).
- **Japanese, Korean, Devanagari deliberately not added**: mechanically trivial (identical `ModuleInstallClient` pattern to Chinese), but no real text sample was available to verify end-to-end on-device, and this project's stated house style doesn't ship an unverified code path just because it would compile.

**Verification honesty**: real camera preview streaming and permission grant/deny handling were verified live on the Fold 5 (real CameraX/Camera2 bind sequence in logcat; `screencap` itself can't capture the live preview on this device — a systemic limitation reproduced in the stock Camera app too, not app-specific). Actual OCR recognition + translation correctness was verified only through a debug harness (`OcrTestActivity`) that renders real text to an on-device bitmap via Canvas — both Latin and Chinese read back exact matches and translated correctly. **No agent has ever photographed real printed/displayed text with this feature** — disclosed explicitly in the spec rather than glossed over, the same honest gap as "no live human speaker" for STT.

Only wired into one of Translate's 8 layout variants (`ActiveLayout.DEFAULT`) — a deliberate scope decision, not an oversight; `CameraCaptureActivity` itself is layout-agnostic regardless of how many entry buttons exist.

## Combined footprint

92 total downloadable packs (59 translation + 25 Vosk + 8 Piper) ≈ **3.7–3.8GB**, confirmed by a real on-device summary card reading ("9 of 92 packs downloaded... 83 remaining (~3464MB, Wi-Fi)"), not just arithmetic. eSpeak's 18MB and the two OCR script packs (Latin bundled, Chinese ~microscopic on-demand) aren't part of this count — they're outside the unified pack-management system entirely.

## Wear module summary

| Engine | Phone | Wear | Why different |
|---|---|---|---|
| STT | Vosk, 25 languages, arm64-v8a | Vosk, 12-language subset, armeabi-v7a | Real device is 32-bit-only; AAR already bundles armeabi-v7a natives, so no extra work needed |
| Translation | ML Kit, 59 languages | ML Kit, 12-language subset | Curated for travel relevance, not a technical limit |
| Language-ID | ML Kit | **absent** | No auto-detect UI on the watch (fixed source/target chip-cycling instead) |
| TTS | eSpeak (floor) + Piper (natural) | Android system TTS | eSpeak/Piper are arm64-v8a-only prebuilt binaries; no build recipe for 32-bit, and the real device can't load arm64-v8a anyway |
| OCR | ML Kit Text Recognition | **absent** | Not built for the watch |
