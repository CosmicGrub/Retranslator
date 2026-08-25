# Engineering systems pitch: closing the gaps this app's own goals expose

RetroidTranslator's stated goals — fully offline, cost-free, privacy-respecting, real device-adaptive UX, genuine language-learning value, and an honest, disclosed engineering culture (see `engines-inventory.md`/`engines-upgrade-plan.md` for the pattern this doc continues) — are mostly proven out at the *feature* layer: STT/MT/LID/TTS/OCR engines are real, measured, and cited; fold- and tablet-specific branches genuinely adapt the UX; Practice/Learn/Conversations are genuine learning tools, not a dictionary lookup.

What isn't yet proven out is the *systems* layer underneath those features: nothing gates a commit before it ships, a crash today leaves no trace once Logcat scrolls past it, a user's SRS streak has exactly one accidental backup path, four device-adaptive decisions are hardcoded per-branch instead of runtime-detected, one accessibility fix was applied once and already didn't get copied to the next screen that needed it, every large download restarts from byte zero on any interruption, and the `engine/` package has no build-enforced boundary between pure logic and Android glue. None of these are features a user asks for by name — they're the difference between "the demo works" and "this holds up."

Seven systems below, each surveyed against the real repo first (file paths, line numbers, actual current behavior — not assumed), then scoped against what would *actually* close that specific gap without violating goals 1–3. Every proposal that would have added a cloud dependency, a paid service, or off-device telemetry was disqualified before being written, not filtered out afterward.

**How to read this doc:** each section states the real problem with evidence, why it serves this app's specific goals (not generic best practice), the concrete system to build, a real illustrative code/config scaffold, effort sizing, risks, and — matching this repo's established discipline — what it explicitly does **not** solve. Nothing here has been built or run; every scaffold is illustrative and unverified until compiled and exercised on real hardware, exactly the standard `engines-upgrade-plan.md` already holds itself to.

## Priority order

