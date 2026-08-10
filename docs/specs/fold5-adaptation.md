# Spec: Samsung Galaxy Z Fold 5 native adaptation

Status: **drafted, not yet implemented**. Captures decisions from a brainstorming session on 2026-08-09; no code has been written against this yet.

## Goal

Retranslator was built for and tested on a Retroid Pocket 2+ (Unisoc T310, Android 9, single flat screen, weak hardware). This spec adapts it to run natively on a Galaxy Z Fold 5 (Snapdragon 8 Gen 2, Android 9+ compatible per current `minSdk`, two physical displays: a narrow 23:9 cover screen and a ~6:5 unfolded inner screen with a horizontal or vertical hinge depending on orientation).

"Native adaptation" here means two things together, not either alone:
1. **Hardware upgrade** — the Z Fold 5's Snapdragon 8 Gen 2 gives real headroom over the Unisoc T310 the app was tuned for. Piper/sherpa-onnx neural TTS already runs at RTF 0.35–0.63 on the weaker chip (see `README.md`); expect comfortably faster synthesis and Vosk decoding here, which loosens constraints that shaped the original UI (e.g. tap-to-talk turn-taking to keep one recognizer active at a time).
2. **Fold-aware layout** — not a phone UI stretched to fill a bigger screen. The two physical displays and the hinge are treated as real input to the layout.

## Scope: which screens get what

Not every tab gets a bespoke fold-aware layout — that would be a large surface area for marginal benefit on tabs that are fundamentally single-user, single-column flows.

| Tab | Cover screen | Unfolded inner screen |
|---|---|---|
| **Conversations** | Flagship treatment — full cover-screen-optimized layout (§3) | Flagship treatment — mirrored face-to-face dual-pane (§2) |
| Translate | Responsive scaling only | Responsive scaling only |
| Practice | Responsive scaling only | Responsive scaling only |
| Learn | Responsive scaling only | Responsive scaling only |

**Rationale**: Conversations is the one tab whose entire premise (two people, two languages) maps directly onto the fold's two-audience shape — a physical hinge between two people is close to literally what the feature already models with its `langA`/`langB` split (`ConversationsFragment.kt:81-84`). Translate/Practice/Learn are single-user flows where "fold-aware" would mean nothing more than "bigger" — handled by standard responsive layout (larger touch targets, multi-column where the existing `ConstraintLayout` naturally allows it, no new bespoke XML per tab).

"Responsive scaling" is **not defined further in this spec** — it's ordinary Android large-screen layout work (`sw*dp` qualifiers / `ConstraintLayout` breakpoints), no design decisions pending on it.

**Baseline correctness is not scoped the same way as bespoke layout.** "Responsive scaling only" on Translate/Practice/Learn means those three tabs don't get a custom split/rotation design — it does **not** mean they're exempt from behaving correctly across every physical configuration change (fold, unfold, rotate, cover ↔ main handoff). A recording in progress on Practice, or a downloaded-pack check mid-flight on Translate, must survive the user folding or unfolding the device without crashing or losing state, on all four tabs, regardless of which tabs get bespoke layouts. This is largely already satisfied today — `AndroidManifest.xml`'s existing `configChanges="orientation|screenSize|keyboardHidden"` means the Activity isn't destroyed/recreated on a fold-state change (a fold/unfold is a `screenSize` change, covered), and `MicPipeline`'s capture thread and `AudioRecord` session are already independent of the view hierarchy — but this should be explicitly verified per tab during implementation (§ Hardware fidelity audit), not assumed.

## 1. Current state (baseline being changed)

