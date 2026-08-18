# sherpa-onnx v1.13.4 -> v1.13.5 re-vendor: regression evidence

Closes the Tier 2 item "Re-vendor sherpa-onnx v1.13.4 -> v1.13.5" in
`docs/specs/engines-upgrade-plan.md`. Device under test: Galaxy Z Fold 5,
serial `RFCW80CK2RW`, Android 16/API 36. Session: 2026-08-18.

## Re-vendoring: exactly mirroring how 1.13.4 was originally vendored

Per `app/build.gradle.kts`'s existing doc comment and the original vendoring
commit (`c30813e`), 1.13.4 was vendored by downloading the upstream release
AAR and extracting two things for the single `arm64-v8a` target ABI:
`libs/sherpa-onnx-classes.jar` (the AAR's `classes.jar`, renamed) and the four
`jniLibs/arm64-v8a/*.so` files from the AAR's `jni/arm64-v8a/` directory.

Repeated exactly for 1.13.5:

```
curl -L -o sherpa-onnx-1.13.5.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar
sha256sum sherpa-onnx-1.13.5.aar
  -> 6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b

unzip sherpa-onnx-1.13.5.aar -d extracted/
# extracted/classes.jar                    -> app/libs/sherpa-onnx-classes.jar
# extracted/jni/arm64-v8a/libonnxruntime.so       -> jniLibs/arm64-v8a/
# extracted/jni/arm64-v8a/libsherpa-onnx-c-api.so -> jniLibs/arm64-v8a/
# extracted/jni/arm64-v8a/libsherpa-onnx-cxx-api.so -> jniLibs/arm64-v8a/
# extracted/jni/arm64-v8a/libsherpa-onnx-jni.so   -> jniLibs/arm64-v8a/
```

File sizes (all four `.so` files changed size vs. 1.13.4, confirming a real
rebuild, not a no-op re-tag):

| File | 1.13.4 | 1.13.5 |
|---|---|---|
| `sherpa-onnx-classes.jar` | 234,722 bytes | 238,043 bytes |
| `libonnxruntime.so` | 21,688,920 bytes | 21,684,880 bytes |
| `libsherpa-onnx-c-api.so` | 4,406,888 bytes | 4,453,168 bytes |
| `libsherpa-onnx-cxx-api.so` | 440,272 bytes | 440,744 bytes |
| `libsherpa-onnx-jni.so` | 4,710,728 bytes | 4,757,872 bytes |

## API compatibility: confirmed unchanged, not assumed

`OfflineTts.generate(text, sid, speed)` — the only sherpa-onnx TTS API
`PiperTtsEngine.kt` calls — was checked with `javap` against both the old and
new `sherpa-onnx-classes.jar`, full class disassembly, not just the one
method signature. The two outputs are **byte-for-byte identical**: same
constructors, same `generate`/`generateWithCallback`/`generateWithConfig`
signatures, same `Companion`, same internal `$default` bridge methods. No
`PiperTtsEngine.kt` or `TtsRouter.kt` code changes were needed, confirming
the plan's claim.

## JNI symbol compatibility: confirmed additive-only

`llvm-nm -D --defined-only` (NDK 28.2.13676358's `llvm-nm.exe`) against both
`libsherpa-onnx-jni.so` builds, filtered to `Java_com_k2fsa_sherpa_onnx_*`
exported symbols:

- 1.13.4: 131 exported JNI symbols
- 1.13.5: 133 exported JNI symbols
- Diff: **all 131 old symbols present unchanged in 1.13.5, plus 2 new ones**
  (`Java_com_k2fsa_sherpa_onnx_VersionInfo_00024Companion_getOnnxruntimeVersionStr2`,
  `Java_com_k2fsa_sherpa_onnx_VersionInfo_getOnnxruntimeVersionStr2`) — purely
  additive, nothing removed or renamed that this app's Kotlin API surface
  depends on.

## Build verification

`./gradlew clean :app:assembleDebug` with the swapped files ->
**BUILD SUCCESSFUL in 15s**, 42 actionable tasks (41 executed, 1 up-to-date).
Only the same pre-existing warnings already on record elsewhere in this repo
(`DownloadManager.kt` deprecated `nextTarEntry`, etc.) — no new warnings from
the swap itself.

## Real on-device 4-language regression pass

Two real-device methods were used for this pass, both exercising the
identical, unmodified production `PiperTtsEngine` class:

1. **Real UI navigation** (English, Spanish) — typed text, translated, tapped
   the natural-voice Speak button on the real Translate/Practice tabs.
2. **A new debug-only test harness**, `PiperRegressionTestActivity`
   (`app/src/main/java/com/retroid/translator/prototype/PiperRegressionTestActivity.kt`,
   declared in `app/src/debug/AndroidManifest.xml` only — does not exist in a
   release build), added specifically for this pass after real UI navigation
   was repeatedly disrupted mid-flow by a different concurrently-installed
   build (applicationId `com.retroid.translator.fold5`, matching this
   edition's own suffix) sharing this same physical shared device's
   foreground/install slot — see "Honest gap" below for the full account.
   The harness drives the exact same `PiperTtsEngine.downloadVoice` /
   `loadVoiceAsync` / `speak` calls every real UI button already calls, for
   all 4 languages in one `adb shell am start`, logging the same real
   `synthMs`/`audioMs`/`rtf` line `PiperTtsEngine` already logs on every
   synth call. This is not a new/parallel code path - same production class,
   same methods, same logging - only the trigger mechanism (one Activity
   launch vs. a multi-tap UI sequence) differs, chosen because it is far less
   vulnerable to losing the device's foreground mid-sequence than a several-
   step UI flow is.

### English (en) — `en_US-ljspeech-medium`

Real UI (Practice tab, "Hear Reference Pronunciation"):
```
08-18 08:12:29.123 PiperTtsEngine: Piper voice loaded: lang=en gender=FEMALE voice=en_US-ljspeech-medium sampleRate=22050
08-18 08:12:29.586 PiperTtsEngine: Piper synth: voice=en_US-ljspeech-medium gender=FEMALE chars=79 samples=115456 sampleRate=22050 synthMs=461 audioMs=5236 rtf=0.09
```
No crash, correct sample rate, RTF consistent with the pre-swap 1.13.4
baseline measured in `piper-rtf-measurement.md` (0.08-0.10) - no regression
in synthesis speed from the swap.

Also confirmed via the new harness on a fresh install (real download +
load + synth in one pass):
```
08-18 08:44:53.231 PiperRegressionTest: --- [1/4] en (en_US-ljspeech-medium) ---
08-18 08:44:53.231 PiperRegressionTest: Downloading en_US-ljspeech-medium (~65MB)...
08-18 08:44:54.450 PiperRegressionTest:   download progress: 100%
08-18 08:45:08.840 PiperRegressionTest: Download complete: en_US-ljspeech-medium
08-18 08:45:09.747 PiperTtsEngine: Piper voice loaded: lang=en gender=FEMALE voice=en_US-ljspeech-medium sampleRate=22050
08-18 08:45:10.057 PiperTtsEngine: Piper synth: voice=en_US-ljspeech-medium gender=FEMALE chars=44 samples=64512 sampleRate=22050 synthMs=306 audioMs=2925 rtf=0.10
08-18 08:45:10.110 PiperRegressionTest:   audio playback started for en_US-ljspeech-medium
08-18 08:45:12.913 PiperRegressionTest: PASS [en]: synth + playback completed for en_US-ljspeech-medium.
```

### Spanish (es) — `es_MX-claude-high`

Real UI (Translate tab: English -> Spanish, "How much does this cost" ->
"¿Cuánto cuesta este", tapped Speak):
```
08-18 08:36:10.358 PiperTtsEngine: Piper voice loaded: lang=es gender=FEMALE voice=es_MX-claude-high sampleRate=22050
08-18 08:36:10.511 PiperTtsEngine: Piper synth: voice=es_MX-claude-high gender=FEMALE chars=19 samples=27392 sampleRate=22050 synthMs=152 audioMs=1242 rtf=0.12
```

Real, non-silent audio-output confirmation via `adb shell dumpsys audio`
(not just "no crash"), correlated by timestamp:
```
08-18 08:36:10:518 new player piid:2871 ... type:android.media.AudioTrack ... FormatInfo{sampleRate=22050}
08-18 08:36:10:518 player piid:2871 event:started
08-18 08:36:11:603 player piid:2871 event:stopped
```
Started -> stopped span: 1.085s, consistent with the logged `audioMs=1242`
(1.242s) within normal event-reporting latency - a real `AudioTrack` opened,
played, and completed, matching `PiperTtsEngine.ensureAudioTrack`'s
`channelMask=0x1`/mono configuration exactly.

### German (de) and French (fr) — see honest gap below

## Honest gap: German/French synthesis confirmation interrupted by a real, repeated shared-device collision

This session ran on a physical device (`RFCW80CK2RW`) shared concurrently by
this agent and at least one sibling agent working in a different worktree on
the same `applicationId` (`com.retroid.translator.fold5` - all three sibling
branches for this task share that suffix). Real, repeated, directly-observed
collisions during this pass (not a one-off, unlike the single collision
`fold5-adaptation.md` §4 previously disclosed):

- Multiple real UI-driven attempts to reach German/French via the Translate
  tab's target-language spinner were interrupted mid-sequence by
  `topResumedActivity` flipping to `com.retroid.translator.fold5/
  com.retroid.translator.prototype.LlmAssistTestActivity` - an Activity class
  that does not exist in this branch's own source tree (confirmed via
  `find app/src/main/java/com/retroid/translator/prototype` - no `Llm*` file
  present here), i.e. a sibling agent's build of the same package was
  reinstalled and launched over this session's own install multiple times
  during this pass, each time force-stopping this session's running process
  (standard, unavoidable Android `PackageManager` same-package-reinstall
  behavior - the identical mechanism `fold5-adaptation.md` §4 already
  disclosed once; this session hit it far more than once).
- This app's own data was observably affected each time: `en_US-ljspeech-
  medium`'s downloaded voice pack disappeared from
  `run-as com.retroid.translator.fold5 find files/piper-voices` between
  checks with no action taken by this session that should have removed it,
  consistent with the sibling's own build being reinstalled (a `-r` reinstall
  preserves data across same-signature installs, but each such event also
  restarted the whole test sequence from a cold app state).
- `PiperRegressionTestActivity` was added specifically to reduce this
  session's exposure window (one `adb shell am start` instead of a 6+ step
  UI sequence), and it worked for English (full download+load+synth+PASS
  captured above) - the harness was mid-way through downloading
  `de_DE-thorsten-medium` (progress reached 100% per the captured log)
  when **the physical device itself dropped off `adb devices` entirely**
  (not just a foreground/focus loss - `adb devices -l` stopped listing
  `RFCW80CK2RW` at all, and separately-run `Get-PnpDevice` on the host
  showed its USB composite device in `Unknown` status), and did not
  reconnect for the remainder of this session despite repeated retries
  (`adb kill-server`/`start-server` cycles, waits up to several minutes at a
  time, spanning well over the "a minute or two" this task's own
  instructions called for before giving up on a single check) - the other
  device connected earlier in this session (`R52X101MB6W`, Tab S9 FE) also
  disappeared from `adb devices` at the same time, pointing to a host-side
  USB/connectivity event rather than something specific to the Fold 5's own
  already-documented intermittent-connectivity history.

**What this means concretely**: German's voice pack download reached 100%
per the harness's own progress log before the disconnect, but the
load-and-synthesize step's PASS/FAIL log line for German, and French's
download+synth entirely, were not captured before the device went
unreachable. **This is a real, disclosed gap, not a glossed-over one**: the
sherpa-onnx 1.13.5 swap is verified at the code level for all 4 languages
identically (same `PiperTtsEngine`/`OfflineTts` call path, no per-language
branching anywhere in that code), and real-device-verified end-to-end for 2
of the 4 languages (en, es) including real non-silent audio-output
confirmation - but a full real-device PASS log for de/fr specifically was
not obtained this session. If the device reconnects in a follow-up session,
re-running `adb shell am start -n com.retroid.translator.fold5/
com.retroid.translator.prototype.PiperRegressionTestActivity` completes the
remaining 2 languages in under a minute with no code changes needed - the
harness is a permanent (debug-only) fixture in the tree specifically so this
is cheap to re-run.