| # | System | Effort | Why this order |
|---|---|---|---|
| 1 | [CI test-gate + 2 mechanical test files](#1-ci-test-gate--a-second-honest-coverage-seam) | ~1 afternoon | Zero new dependencies, protects every change after it lands |
| 2 | [`:core` Gradle module](#2-a-core-gradle-module-for-the-engine-layers-decision-logic) | ~1 day | Makes #1's future coverage expansion and #4's new logic testable by construction, not convention |
| 3 | [Adaptive device-capability tiering](#3-devicecapabilitygate-replacing-branch-hardcodes-with-runtime-signals) | ~1 day + device verification | Fixes disclosed correctness bugs (branch-hardcoded behavior) mostly by forward-porting code that already exists |
| 4 | [Resumable pack downloads](#4-resumable-retrying-pack-downloads) | Multi-day scoped pass | Directly protects the 529MB Gemma model and every large pack from wasted re-transfers |
| 5 | [Local diagnostics journal](#5-local-diagnostics-journal) | Multi-day scoped pass | The only way to see what actually breaks for a real user, without a cloud dependency |
| 6 | [Practice accessibility fixes](#6-closing-practices-talkback-gap-and-light-theme-contrast-failure) | ~1 day + on-device TalkBack check | Closes a real, already-recurred gap in a core Practice mechanic |
| 7 | [Local data vault (backup/export)](#7-local-data-vault-manual-exportimport) | Multi-day scoped pass | Protects users' real Learn-progress investment; largest honest-gaps list (conversation persistence doesn't exist yet) |

Items 1–3 are close to independent and could ship in parallel; 4–7 are each their own scoped pass and shouldn't be folded together, matching this repo's own "don't fold into an unrelated feature pass" rule from `engines-upgrade-plan.md`'s Tier 4 framing.

---

## 1. CI test-gate + a second, honest coverage seam

**The problem.** 33 existing tests across 6 files (JUnit 4 only) run exactly when a human remembers to type `./gradlew testDebugUnitTest` — there's no `.github/workflows` directory despite `origin` pointing at a real GitHub remote with 8 live branches (`main`, four `fold5-*` variants, `fold5-device-edition`, `tab-s9fe-device-version`, `tabs9fe-device-edition`). Any of them can silently break the build or regress an already-tested file, and nothing says so until someone runs the suite by hand. Separately, coverage stops at pure-Kotlin logic by construction — of the untested engine classes, most (`VoskEngine`, `TranslationEngine`, `PiperTtsEngine`, etc.) genuinely need JNI/audio/network and are out of scope for a JVM-only fix. One exception: `LearnProgressStore` is a plain `SQLiteOpenHelper` with no JNI, network, or audio dependency — untested only because nothing in this repo can construct an `android.content.Context` today.

**Why it fits.** Goal 6 (honest engineering): this repo already treats "did the tests actually pass" as evidence to capture (real JUnit XML under `app/build/test-results/`), not assume — CI makes that per-commit instead of per-manual-run, across all 8 branches at once. Goals 1–3 are untouched by construction: CI is dev-time infrastructure running on free-tier GitHub runners against the *existing* JVM test suite — no device, no user data, no new runtime capability the shipped app gains. Goal 5: `LearnProgressStore`'s XP/streak/Leitner-box logic is the actual mechanic behind Learn's "not just a dictionary lookup" claim, and none of it has any test today.

**The system.**
- **Part A** — `.github/workflows/ci.yml`, triggers on push to any branch + PR + manual dispatch (matching the real 8-branch topology), runs `./gradlew testDebugUnitTest --continue` for both `:app`/`:wear`, uploads the real JUnit XML as an artifact.
- **Part B** — two new, mechanical test files riding along: `AnswerCheckerTest.kt` (zero-dependency logic, same shape as the existing `TranscriptEntryTest.kt`) and `PackInventoryTest.kt` (drives `VoskModelCatalog.MODELS`/`PiperVoiceCatalog.VOICES` directly — deliberately *not* `PackInventory.all()`, since that pulls in `LanguageCatalog.codes` → `TranslateLanguage.getAllLanguages()`, the same ML-Kit-runtime dependency `LanguageCatalogTest.kt` already documents avoiding).
- **Part C** — a scoped, separate Robolectric introduction (`:app`'s first-ever), just for `LearnProgressStoreTest.kt`, using the real `SQLiteOpenHelper` via Robolectric's shadow SQLite. Requires widening `recordActivityToday()` to take an injectable `today: LocalDate` default parameter — the same precedent `LearnModels.kt`'s `LearnCourseLoader.parse` already set (`private` → `internal` specifically so a test could call it).

**Scaffold.**
```yaml
# .github/workflows/ci.yml
name: CI
on:
  push:
    branches: ["**"]        # real topology: main + 7 device-specific branches
  pull_request:
  workflow_dispatch:

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - uses: gradle/actions/setup-gradle@v4   # reads the committed gradle-8.7-bin.zip wrapper
      - name: Run JVM unit tests (both modules)
        run: ./gradlew testDebugUnitTest --continue
      - name: Upload JUnit reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: junit-test-results
          path: |
            app/build/test-results/testDebugUnitTest/*.xml
            wear/build/test-results/testDebugUnitTest/*.xml
```
```kotlin
// app/src/test/java/com/retroid/translator/learn/AnswerCheckerTest.kt
class AnswerCheckerTest {
    @Test fun `exact match passes at default threshold`() =
        assertTrue(AnswerChecker.matches("good morning", "good morning"))
    @Test fun `word order does not matter, by design`() =
        // AnswerChecker's own doc comment: Vosk transcripts can reorder/drop small words.
        assertTrue(AnswerChecker.matches("good morning", "morning good"))
    @Test fun `below-threshold overlap fails`() =
        assertFalse(AnswerChecker.matches("good morning to you", "good"))
}

// app/src/test/java/com/retroid/translator/learn/LearnProgressStoreTest.kt (Part C)
@RunWith(RobolectricTestRunner::class)
class LearnProgressStoreTest {
    private lateinit var store: LearnProgressStore
    @Before fun setUp() { store = LearnProgressStore(ApplicationProvider.getApplicationContext()) }

    @Test fun `consecutive-day activity increments streak, a gap resets it to 1`() {
        val day1 = LocalDate.of(2026, 8, 20)
        assertEquals(1, store.recordActivityToday(day1))
        assertEquals(2, store.recordActivityToday(day1.plusDays(1)))   // consecutive
        assertEquals(1, store.recordActivityToday(day1.plusDays(5)))   // gap -> reset
    }
}
```

**Effort.** Parts A+B: one afternoon, zero new dependencies. Part C: half a day, bracketed separately as this repo's first Robolectric dependency — ship independently, don't let it block A/B.

**Risks.** Part A presumes GitHub's `ubuntu-latest` image resolves `compileSdk 34`/AGP 8.3.2 for a unit-test-only task — unconfirmed until the first real run. Part C's real risk: Robolectric's `android-all` shadow jar sharing a classpath with the existing `org.json:json:20240303` shadow dependency (added to fix a real, documented stub-jar bug where `VoskResultParsingTest` silently passed against blank/zero values) — two libraries independently shadowing the Android SDK is a plausible source of the *same* silent-wrong-value bug class, worth an explicit check before trusting any Robolectric-backed assertion.

**Honest gaps.** No instrumented/`androidTest` coverage added. `VoskEngine`, `TranslationEngine`, `DownloadManager`, `PiperTtsEngine`, `EspeakEngine`, `LlmAssistEngine`, `MicPipeline`, `TtsRouter` remain completely untested — Robolectric can't load real `.so` files or run real MediaPipe inference, so this is a structural boundary, not an oversight. No coverage/Jacoco tooling proposed: a coverage percentage wouldn't have caught the actual historical bug this repo already hit (every line executing while still returning wrong values). Does nothing for the already-disclosed data gaps in `engines-inventory.md` (22/25 Vosk languages untested, no live-speaker/real-OCR-photo testing) — CI runs code-level tests, it can't manufacture real audio/camera data.

---

## 2. A `:core` Gradle module for the engine layer's decision logic

**The problem.** The `engine/` package (16 files, 1876 lines) has no Gradle-enforced boundary between Android-framework code and pure decision logic — "pure logic vs. Android I/O" is a convention, not something the build graph can fail on. Only 2 of 5 already-Android-import-free files have any test. Worse, real branching logic sits fused inside `Context`-taking functions: `DownloadManager`'s Wi-Fi/cellular gate is inlined *twice* — once in `DownloadManager.runDownload`, and independently hand-copied again inside `TranslationEngine.attemptTranslate` — meaning the two copies can silently drift from each other.

**Why it fits.** Goal 6: converts a gap in the *build graph itself* from documented weakness into an actual compile error. Goals 1–3 satisfied by construction: a build-time reorganization only, same pinned Kotlin 1.9.22, zero new runtime dependency, zero APK/network implication. Deliberately orthogonal to goals 4/5 — pure engineering hygiene, matching this repo's own tiered scoping discipline.

**The system.** A fourth Gradle module, `:core`, as a **plain `kotlin("jvm")` module — not `com.android.library`**. That distinction is the actual point: a `com.android.library` module still has `android.jar` on its classpath even with zero explicit Android deps, so `import android.content.Context` would still silently compile. Only `kotlin("jvm")` has no Android SDK on the classpath at all — an accidental Android import becomes a real build failure. `:core` keeps the same package name (`com.retroid.translator.engine`) so call sites in `:app` need zero import changes.

1. **Verbatim moves:** `VoiceGender.kt`, `EspeakLanguageMap.kt`, `PiperVoiceCatalog.kt`, `VoskResultParsing.kt` (the last needs `org.json:json` as `compileOnly`, not `implementation` — get this wrong and its classes collide with Android's platform `org.json` at dex-merge time).
2. **Three narrow extractions**, each: pull the `Context`-free logic into a pure function in `:core`, leave a one-line caller behind:
   - `DownloadPolicy.shouldBlockDownload(requireWifi, allowCellular, onWifi): Boolean` — unifies the two independently-inlined copies.
   - `VoskModelCatalog`'s pure 85 lines move unchanged; `effectiveModelInfo(context, code)` becomes a thin extension function in `:app` calling a pure `effectiveModelInfo(code, highAccuracyEnabled: Boolean)` in `:core`.
   - `TtsEngineLabel.forState(naturalVoiceDownloaded, espeakSupportsLanguage): String` extracted from `TtsRouter.activeEngineLabel`'s 3-way `when`.

**Scaffold.**
```kotlin
// core/build.gradle.kts — NEW. No android {} block: this is the real enforcement.
plugins { id("org.jetbrains.kotlin.jvm") }
dependencies {
    compileOnly("org.json:json:20240303")   // compile-time stub only, see collision note above
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// core/.../engine/DownloadPolicy.kt
object DownloadPolicy {
    fun shouldBlockDownload(requireWifi: Boolean, allowCellular: Boolean, onWifi: Boolean): Boolean =
        requireWifi && !allowCellular && !onWifi
}

// app/.../engine/DownloadManager.kt — CHANGED (1 line inside runDownload)
- if (requireWifi && !allowCellularDownloads(context) && !isOnWifi(context)) {
+ if (DownloadPolicy.shouldBlockDownload(requireWifi, allowCellularDownloads(context), isOnWifi(context))) {

// core/.../engine/VoskModelCatalog.kt — MOVED, minus the Context-touching method
object VoskModelCatalog {
    val MODELS: List<VoskModelInfo> = listOf(/* unchanged */)
    fun effectiveModelInfo(mlKitCode: String, highAccuracyEnabled: Boolean): VoskModelInfo? {
        val tier = accuracyTierFor(mlKitCode)
        return if (tier != null && highAccuracyEnabled) tier else forLanguage(mlKitCode)
    }
}
// app/.../engine/VoskModelCatalogAndroid.kt — NEW, thin Context-reading half
fun VoskModelCatalog.effectiveModelInfo(context: Context, mlKitCode: String): VoskModelInfo? =
    effectiveModelInfo(mlKitCode, VoskAccuracyPreference.isHighAccuracyEnabled(context, mlKitCode))
// all 7 existing call sites compile unchanged
```

**Effort.** A single focused day: module scaffolding ~1hr, the three extraction seams are small targeted diffs against already-understood code, tests follow `LanguageCatalogTest.kt`'s existing style. Never touches `compileSdk`/AGP/Kotlin-major-version, so none of Tier 4's migration cascade risk applies.

**Risks.** The `compileOnly` vs. `implementation` distinction on `org.json` is a real, concrete failure mode if gotten wrong (duplicate-class dex-merge error), not hypothetical. Nothing stops a future PR from adding an Android dependency to `:core` directly beyond normal review — no automated dependency-analysis guard included. The `VoskModelCatalog` extension-function resolution at the 7 call sites should get a real compile-and-run check, not just a read-through.

**Honest gaps.** Does not make `DownloadManager`, `VoskEngine`, `EspeakEngine`, `PiperTtsEngine`, `LlmAssistEngine`, `VoicePreferences`, `VoskAccuracyPreference` unit-testable in any real sense — most have no pure logic to extract at all (structural Context/media coupling: Handler/Looper threading, AudioTrack, filesystem paths), and extracting something there anyway just to claim broader coverage would be exactly the unearned completeness this codebase's style avoids. `DownloadManager.runDownload`'s actual HTTP/zip/tar mechanics stay untested — only the boolean gate guarding it moves. This is a one-time reorganization, not an enforcement mechanism against new logic being written the old, fused way in the future.

---

## 3. DeviceCapabilityGate: replacing branch-hardcodes with runtime signals

**The problem.** Four independent decisions are gated by which git branch produced the APK, not by anything the device reports at runtime: `MainActivity.seedFold5LayoutDefaultsIfNeeded()` unconditionally hardcodes three cover-screen variants; the Vosk accuracy-tier toggle has no capability check before offering a 125MB download; `DownloadManager.allowCellularDownloads()` defaults to `true` app-wide; `LlmAssistEngine` offers a 529MB Gemma load with zero RAM check. Real runtime signals already exist in this codebase to answer these questions — `FoldPostureProvider`'s hinge detection, and `DeviceCapabilities.kt`'s RAM tiering — but `DeviceCapabilities.kt` isn't even on the currently-checked-out `fold5-device-version` branch: it was built on a divergent sibling line (`fold5-device-edition`, commit `ad27ca2`) that forked before this branch's HEAD and was never merged forward. A QA install of `fold5-device-version` on non-fold hardware silently pre-seeds cover-screen-only variants; a low-RAM device can opt into the LLM download exactly as freely as a Fold 5.

**Why it fits.** Goal 4 is exactly what's being faked today — fold-aware and tablet-aware *branches*, not fold-aware and tablet-aware *code*. This makes the same APK behave correctly regardless of which hardware it actually lands on. Goals 1–3 preserved: every signal (`ActivityManager.MemoryInfo`, the already-vendored `androidx.window`, `ConnectivityManager.isActiveNetworkMetered`) is a stock platform API already linked in — no new library, no network call. Goal 6: every new threshold is explicitly labeled engineering judgment vs. citation, distinguishing "measured" from "reasoned" the way `engines-upgrade-plan.md`'s own Tier 3 section already does.

**The system.** Two layers, deliberately *not* a single unified "device tier" enum — Tab S9 FE is high-RAM but hingeless, so collapsing "RAM tier" and "has a fold" into one number would misrepresent the real fleet.
- **Layer 1** — forward-port `DeviceCapabilities.kt` from `ad27ca2` unchanged (near-zero design risk; the design work is already done and disclosed).
- **Layer 2** — three new, narrow signals, each used by exactly one decision: `hasHeadroomForLlm` (live `MemoryInfo.availMem`, distinct from the existing static total-RAM tier), `isMeteredConnection` (distinct from `isOnWifi`'s transport check — a mobile hotspot's Wi-Fi can be metered), and a storage-headroom check for the accuracy toggle (deliberately *not* a RAM check — no measured RAM delta for a resident lgraph model exists anywhere in this codebase).

**Scaffold.**
```kotlin
// DeviceCapabilities.kt additions
/** NOT a measured threshold, unlike HIGH_RAM_THRESHOLD_BYTES - no on-device Gemma 3 1B
 *  memory-footprint measurement exists anywhere in this codebase. 2.5x on-disk size is
 *  engineering judgment for runtime tensor/activation overhead - replace the moment a
 *  real measurement exists. */
private const val LLM_RAM_MULTIPLIER_X10 = 25L
fun hasHeadroomForLlm(context: Context, modelSizeMib: Int = LlmAssistEngine.APPROX_SIZE_MIB): Boolean =
    availableRamBytes(context) >= modelSizeMib.toLong() * 1024 * 1024 * LLM_RAM_MULTIPLIER_X10 / 10

fun isMeteredConnection(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    return cm.isActiveNetworkMetered
}

// MainActivity.kt - fold seeding into the postureFlow collector instead of an
// unconditional onCreate() call
provider.postureFlow().collect { state ->
    if (!seedChecked && !LayoutPreferences.areDeviceDefaultsSeeded(this@MainActivity)) {
        seedChecked = true
        seedFold5LayoutDefaultsIfNeeded(hasFold = state.posture != FoldPosture.NO_FOLDING_FEATURE)
    }
}

// LlmAssistEngine.kt - loadAsync(), gate added before the existing "not downloaded" check
if (!DeviceCapabilities.hasHeadroomForLlm(appContext)) {
    onResult(false, "Not enough free memory on this device to load the on-device AI model right now")
    return
}
```

**Effort.** ~1 focused day for the code (cherry-pick + wiring is mostly mechanical since the hard design already exists), plus a separate, open-ended real-device verification pass (confirming `postureFlow`'s first-emission timing on real Fold 5 hardware, confirming `MemoryInfo.availMem` under induced pressure) that this estimate deliberately doesn't fold in.

**Risks.** The cherry-pick is assumed clean but not verified conflict-free. Moving seeding into `postureFlow`'s collector trades a synchronous guarantee for an asynchronous one — a real, narrow race where a user who navigates before the first emission sees the default variant on that one launch. `SettingsHubFragment`'s fix is the one change that isn't a single-branch PR — it needs to land on both `fold5-device-version` and `tab-s9fe-device-version`, which this repo's per-device-branch model doesn't obviously support with an established merge cadence. `isActiveNetworkMetered` has known real-world platform quirks (some carriers misreport it) — a heuristic, not a guarantee.

**Honest gaps.** Does not produce the one measurement `LlmAssistEngine`'s own introducing commit already flagged missing: real on-device Gemma 3 1B latency/memory footprint. `hasHeadroomForLlm`'s 2.5x multiplier is a reasoned placeholder that should be thrown out the moment someone captures real RSS via `dumpsys meminfo`. No RAM gate proposed for the Vosk accuracy tier, only storage — because no measured RAM delta exists to size a threshold against. Doesn't touch `allowCellularDownloads`'s actual default value — that's a disclosed product decision, not a device-capability question. Doesn't fix the underlying branch-divergence process that let `DeviceCapabilities.kt` sit unused on a sibling line in the first place.

---

## 4. Resumable, retrying pack downloads

**The problem.** `DownloadManager.runDownload` is the shared core for all three download paths — Vosk packs (30–290MB), Piper voices (~65MB), and the Gemma 3 1B model (529MB). Every one restarts from byte zero on any interruption: no `Range` header is ever sent (zero real hits for Range/ETag/resume semantics repo-wide), the temp file is timestamp-named so a retry can never find bytes already on disk, and `finally { tmp?.delete() }` unconditionally deletes whatever was fetched. `BulkDownloadCoordinator.downloadNext` compounds this at the batch level — a single transient Wi-Fi drop on item 7 of a ~92-pack "download all" run permanently skips that item, zero retry. On `fold5-device-version`, where cellular downloads are allowed by default, a drop 400MB into the 529MB Gemma model means re-transferring the full 529MB again over a possibly-metered connection.

**Why it fits.** Goals 1–2: touches only two existing files, zero new libraries — the resume journal is a plain sidecar text file, matching the precedent `LanguagePackPreferences.kt` already set for "no new persistence dependency for a handful of small values." Goal 3: no new data leaves the device — the journal stores only a URL and an ETag, both already visible to the same host on every plain request. Goal 4: a proposed cache-GC pass is sized around Watch6 Classic's measured ~426MB real `MemAvailable`, not a one-size assumption. Goal 6: explicitly does not claim resume "works" until verified against the three real hosts this app actually pulls from.

**The system.** Two files change, no new files, no new dependency.
- `DownloadManager.runDownload` gets Range-based resume: deterministic temp filenames (`dl_<sha256(url)>.zip` instead of timestamp-suffixed), a sidecar `.journal` file (URL + ETag/Last-Modified) written on first attempt, `Range`/`If-Range` headers sent on retry, response branching on `206` (append) vs. anything else (discard and restart clean — never a silent corrupt merge). The `destDir`-wipe-on-failure safeguard stays untouched; only `tmp` stops being deleted on failure, since it's now the resume checkpoint. A new `pruneStaleResumeCheckpoints` sweeps abandoned partials after 7 days.
- `BulkDownloadCoordinator.downloadNext` retries the same item up to 3 times with exponential backoff before advancing — composes with the above so each retry resumes from wherever the previous attempt stopped, rather than re-fetching from zero.

**Scaffold.**
```kotlin
// DownloadManager.kt
private fun stableTmpName(url: String, suffix: String): String =
    "dl_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16) + suffix

// inside runDownload, on retry:
val resumeFrom = if (tmp.exists() && tmp.length() > 0 && journal?.first == url) tmp.length() else 0L
val conn = (URL(url).openConnection() as HttpURLConnection).apply {
    if (resumeFrom > 0) {
        setRequestProperty("Range", "bytes=$resumeFrom-")
        journal?.second?.let { setRequestProperty("If-Range", it) }
    }
}
val resumed = resumeFrom > 0 && conn.responseCode == HttpURLConnection.HTTP_PARTIAL
if (resumeFrom > 0 && !resumed) {
    // Server ignored Range or validator no longer matches - can't trust partial bytes.
    tmp.delete(); journalFile(tmp).delete()
    return@execute runDownload(context, url, tmpSuffix, destDir, requireWifi, onProgress, onDone, extract)
}
// ... FileOutputStream(tmp, append = resumed) ...
// tmp is deliberately NOT deleted in the catch block - it's the next attempt's checkpoint.

// BulkDownloadCoordinator.kt
private fun downloadNext(items: List<PackDescriptor>, index: Int, total: Int, successCount: Int, failCount: Int, attempt: Int = 0) {
    // ...
    downloadSingle(item, ...) { success, error ->
        if (!success && attempt < MAX_RETRIES_PER_ITEM) {
            val backoffMs = BASE_BACKOFF_MS * (1L shl attempt)  // 2s, 4s, 8s
            mainHandler.postDelayed({ downloadNext(items, index, total, successCount, failCount, attempt + 1) }, backoffMs)
            return@downloadSingle
        }
        // ... existing failure/advance logic ...
    }
}
```

**Effort.** Multi-day scoped pass: ~1 day for the Range/journal/append rewrite, ~1 day for retry/backoff wiring, ~1–2 days of real on-device verification (forced mid-download disconnects against each real host) before resume can honestly be called working rather than just written.

**Risks.** Server Range/206 support is unverified per-host — must be checked against huggingface.co's redirect chain (the Gemma model's actual host, where a 302 to a signed URL could complicate Range semantics), sherpa-onnx's GitHub Releases CDN, and alphacephei.com before trusted for any of them. If a host lacks an ETag/Last-Modified validator, the design still degrades safely (no validator → no `If-Range` → mismatch falls through to clean restart, never a silent corrupt merge). Does not touch ML Kit translation/OCR downloads, which never go through `DownloadManager` at all. Retry/backoff constants (3 retries, 2s base) are starting guesses — this app has no telemetry, by design, to calibrate them from.

**Honest gaps.** This is a design and code sketch, not proof it works — the central unverified claim (three real hosts actually honor Range/206) requires the on-device pass described above. No content-hash verification against a published checksum exists before or after this change — ETag/Last-Modified is a weaker "hasn't obviously changed" signal, a pre-existing gap this doesn't close. Cancellation stays cooperative, not preemptive. No UI distinguishes "resuming from 40%" from "starting fresh" in the progress callback.

---

## 5. Local diagnostics journal

**The problem.** The entire diagnostic surface today is Logcat (wiped on reboot, adb-only) plus transient Toasts (gone once dismissed) — no crash library, no `Thread.setDefaultUncaughtExceptionHandler`, no log-persistence or export path anywhere, no Settings entry. Any exception escaping one of 58 existing `Log.e`/`Log.w` call sites, or any uncaught exception at all, produces the stock "App has stopped" dialog with zero captured context that survives past the moment it happens.

**Why it fits.** Goals 1–3: uses only what's already on the classpath — `android.util.Log`, `SQLiteOpenHelper` (same mechanism `LearnProgressStore` already uses), plain files, and `FileProvider` (already transitively present via `androidx.core-ktx`). Zero new dependencies, zero network calls. The only outbound path is a user-initiated OS share sheet — structurally identical in spirit to the app already showing exception messages in a Toast, except the user can choose to keep or share the full record instead of losing it. Goal 6: names a gap `engines-inventory.md`/`engines-upgrade-plan.md` never mention, and states plainly what it doesn't solve (no cross-device visibility — correctly out of scope under goals 1–3). Goal 4: the wear-side layer is deliberately smaller, sized against Watch6 Classic's documented ~426MB `MemAvailable`.

**The system.** New `diagnostics` package, two tiers. **Tier 1** (`CrashHandler`) wraps the existing default uncaught-exception handler (doesn't replace it — the OS dialog and process-death semantics are unchanged) and writes one minimal flat-file record via a swallowed-on-failure `FileOutputStream` append, avoiding SQLite at crash time (re-entrancy risk if the crash itself involves storage/IO). **Tier 2** (`DiagnosticsStore`) is a `SQLiteOpenHelper` styled directly on `LearnProgressStore`'s pattern, capped at 200 rows. A `Diag` facade object mirrors `Log.e`/`Log.w` exactly (Logcat behavior unchanged) and additionally persists. On next launch, any pending Tier-1 crash file is folded into `DiagnosticsStore` as one FATAL row. A new `DiagnosticsFragment` (a sixth Settings row) lists recent events and has one explicit "Share diagnostic log" button via `FileProvider` — nothing auto-sends. Both `backup_rules.xml` and `data_extraction_rules.xml` need the new `diagnostics/` path added to their existing exclusion lists.

**Scaffold.**
```kotlin
object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { writeCrashRecord(appContext, thread, throwable) } catch (e: Throwable) { /* never mask the real crash */ }
            previous?.uncaughtException(thread, throwable)   // preserve today's OS dialog/process-death
        }
    }
}

class DiagnosticsStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "diagnostics.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL("""
        CREATE TABLE diagnostic_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, ts_epoch_ms INTEGER NOT NULL,
            level TEXT NOT NULL, tag TEXT NOT NULL, message TEXT NOT NULL, stack_trace TEXT
        )""".trimIndent())
    fun append(level: Char, tag: String, message: String, t: Throwable?) {
        writableDatabase.use { db ->
            db.insert("diagnostic_events", null, ContentValues().apply { /* ... */ })
            db.execSQL("DELETE FROM diagnostic_events WHERE id NOT IN (SELECT id FROM diagnostic_events ORDER BY id DESC LIMIT 200)")
        }
    }
}

object Diag {
    private var store: DiagnosticsStore? = null
    fun init(context: Context) { store = DiagnosticsStore(context) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, msg, t); store?.append('E', tag, msg, t) }
}
// Representative migration (mechanical, one line per site):
// - Log.e(TAG, "Failed to load Piper voice ${info.voiceId}", e)
// + Diag.e(TAG, "Failed to load Piper voice ${info.voiceId}", e)
```
```xml
<!-- data_extraction_rules.xml and backup_rules.xml -->
<exclude domain="file" path="diagnostics/" />
```

**Effort.** A scoped multi-day pass: Tier A core capture ≈ 1 day; migrating the 58 `Log.e`/`Log.w` call sites (including a required per-site check that no message string leaks actual translated/recognized/conversation text, only metadata) ≈ 0.5–1 day; the Settings UI + `FileProvider` export ≈ 0.5 day; real-device verification of a forced crash actually surviving and being recoverable ≈ 0.5 day. Wear v1 (Tier 1 only, no viewer) ≈ 0.5 day extra.

**Risks.** Re-entrancy at crash time if the crash itself involves storage/IO — mitigated by keeping Tier 1 a minimal flat-file append in its own swallowed try/catch. `allowBackup="true"` means the new path must be excluded from *both* rules files, not just one. The 58-site migration needs a real content check per site, not a mechanical find-and-replace — some existing messages may need rewording before being persisted.

**Honest gaps.** No automatic, cross-device crash visibility — the one thing a cloud crash-reporter would add, correctly out of scope under goals 1–3. If a user never taps "Share," a crash is invisible to anyone but them, forever — a real, permanent capability gap, not temporary. Doesn't audit or fix the ~21 existing silent `catch (e: Exception) { /* ignore */ }` teardown blocks — wiring those to `Diag.w` is a separate, deliberately-deferred decision since some of that silence is a defensible "don't let cleanup failure mask the real error" choice. The crash-time file write hasn't been tested against a real OOM or storage-exhaustion failure on-device.

---

## 6. Closing Practice's TalkBack gap and light-theme contrast failure

**The problem.** `PracticeFragment.bindDrillCarousel` navigates the drill carousel purely via a raw `setOnTouchListener` swipe — not clickable, not focusable, no `contentDescription`, no button fallback. This is the *exact* failure mode `TranslateFragment` already named and fixed once for `cardCircle` via `installSingleCircleAccessibility` — and the layout file's own comment even names the parallel technique explicitly without applying the fix. Repo-wide, `setOnTouchListener` has exactly 2 real call sites, so this bug already recurred once, in the one other place the technique was reused. Separately, `renderPronunciationFeedback` colors Vosk per-word confidence via 3 hardcoded hex values with no explicit background, rendering against `#FAFAFA` in light mode — independently recomputed WCAG contrast: green 2.66:1, orange 2.06:1, red 3.53:1, all failing the 4.5:1 AA threshold (orange fails by more than half). Dark mode's original hex values already pass comfortably.

**Why it fits.** Goal 5: the carousel and pronunciation feedback aren't decoration, they're Practice's core mechanics — an unreachable carousel doesn't degrade the learning loop for a TalkBack user, it removes it entirely. Goal 4: extends this repo's existing "adapt to how a real person actually uses the device" discipline from screen geometry to input modality. Goal 6: the gesture fix reuses the exact underlying functions the touch path already calls (never reimplemented), and the contrast fix is built from an actually-run WCAG calculation, shown, with its small discrepancy from a prior source disclosed rather than papered over.

**The system.** Three additive pieces, none touching the already-verified `cardCircle` path.
1. `AccessibleGestureCard.kt` — generalizes `installSingleCircleAccessibility`'s pattern into a reusable installer: attach an `AccessibilityDelegateCompat` exposing named custom actions, each calling the same function the touch gesture already calls. Applied to `cardCarouselPhrase` — "Previous/Next phrase" wired to the existing `moveCarousel(-1/1)`, "Perform current step" to the existing `performCarouselAction(b)`.
2. Replace the 3 hardcoded hex colors with day/night color resources, chosen to actually clear 4.5:1 in light mode, plus a compact bracketed non-color suffix (`" (uncertain)"` / `" (unclear)"`) as a second cue per word — narrowing, not fully closing, the WCAG 1.4.1 use-of-color gap.
3. A small grep-based guardrail script — not a real lint rule, sized to match how small the actual surface is — failing CI if a `.kt` file calls `setOnTouchListener` without also referencing the new accessibility installer, targeting the exact recurrence class this bug already demonstrated.

**Scaffold.**
```kotlin
// ui/a11y/AccessibleGestureCard.kt
class AccessibleGestureAction(val label: String, val isAvailable: () -> Boolean = { true }, val perform: () -> Unit)

fun installAccessibleGestureCard(view: View, description: () -> String, actions: List<AccessibleGestureAction>) {
    ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            host.contentDescription = description()
            actions.filter { it.isAvailable() }.forEach { info.addAction(/* ... */) }
        }
    })
}

// PracticeFragment.kt, inside bindDrillCarousel
installAccessibleGestureCard(
    view = b.cardCarouselPhrase, description = ::drillCarouselAccessibilityDescription,
    actions = listOf(
        AccessibleGestureAction("Previous phrase", { phraseQueue.size > 1 }) { moveCarousel(-1) },
        AccessibleGestureAction("Next phrase", { phraseQueue.size > 1 }) { moveCarousel(1) },
        AccessibleGestureAction("Perform current step", { currentCarouselPhrase() != null }) { performCarouselAction(b) },
    ),
)
```
```xml
<!-- values/colors.xml - light values computed to clear 4.5:1 vs #FAFAFA -->
<color name="confidence_clear_light">#2E7D32</color>      <!-- 4.91:1 -->
<color name="confidence_uncertain_light">#8F4A00</color>  <!-- 6.39:1 -->
<color name="confidence_unclear_light">#C62828</color>    <!-- 5.39:1 -->
```
```bash
#!/usr/bin/env bash
# scripts/check_gesture_accessibility.sh - flags the exact recurrence this repo already had once
for f in $(grep -rl 'setOnTouchListener' app/src/main/java --include='*.kt'); do
  grep -q -e 'installAccessibleGestureCard' -e 'AccessibilityDelegateCompat' "$f" \
    || { echo "::error file=$f::setOnTouchListener with no accessibility path in this file"; exit 1; }
done
```

**Effort.** About a 1-day scoped pass. Extraction/wiring ~2–3hrs (a parameterized copy of an already-proven technique). Contrast/palette change ~1hr to implement, but needs real on-device verification (uiautomator dump + a real contrast check against rendered pixels, not just formula output) — another 1–2hrs. Guardrail script ~30min.

**Risks.** Retrofitting onto `cardCircle` is explicitly *not* proposed — only new, additive use on Practice's carousel, to avoid regressing the one accessibility path already verified on real hardware. The contrast numbers here are an independent recomputation that differs slightly from an earlier cited figure (same fail/pass conclusion either way, but the discrepancy itself should be resolved with a dedicated contrast tool, not trusted from either calculation alone). The bracketed suffix adds real TalkBack verbosity on a long phrase, with no mute option proposed.

**Honest gaps.** Does not touch the ~30/43 sub-48dp touch targets found across nearly every screen — a different guideline class and a much broader surface, deliberately left out. Does not localize the ~52 hardcoded English `contentDescription` strings into `@string/` resources. Does not extend the gesture-card pattern beyond `cardCarouselPhrase` — if a raw gesture handler exists elsewhere that a grep-based search missed, it isn't covered. No real TalkBack session has actually been run as part of writing this pitch.

---

## 7. Local Data Vault: manual export/import

**The problem.** RetroidTranslator has no in-app way to see, trigger, or verify a backup of anything a user has earned or configured. Learn progress (XP, streak, lesson completion, Leitner-box SRS state) only survives a device change today because `backup_rules.xml`/`data_extraction_rules.xml` happen not to mention it — an omission, not a decision; both files carefully justify excluding `recordings/`, `vosk-models/`, `piper-voices/` and say nothing about the database at all. No Settings destination exists for a user reinstalling, switching from a Fold5 to a Tab S9 FE, or wanting a safety copy before clearing app data.

**Why it fits.** Goal 1: runs entirely on Android's Storage Access Framework (`ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`) — zero sockets opened, the app writes bytes to a `Uri` the OS handed back after the user picked a destination, the same mechanism any "Save As" dialog uses. Goal 2: zero new libraries — JSON handling uses `org.json`, already this codebase's established pattern. Goal 3: the export deliberately mirrors and reinforces `backup_rules.xml`'s existing exclusions — recordings and downloaded packs never enter the bundle. Goal 6: converts `learn_progress.db`'s current accidental, invisible OS-backup inclusion into a deliberate, visible, versioned mechanism, with the Settings screen stating in plain text exactly what is and isn't included.

**The system.** New `backup` package, three files, one new Settings row. `BackupBundle.kt` defines `SCHEMA_VERSION = 1` and the JSON shape. `BackupManager.kt` is the only class touching persistence — `export(uri)` reads `LearnProgressStore`'s three tables plus five known `SharedPreferences` files, assembles one `JSONObject`, writes it to `contentResolver.openOutputStream(uri)`. `import(uri)` reverses this, first copying the live database to a `.pre-import-<epochMs>.bak` safety net. `DataBackupFragment.kt` is the sixth Settings destination, wired exactly like the existing five, with Export/Import buttons backed by `ActivityResultContracts.CreateDocument`/`OpenDocument`, and an explicit confirmation dialog before import overwrites anything.

**Scaffold.**
```kotlin
class BackupManager(private val context: Context) {
    fun export(uri: Uri) {
        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("learnProgress", exportLearnProgress())
            put("preferences", exportPreferences())
        }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { it.write(root.toString(2)) }
        } ?: error("Could not open output stream for $uri")
    }

    private fun exportPreferences(): JSONObject {
        // SharedPreferences is heterogeneously typed (Boolean/Int/Long/Float/String/Set<String>)
        // but JSON has no native Int-vs-Long distinction and no Set - tag the type explicitly
        // rather than relying on JSONObject.put(k, v) round-tripping cleanly.
        val out = JSONObject()
        for (name in prefsFiles) {
            val fileObj = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                fileObj.put(k, JSONObject().apply { put("type", v!!::class.simpleName?.lowercase()); put("value", v) })
            }
            out.put(name, fileObj)
        }
        return out
    }
    companion object { const val SCHEMA_VERSION = 1 }
}
```
```json
{
  "schemaVersion": 1,
  "learnProgress": {
    "appState": { "xp_total": "1240", "streak_count": "6" },
    "srsState": [{ "exerciseKey": "es_greetings_03", "box": 2, "correctCount": 4 }]
  },
  "preferences": { "voice_prefs": { "gender": { "type": "string", "value": "female" } } }
}
```
```xml
<!-- backup_rules.xml / data_extraction_rules.xml, once DataBackupFragment ships -->
<exclude domain="database" path="learn_progress.db" />
```

**Effort.** A scoped multi-day pass: `BackupManager` (schema, SQLite read/write, type-tagged prefs round-trip) ~1 day; `DataBackupFragment` + wiring + confirmation dialog ~1 day; real-device testing (fresh-install import, corrupt/foreign-JSON import, schema-mismatch refusal) ~1 day.

**Risks.** The SharedPreferences typing gotcha is required handling, not optional — today's five files only use scalars, so an untagged first cut would pass every current test and silently misbehave the moment a future preference introduces a `Set<String>`. Import is destructive by construction (full replace, not merge) — the pre-import `.bak` is necessary, not polish. Database I/O must run off the main thread via the coroutines dependency already present. The five prefs-file names are currently duplicated as string literals rather than referencing each class's own constant — a real drift risk if any is ever renamed.

**Honest gaps.** Conversation transcripts are not exportable because they are not persisted anywhere today — `ConversationsFragment` holds transcript rows in a plain in-memory list with no database or file write; giving transcripts a persistence layer at all is separate, larger, and more privacy-sensitive prerequisite work this pitch does not resolve. Practice/Conversations `.wav` recordings are deliberately excluded — already excluded from OS backup for privacy, folding them into a JSON export would quietly undo that. Import is replace-only with no cross-device merge/conflict-resolution logic. Schema versioning only refuses a newer version than the app understands — there's no migration path yet, fine while `SCHEMA_VERSION` has only ever been `1`. Nothing here touches `:wear` — it has no `LearnProgressStore` or matching prefs to export. No scheduled/automatic backup and no in-app nudge to actually use the feature exist — a user who never opens Settings gets no benefit.

---

*Surveyed and drafted via a 14-agent workflow (7 domains × survey-then-pitch) against the real `Z:\Dev\RetroidTranslator` repo on 2026-08-24. Every citation above (file paths, line numbers, commit hashes, measured contrast ratios) was independently verified by reading the actual file, not assumed — matching this doc's own stated bar. Nothing above has been built or run; treat every scaffold as illustrative and unverified until compiled and exercised on real hardware.*