`ConversationsFragment.kt` today:
- Single flat-screen layout (`fragment_conversations.xml`), no fold or window-size-class awareness anywhere in the app (`AndroidManifest.xml` declares `configChanges="orientation|screenSize|keyboardHidden"` — changes are absorbed, not used to re-layout for a fold state).
- Two `Spinner`s pick `langA`/`langB` once per session.
- Turn-taking is **manual and pre-determined**: a `turnIsA: Boolean` flag alternates automatically after every completed exchange (`switchTurn()`), and the mic button always listens in whichever language `turnIsA` currently implies. There is no per-utterance language detection today — the app already knows which language to expect because it tracks turns itself.
- One shared `TextView` transcript, appended to linearly, both languages interleaved as plain lines (`A (English): ... / → (Spanish): ...`).
- Mic is tap-to-start / tap-to-stop, not continuously listening (`onMicTap()`).

This spec changes the turn model from **app-tracked alternation** to **live language detection per utterance**, and adds a second physical-layout mode. Both are new capabilities, not refinements of existing ones.

## 2. Conversations, unfolded: mirrored face-to-face

### Detection: the full posture matrix, not one binary check

Fold state and hinge geometry come from Jetpack `androidx.window:window` (`WindowInfoTracker` / `FoldingFeature`) — **not currently a project dependency, needs adding**. `FoldingFeature` exposes `state` (`FLAT` | `HALF_OPENED`), `orientation` (`HORIZONTAL` | `VERTICAL`, based on which dimension of the fold rectangle is larger), `bounds` (the hinge's exact `Rect` in window coordinates), `isSeparating` (whether the fold actually splits the window into two logical areas), and `occlusionType` (whether content is physically hidden under the hinge). None of these expose a continuous angle — for that, a separate sensor is needed (see below). Never infer posture from raw screen width/height alone; a wide phone and a flat-laid fold can report similar dimensions.

The Z Fold 5 can present Conversations with five distinct postures, and each needs an explicit, deliberate decision rather than a single "flat vs not" check:

| Posture | `orientation` | `state` | Conversations behavior |
|---|---|---|---|
| Closed (cover screen only) | n/a — separate physical display | n/a | §3's cover-screen layout |
| Book-portrait, fully open (the default "just unfolded" pose) | `VERTICAL` | `FLAT` | Fallback single-column layout (below) — vertical hinge doesn't map to two side-by-side conversational partners |
| Book-portrait, angled (propped like a mini laptop, portrait) | `VERTICAL` | `HALF_OPENED` | Same fallback single-column layout. Samsung's own Flex Mode guidance calls portrait Flex Mode a rare "special case" (usually disabled) — not worth a bespoke third layout here |
| **Tabletop-landscape, laid flat (180°)** | `HORIZONTAL` | `FLAT` | **Mirrored face-to-face** (below) — both halves perfectly coplanar, closest to "shared menu on a table" |
| **Tabletop-landscape, angled (~75–115°, propped like a tent/open book)** | `HORIZONTAL` | `HALF_OPENED` | **Mirrored face-to-face**, same as above — each half still faces a different person even though the halves aren't coplanar; the 180°-rotation trick is orientation-of-content, not angle-dependent, so it applies identically here |

The two `HORIZONTAL` rows share one implementation, not two — trigger the mirrored layout whenever `orientation == HORIZONTAL && isSeparating`, regardless of which `state` it's in.

### Layout

Two panes split at the hinge line (use `FoldingFeature.bounds` to size and position the split precisely — don't assume the hinge sits at exactly 50%, and use `occlusionType` to keep no bubble text or tap targets rendered directly under the physically-occluded crease itself). The pane above the hinge is rendered rotated 180° (`rotation = 180f` on its root view, or an equivalent canvas transform) so each person, seated or leaning in from across the hinge, reads their own half right-side up. Each pane shows: the transcript bubble(s) belonging to that side, in that side's language, largest/most recent nearest the hinge — closest to where that person would naturally look down.

**Transition polish**: `FoldingFeature` alone only reports the two discrete states, which would make the layout snap abruptly as someone opens the device from flat book-portrait into tabletop-landscape. `Sensor.TYPE_HINGE_ANGLE` (API 30+, confirmed present on Z Fold 5 — supported from the Fold3 generation onward) reports continuous hinge-angle degrees and can drive a smooth interpolated transition (e.g. cross-fading into the mirrored split as the angle crosses into the `HORIZONTAL`+range instead of a hard cut). Treat this as a progressive-enhancement layer: check `SensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null` and fall back to snapping directly on the `FoldingFeature` state change if it's absent — don't make the core posture detection depend on a sensor that isn't guaranteed on every foldable.

**Fallback**: book-portrait in either `FLAT` or `HALF_OPENED` state — i.e. anything with a `VERTICAL` hinge, or no separating fold at all — falls back to a single-column layout close to the current phone UI, not the mirrored split. The mirrored layout only makes sense as a physical two-person metaphor, which a vertical hinge doesn't provide.

## 3. Cover screen: live quick-translate

Runs as **the app itself, in a cover-screen-optimized layout** when launched while folded — not an Android App Widget. This was an explicit decision: a widget's interaction model can't sustain live mic capture; a running `Activity`/`Fragment` can, and folding/unfolding while the app is open is a configuration change this app must already handle (see §2's fold-state detection, which also drives cover ↔ inner transitions).

**Layout**: minimal. One large tap-to-talk mic button dominates the narrow (23:9) screen. No language-pair picker on this screen — it inherits whatever pair is currently active from the main (unfolded) session; swiping the mic button left/right flips translation direction. On tap: listen, translate, swap the button's content for the translated-text bubble, auto-fade back to the idle mic button after a few seconds.

**App Continuity requirement**: Samsung's cover ↔ main handoff (open the app on the cover screen, unfold, and it should reappear on the main screen mid-state, not restart) has a concrete manifest prerequisite this project doesn't currently declare: `android:resizableActivity="true"` on `MainActivity` in `AndroidManifest.xml`, plus a layout that fills the full window with no letterboxing on either physical screen. Add the explicit `resizableActivity` declaration rather than relying on its `targetSdk`-24+ default — this is a one-line, low-risk change, worth making early rather than leaving implicit.

## 4. Listening model: single shared mic, auto-detect

Both the mirrored unfolded layout and the cover screen use **one continuously-available mic, no per-side manual "whose turn" button**. When a person speaks, the app must determine which of the two configured languages (`langA`/`langB`) they used and route the translation to the other side — this replaces `ConversationsFragment`'s current `turnIsA` alternation entirely.

- Vosk (`VoskEngine`, via `com.alphacephei:vosk-android`) is **monolingual per loaded model** — a `Recognizer` is built from one language's model and can't identify what language is being spoken before transcribing it. The app's existing spoken-language auto-detect precedent, ML Kit `LanguageIdentification` (used today in `TranslateFragment.kt:210` for *typed text*), operates on already-transcribed text, not raw audio — it can't tell you which Vosk model to run *before* you've transcribed something.
- Because Conversations is always a fixed two-language pair (not an open-set "any of 25 languages" problem), the chosen approach is **running both languages' Vosk recognizers in parallel on the same audio stream** and picking whichever produces the higher-confidence/more-coherent result, rather than true open-ended spoken-language-ID.

**Status: prototyped and measured on-device 2026-08-09 (Galaxy Z Fold 5, serial `RFCW80CK2RW`, Snapdragon 8 Gen 2, Android 16/API 36). Verdict: proceed with dual-recognizer decoding as designed** (caveats below). Prototype code lives at `app/src/main/java/com/retroid/translator/prototype/` (`DualRecognizerPrototype.kt` — core dual-decode/timing/memory/plausibility logic; `TestAudioSynth.kt` — synthetic test-audio generation; `DualRecognizerProtoActivity.kt` — throwaway debug entry point), triggered via `adb shell am start -n com.retroid.translator/.prototype.DualRecognizerProtoActivity`, all output under logcat tag `DualRecoProto`. It is not wired into `ConversationsFragment` or any shipped nav flow, and `VoskEngine`/`MicPipeline`/`ConversationsFragment` were not modified — the prototype gets two simultaneously-resident models "for free" by simply constructing two independent `VoskEngine(context)` instances, each unaware of the other, exactly as this spec originally proposed.

**Test method**: no human speaker, for reproducibility. `TestAudioSynth.kt` drives `com.reecedunn.espeak.SpeechSynthesis` directly (the same JNI class `EspeakEngine` wraps — called directly here rather than modifying `EspeakEngine`, since its `speak()` only plays to an `AudioTrack` and has no capture-to-file path) to synthesize 3 English + 3 Spanish real sentences ("Where is the train station?" / "¿Dónde está la estación de tren?", "I would like a cup of coffee, please." / "Quisiera una taza de café, por favor.", "What time does the museum open?" / "¿A qué hora abre el museo?"), captures the raw PCM, linearly resamples it from eSpeak's native 22050Hz down to 16kHz/16-bit/mono, and writes it with the project's existing `WavFileWriter`. Each clip is fed to both recognizers in chunks sized identically to `MicPipeline`'s own cadence (`AudioRecord.getMinBufferSize(...).coerceAtLeast(4096)`, which resolved to 4096 bytes/chunk on this device).

**Memory** (English + Spanish Vosk small models — `vosk-model-small-en-us-0.15` (~39MB), `vosk-model-small-es-0.42` (~38MB) — both resident simultaneously):

- Native heap allocated (`Debug.getNativeHeapAllocatedSize()`): ~7.5–10MB with no models loaded → ~222MB with both loaded, a **~214MB delta** for two simultaneously-resident recognizer models.
- `adb shell dumpsys meminfo com.retroid.translator` at the same checkpoint: **330,617KB total PSS** for the whole app process (Native Heap PSS 207,462KB of that, Graphics 66,480KB, Java Heap 13,280KB, Code 27,560KB).
- `adb shell cat /proc/meminfo` `MemAvailable` on the device: ~3.95–4.34GB before app launch, ~3.66–3.83GB with the app running and both models loaded. Device total RAM is 11.4GB.
- **This fits comfortably — nowhere near a real limit.** ~215MB of native heap and ~330MB of total process PSS against ~3.7GB+ of available memory is a small fraction (well under 10%) — a completely different picture from the original Retroid Pocket 2+ target's ~1GB usable RAM that justified `VoskEngine`'s single-resident-model design. The comment in `VoskEngine.kt` ("this device has ~1GB usable RAM") is accurate for the T310 and does not apply to the Fold 5; it does not need to change since `VoskEngine` itself is unmodified and still correctly describes its own original target.

**Timing** (2 full runs, 6 clips each = 12 utterance-decodes per condition; "solo" = only the correct-language recognizer running, alone; "dual" = both recognizers running concurrently on separate threads against the identical chunk sequence — matching how a live-mic implementation would actually run them, not a serial simulation):

| Run | avg solo wall time | avg dual wall time | dual/solo ratio |
|---|---|---|---|
| 1 | 445.5ms | 837.7ms | 1.88x |
| 2 | 423.8ms | 834.7ms | 1.97x |

Clip audio duration ranged 1.45–2.58s; every solo *and* dual decode finished well inside the clip's own duration — real-time factor (dual wall time / audio duration) topped out at ~0.60 on the slowest clip (`en_1`). Even running both recognizers concurrently, decoding keeps up with real-time mic input on this chipset with margin to spare. Running both recognizers concurrently costs **~1.9x** a single recognizer's wall time — not the naive 2x one might assume from "twice the work" (some benefit from true parallelism across cores), but also not free: two cores are doing real, non-trivial work per utterance, confirming the spec's original expectation that this "roughly doubles STT CPU load."

**Confidence signal — verified on-device, not assumed.** Vosk's JSON does **not** carry an overall utterance-level confidence field by default. Calling `recognizer.setWords(true)` (done in `DualRecognizerPrototype.runRecognizer`) does add a per-word `"conf"` field to `result()`/`finalResult()`'s `"result"` array, confirmed present in real logcat output for both `vosk-model-small-en-us-0.15` and `vosk-model-small-es-0.42` — e.g., the real raw JSON for the Spanish recognizer decoding "¿Dónde está la estación de tren?":

```
{
  "result" : [
    {"conf": 0.618969, "end": 0.330000, "start": 0.000000, "word": "donde"},
    {"conf": 0.649093, "end": 0.600000, "start": 0.330000, "word": "esta"},
    {"conf": 1.000000, "end": 1.260000, "start": 0.720000, "word": "estación"}
  ],
  "text" : "donde esta estación"
}
```

The prototype's plausibility heuristic (`DualRecognizerPrototype.pickLanguage`) is the mean of these per-word `conf` values, compared between the two recognizers' final results for the same utterance; it falls back to comparing decoded word count only if a JSON has no `conf` field at all (never triggered in testing — the field was always present).

**Accuracy: 12/12 correct** across both runs (6 clips × 2 runs; note this is the same 6 synthesized clips run twice, not 12 independent phrases), using mean word-confidence to pick between the English and Spanish recognizer. `avgWordConf` margins (English vs Spanish), both runs:

| Clip | Run 1 (en vs es) | Run 2 (en vs es) |
|---|---|---|
| en_1 "Where is the train station?" | 0.762 vs 0.237 | 0.771 vs 0.211 |
| en_2 "I would like a cup of coffee, please." | 0.914 vs 0.625 | 0.912 vs 0.668 |
| en_3 "What time does the museum open?" | 0.930 vs 0.510 | 0.951 vs 0.364 |
| es_1 "¿Dónde está la estación de tren?" | 0.736 vs **0.756** | 0.725 vs **0.771** |
| es_2 "Quisiera una taza de café, por favor." | 0.695 vs 0.921 | 0.760 vs 0.929 |
| es_3 "¿A qué hora abre el museo?" | 0.635 vs **0.684** | 0.652 vs **0.704** |

The correct-language recognizer won every time, but margins were not uniform: English clips won by wide margins (0.2–0.7); two of the three Spanish clips (`es_1`, `es_3`) won by narrow margins (0.02–0.05) **in both runs**, consistently rather than by chance — suggesting the small Spanish model's confidence calibration runs closer to the English model's on some phrase shapes.

**Vosk API / implementation surprises hit and resolved**:

- The forced-mismatch decode is not silent garbage, it's a phonetically-plausible false transcription. Forcing the English recognizer to decode the Spanish "Quisiera una taza de café, por favor" clip produced `"keys you in on us on your coffee pot family"` (`avgWordConf` 0.695) — a coherent-looking, if nonsensical, English sentence, not empty output. This is exactly why the word-count fallback heuristic would have been much weaker than the word-confidence signal actually used (both recognizers "sound" like they found something).
- `adb shell am start` of a **non-exported** activity, even in a `debuggable` app, was refused by Android 16 (confirmed on this device: API 36, security patch 2026-06-05) with `PermissionDenial: ... not exported from uid ...`. This used to be a standard debug workflow and no longer works on this OS build regardless of the app's own manifest declarations. `DualRecognizerProtoActivity` had to be declared `exported="true"` (see its manifest comment) purely so `adb shell am start -n ...` could reach it for testing — it remains outside every shipped nav flow and off the launcher, so this has no bearing on the real app's attack surface.
- No crash, ANR, or OOM anywhere across either run (36 total `Recognizer` lifecycles — 2 runs × 6 clips × (1 solo + 2 concurrent) — plus 12 `SpeechSynthesis.synthesize()` calls). One latent risk identified but not actually triggered: `TestAudioSynth` constructs a second, independent `com.reecedunn.espeak.SpeechSynthesis` instance in the same process as `TranslatorApp`'s own lazily-created `EspeakEngine` (which starts initializing in `Application.onCreate` regardless of which Activity is launched) — espeak-ng's native layer was not obviously designed for multiple concurrent instances. It caused no visible crash or corruption in testing, but this is worth being aware of, not a cleared risk, if the prototype is extended further.

**Caveats on the accuracy number** — read 12/12 as "the mechanism works," not "this is production-grade accuracy":

- All ground-truth audio is synthetic eSpeak TTS: clean, single-voice, no background noise, no real accent variation, no room acoustics, no mic self-noise. Real conversational speech is harder on every one of these axes. Two of six phrases already produced narrow (<0.05) confidence margins even under these best-case conditions — real mic audio should be expected to produce *more* narrow/ambiguous cases, and plausibly some outright wrong picks, not zero.
- N=6 unique phrases is a small sample (the second run reuses the same 6 synthesized clips, so this is not 12 independent data points). It rules out "fundamentally broken" but does not establish a reliable production accuracy rate.
- The consistent narrow margins on `es_1`/`es_3` specifically (not random noise — the same two clips, both runs) suggest testing with more/varied Spanish phrases, and eventually real speech, before relying on this signal alone in production.

**Recommendation: proceed with dual-recognizer decoding as designed**, carrying two concrete caveats into implementation:

1. Before wiring this into `ConversationsFragment`, repeat this measurement with real human speech (both languages, multiple speakers) — the synthetic-audio numbers here clear the *mechanism* for feasibility (memory fits, timing keeps up with real-time, the confidence signal exists and is usable) but do not validate real-world accuracy, and the narrow-margin cases found above are exactly where real acoustic noise would matter most.
2. Keep the spec's existing tap-to-fix reassign affordance (see "Fallback UX for a wrong guess" below) as the safety net it was already designed to be — it now does double duty, recovering from both "wrong recognizer picked" and whatever accuracy gap real-speech testing turns up. There is no need to build the named fallback (explicit language-pair confirmation per utterance) — the measured memory and timing headroom on the Snapdragon 8 Gen 2 make dual-recognizer decoding comfortably viable, which is exactly what this spike existed to determine.

**Fallback UX for a wrong guess**: no dedicated override button (deliberately, to keep the shared-mic model uninterrupted). Instead, every translated bubble carries a small reassign affordance — tapping it flips which side the utterance is attributed to and re-renders it mirrored to the other pane. This applies identically to the single-bubble result on the cover screen. Commit to the best guess immediately (no confidence-gating delay); correction happens after the fact, on the result itself, not through new persistent UI.

## 5. Hardware fidelity audit — every other Z Fold 5 facet considered

A pass through what else this device offers, so "highest fidelity" isn't limited to just the two screens and the hinge. Each item below is one of three things: a concrete action item, a real capability the app already gets for free with no code changes, or a deliberate exclusion with a stated reason — nothing is left unconsidered.

- **120Hz adaptive refresh display** — action item, small. The OS drives standard `View` animations and transitions at the display's native refresh rate automatically; nothing here needs new code. Verify during implementation that nothing in the new mirrored-layout transitions (bubble fade-in, the rotation snap/interpolation from § Transition polish) accidentally fights this with a fixed low-frequency `postDelayed` loop instead of proper `ValueAnimator`/`Choreographer`-driven animation.
- **Stereo speakers (AKG-tuned, Dolby Atmos)** — free, no action. TTS playback already goes through standard `MediaPlayer`/`AudioTrack` (`EspeakEngine`, `PiperTtsEngine`), which the OS's audio pipeline enhances transparently. No spatial-audio-aware code is needed or beneficial here — a two-person face-to-face conversation isn't a stereo-imaging use case (both people are close to the device, not positioned for a stereo soundstage).
- **Snapdragon 8 Gen 2 / RAM** — covered in the Goal section and validated concretely in §4's prototype (memory and timing headroom for dual-recognizer decoding).
- **App Continuity (`resizableActivity`)** — action item, covered in §3.
- **Rear camera array (50MP wide + 12MP ultra-wide + 10MP telephoto), cover camera, under-display camera** — **not scoped, flagged as a candidate feature, not decided here.** A camera-based "point at a sign/menu, see translated text overlaid live" mode (OCR + live translation, akin to Google Translate's camera mode) would be a genuine, substantial use of this device's camera hardware for a translation app — but it's a new feature with its own pipeline (on-device OCR, live overlay rendering, a new permission), not a fold-aware adaptation of something that already exists in this app. Deliberately not designed or built here without an explicit decision from whoever's driving this spec next.
- **S Pen (Fold Edition case only)** — excluded. Not built into the Z Fold 5 itself (requires a separate compatible case/stylus), and there's no clear translation-app interaction this adds over touch. Out of scope.
- **Samsung DeX / external display output** — excluded. DeX targets a desktop-monitor workflow; this app's use cases (cover-screen quick translate, tabletop face-to-face conversation) are inherently mobile/tabletop, not desk-bound. No compelling DeX-specific interaction identified.
- **5G / Wi-Fi 6E connectivity** — explicitly not a target for new work. This app's stated design principle (`README.md`) is offline-first with zero ongoing network dependency beyond one-time pack downloads; better connectivity doesn't change that architecture and shouldn't be used to justify pulling any feature toward requiring a network connection it doesn't already need.
- **Biometrics (under-display fingerprint, face unlock)** — excluded, not applicable to a translation app's scope.

## Out of scope for this spec

- Bespoke fold-aware layouts for Translate, Practice, or Learn (responsive scaling only, see Scope table).
- Any Android App Widget implementation of the cover-screen experience.
- A manual per-turn override control in Conversations (explicitly rejected in favor of pure auto-detect + tap-to-fix).
- Validating dual-recognizer accuracy against real human speech — the §4 prototype spike is done and the memory/timing/mechanism question it existed to answer is resolved (proceed), but only synthetic eSpeak-TTS audio has been tested so far; real-speech validation is called out in §4 as a prerequisite before wiring this into `ConversationsFragment`, not as a still-open design question.

## Suggested implementation order

1. Add `androidx.window:window` dependency; build fold-state detection as a shared utility covering the **full posture matrix** from §2 (not just a flat/not-flat check) — `FoldingFeature.state`/`orientation`/`bounds`/`isSeparating`/`occlusionType`, plus an optional `Sensor.TYPE_HINGE_ANGLE` listener for smooth transitions with a graceful fallback when the sensor is absent. Both §2 and §3 depend on this. Add `android:resizableActivity="true"` to `MainActivity` in the same pass (§3's App Continuity requirement) — trivial, no reason to defer it.
2. ~~Prototype dual-recognizer parallel Vosk decoding in isolation (not wired into UI) to de-risk §4 before any layout work depends on it.~~ **Done** (2026-08-09) — see §4. Verdict: proceed. Remaining prerequisite before step 3 can use auto-detect for real: validate the same prototype against real human speech, not just synthetic eSpeak-TTS audio.
3. Build the mirrored unfolded layout (§2) against the existing manual-turn model first (de-risk the rotation/split layout independent of the harder auto-detect problem), then swap in auto-detect once §4's prototype is validated against real speech. Use `FoldingFeature.bounds`/`occlusionType` from step 1 to size the split and keep content off the crease from the start, rather than retrofitting it later.
4. Build the cover-screen layout (§3) last — it's the smallest surface area and reuses whatever auto-detect + tap-to-fix mechanism §2/§4 land on.
5. Verify baseline fold-state correctness (Scope section) on Translate/Practice/Learn — fold/unfold mid-recording, mid-download — as an explicit pass, not an assumption.
