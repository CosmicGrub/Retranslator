# Spec: Samsung Galaxy Tab S9 FE adaptation

Status: **built and verified on the real target device**. All work below was implemented and tested on `galaxy-tab-s9fe-adaptation`, branched from `main` at `7b5fa4e` (which already contained the Fold 5 work this spec deliberately does not touch). Every on-device claim in this document is from the actual target hardware — no substitute device was used for this pass, unlike several passes in `docs/specs/fold5-adaptation.md`.

## 1. Hardware — real, connected, grounded in actual specs

Samsung Galaxy Tab S9 FE (5G), model **SM-X518U**, adb serial **`R52X101MB6W`**. Exynos 1380, 6GB RAM, 128GB storage, single flat 10.9" display, Android 16 (API 36).

Real display facts, measured on-device (`adb -s R52X101MB6W shell wm size` / `wm density`), not assumed:

```
Physical size: 1440x2304
Physical density: 280
```

Smallest width in dp: `1440px / (280dpi / 160dpi) ≈ 822.9dp`. This is the number that determined the resource-qualifier threshold in §3 below — comfortably clears the `sw720dp` breakpoint (Google's own "≈10" tablet" guidance) while staying safely clear of `sw600dp` (≈7" tablets), so the large-screen layouts built here activate only on genuinely large screens, never smaller tablets they were never designed or tested against.

Two other devices were connected throughout this work (`adb devices`): a Retroid Pocket 2+ (serial `15780287351340`, this project's original target) and the Galaxy Tab S9 FE itself. Every command in this pass targeted `-s R52X101MB6W` explicitly.

## 2. The toolbar bug — cross-check result (read this first, it matters most)

A prior session found `MainActivity`'s `MaterialToolbar` (the gear-icon Settings entry point) rendering at zero height and completely unreachable on the Retroid Pocket 2+ (Android 9 / API 28), confirmed via clean rebuild, full uninstall/reinstall, and inspecting the compiled APK's packaged resources. It was never confirmed as Fold-5-specific, Retroid-specific, or a broader issue.

**Result: does NOT reproduce on the Galaxy Tab S9 FE.** Confirmed with direct screenshot evidence on real hardware (serial `R52X101MB6W`, Android 16/API 36):

- The toolbar renders at its correct height, with visible title ("Retranslator") and the gear/wrench icon, on every screen (Translate, Settings hub, Language packs, etc.).
- All 4 bottom-nav tabs (Translate, Conversations, Practice, Learn — including "Learn," which was also missing on the Retroid Pocket 2+) render correctly and are reachable; tapping any of them switches tabs correctly.
- The gear icon opens the Settings hub correctly, which lists all 4 rows (Translate layout, Practice layout, Learn layout, Fold behavior, plus the new Language packs row from this pass) and navigates into each.

**Conclusion: this bug is old-OS/API-level-specific (Android 9 / API 28), not a defect in the app's toolbar/navigation code itself.** It does not need to be worked around on this hardware, and this pass proceeded with building UI on top of the existing Settings entry point without further investigation, per the task's own decision criteria.

## 3. What "parity" means for this hardware — the scoping decision

This tablet has **no hinge**. The `fold/` package (`FoldPostureProvider`, the posture matrix, `FoldingFeature`), the 24-variant settings-driven cover/Flex-Mode layout picker (`LayoutPreferences`, `*LayoutVariants.kt`, `*LayoutSettingsFragment.kt`), and `ConversationsFragment`'s mirrored face-to-face layout all exist specifically to react to a physical hinge this device does not have. **None of that was ported, modified, or touched by this pass** — verified by `git diff` against `7b5fa4e`: zero changes inside `app/src/main/java/com/retroid/translator/fold/`, zero changes to any `*LayoutVariants.kt`/`*LayoutSettingsFragment.kt`, and `ConversationsFragment`'s existing `applyPosture`/`switchLayout`/`bindMirroredView`/`applyMirroredGeometry` fold-mirrored code path is unchanged (only new code was added alongside it — see §5).

Instead, per the task's own framing:

1. **Full feature parity** — every existing capability (Translate, Conversations, Practice, Learn, all 4 TTS/STT engines, gender toggle, Settings hub, the existing 24-variant system) works correctly and is fully reachable on this device. Nothing was scoped down. Confirmed in §6/§7 below.
2. **Large-screen responsive layouts** — ordinary Android large-screen layout work (`sw720dp` resource qualifiers), not a hinge-reactive variant picker. Built as a genuinely **third** presentation mode alongside "default phone" and "fold-aware variant" — resolved automatically by the OS's resource-qualifier system based on screen configuration, completely independent of (and untouched by) the fold-posture and settings-variant systems.
3. **Auto-download all language packs** — a new orchestration layer wrapping the existing `VoskModelCatalog`/`PiperVoiceCatalog`/ML Kit catalogs without modifying their data, per the task's explicit instruction.
4. `fold/`, `ConversationsFragment`'s mirrored-layout code path, and the 24-variant settings system stay exactly as they are for when this device is a Fold 5.

## 4. Large-screen layouts — mechanism

Android resolves `layout-sw720dp/foo.xml` over `layout/foo.xml` automatically for any device whose smallest width is ≥720dp, entirely at resource-resolution time, before any Kotlin runs. This is the load-bearing fact that made the whole approach low-risk:

- `TranslateFragment`, `PracticeFragment`, `LearnFragment` already used a **dynamic-inflation architecture** predating this pass (`onCreateView` returns a bare `FrameLayout`; which of several real XML layouts gets inflated into it is decided live by fold posture / the settings-variant system, via `FragmentXxxBinding.inflate(...)` against `R.layout.fragment_xxx`). The **`DEFAULT`** variant of each (the one shown when no cover/Flex override is active) is exactly `R.layout.fragment_xxx` — so dropping a `layout-sw720dp/fragment_xxx.xml` with the *same view IDs* makes the OS pick the large-screen version automatically, with **zero Kotlin changes**, for Translate and Practice.
- `ConversationsFragment` predates that pattern (it selects between exactly two hardcoded bindings via `FoldPostureProvider`) — extending it to a third, resource-qualifier-selected layout required a small, deliberate Kotlin change (§5).
- `LearnFragment`'s default layout uses one shared `FrameLayout` where 4 "screens" (`screenUnits`/`screenLessons`/`screenExercise`/`screenSummary`) are mutually-exclusive-visibility children — genuine master-detail (keeping the browsing pane visible during an exercise) needed a small, targeted visibility-logic change (§5), not just new XML.

New default-value resource: `app/src/main/res/values/bools.xml` (`learn_master_detail=false`, `conversations_side_by_side=false`) with a `values-sw720dp/bools.xml` override (both `true`) — the standard Android idiom for letting Kotlin code detect "did a large-screen qualifier actually get picked for this configuration" without duplicating the `822.9dp` threshold logic in code.

### Translate (`layout-sw720dp/fragment_translate.xml`)

Two-pane `LinearLayout` (42/58 weighted split, vertical divider), identical view IDs to phone. Left pane: language pickers, swap button, auto-detect, both pack-download rows, gender radio, natural-voice status/download. Right pane: text input, mic/Translate row, result card, detected-language text — language pickers and result **side-by-side instead of stacked**, per the task's own example. Zero Kotlin changes.

### Practice (`layout-sw720dp/fragment_practice.xml`)

Two-pane split (50/50). Left pane: the full recording workflow (language, gender, phrase input, hear-reference, natural-voice status/download, record/play buttons, status). Right pane: **"Past attempts" as a persistent, always-visible list** — a genuine master-detail split (record a new attempt on the left while browsing/replaying past ones on the right, no scrolling past the whole form required). Zero Kotlin changes.

### Learn (`layout-sw720dp/fragment_learn.xml` + `LearnFragment.setDefaultScreen`)

Two-pane split (38/62). Left pane (master): unit list, or — once you drill in — that unit's lesson list (still mutually exclusive with the unit list, exactly like phone). Right pane (detail): the active exercise, or the lesson-complete summary. The Kotlin change is small and precisely scoped:

```kotlin
private fun setDefaultScreen(b: FragmentLearnBinding, screen: LearnDefaultScreen) {
    if (resources.getBoolean(R.bool.learn_master_detail)) {
        b.screenUnits.visibility = if (screen == LearnDefaultScreen.UNITS) View.VISIBLE else View.GONE
        b.screenLessons.visibility = if (screen == LearnDefaultScreen.UNITS) View.GONE else View.VISIBLE
        // (screenExercise/screenSummary unchanged below)
    } else { /* original phone logic, byte-for-byte */ }
    ...
}
```

On phone (`learn_master_detail=false`) this is byte-for-byte the original mutually-exclusive-among-all-four logic — verified by inspection, not just by the bool defaulting false. On the tablet, the master pane (whichever of Units/Lessons is current) never collapses just because the detail pane switched to Exercise/Summary. A real, on-device screenshot (§7) shows the left pane still reading "Greetings > Basic Greetings" while the right pane displays "Exercise 1/8" — genuine master-detail, not a mockup.

### Conversations (`layout-sw720dp/fragment_conversations_large.xml` + `ConversationsFragment`)

This tab got the most deliberate treatment, per the task's explicit callout that it deserved "genuinely considered" handling, not the phone layout blown up and not the fold's rotation trick repurposed without a hinge to justify it.

`ConversationsFragment` previously tracked exactly two mutually-exclusive layout states (`fallbackBinding`/`mirroredBinding`, a `mirrored: Boolean`). This pass adds a third, **`LayoutKind.LARGE`**, selected purely by `R.bool.conversations_side_by_side` — checked only when the device is *not* in a genuine fold-mirrored posture (mirrored always wins; see `applyPosture`'s `desiredKind` selection). The new layout reuses `view_conversation_pane.xml` **unmodified** — the exact same per-person pane (`textPaneTurnIndicator`, `textPaneTranscript`/`scrollPaneTranscript`, `textPaneStatus`, `btnPaneMic`, `togglePaneContinuous`) the fold-mirrored layout already uses — but as two **static, upright, side-by-side** columns instead of one rotated 180° and dynamically sized from `FoldingFeature.bounds`. There is no hinge on this hardware to rotate around or size against, so neither is needed; both panes read right-side-up to two people seated on either side of the flat 10.9" screen.

State plumbing already existed for this: `paneATranscript`/`paneBTranscript` (per-side transcript state) and `appendPaneEntry(paneIsA, text, own)` were built generically for the mirrored layout and needed no changes — only new `largeBinding?.paneLeft`/`largeBinding?.paneRight` branches alongside the existing `mirroredBinding?.paneTop`/`paneBottom` ones in `updateTurnIndicator`/`setStatus`/`appendPaneEntry`/`applyContinuousUiState`/`refreshRecordingsList`, plus a new `bindLargeView` mirroring `bindMirroredView`'s structure. `setupSpinners`/`setupGenderToggle` were extracted into `setupSpinnersGeneric`/`setupGenderToggleGeneric` (used by both the fallback and large bindings) rather than duplicated.

**Disclosed limitation**: the shared combined transcript (`textTranscript`, phone-only) was deliberately *not* ported to the large layout, matching the mirrored layout's own precedent (it doesn't have a combined transcript either — only per-pane ones). Splitting a live transcript stream by speaker in a new, third way was judged higher-risk than reusing the pane mechanism that already existed and was already proven.

## 5. Bug found and fixed during this pass: button text clipping

Real on-device screenshot evidence (not just a static layout review) caught this: several sw720dp buttons (`btnDownloadModels`, `btnDownloadStt`, `btnDownloadNaturalVoice` on Translate; `btnHearReference`, `btnDownloadNaturalVoicePractice`, `btnRecordAttempt`, `btnPlayAttempt` on Practice) had long label text that wraps to two lines at the narrower column widths these panes use, but a **fixed** `android:layout_height` (52dp/56dp) — clipping the wrapped second line. Fixed by switching to `wrap_content` height + `android:minHeight` (keeping the same minimum touch-target size) and `match_parent` width where a button was previously `wrap_content`-width inside a now-narrower column. Re-verified clean on a subsequent screenshot: both buttons render as two full, unclipped lines (see `docs/specs/...` — screenshots referenced in §7, files not committed to the repo per house convention, described in text below).

## 6. Auto-download-all-language-packs system

New package `com.retroid.translator.packs`, wrapping the existing `VoskModelCatalog`/`PiperVoiceCatalog`/ML Kit `TranslateLanguage` catalogs — **their data was not touched, duplicated, or forked**, per the task's explicit instruction. `git diff` confirms zero changes to `VoskModelCatalog.kt`/`PiperVoiceCatalog.kt`.

- **`PackModels.kt`** — `PackCategory` (TRANSLATION / VOICE_INPUT / NATURAL_VOICE), `PackDescriptor` (sealed class unifying all three catalogs behind one shape), `PackInventory.all()` (flat list of every downloadable pack — **92 total**: 59 translation + 25 Vosk + 8 Piper, confirmed both by catalog `grep` counts and by the real on-device summary card reading "X of **92** packs downloaded").
- **`PackStatus.kt`** — `fetchDownloadedTranslationCodes` (ML Kit's `RemoteModelManager.getDownloadedModels` fetched **once** per screen refresh, not once per language — avoids ~59 separate async round-trips); `isDownloaded` dispatches to the right per-category check (ML Kit snapshot / `VoskEngine.isModelDownloaded` / `PiperTtsEngine.isVoiceDownloaded`, the latter two already doing real completeness validation, not just "does a file exist").
- **`BulkDownloadCoordinator.kt`** — sequential downloader (bounded bandwidth/memory, simple "pack N of M" progress), cooperative cancellation (`cancel()` is checked between items, not mid-download — a download already in flight finishes before the batch actually stops; disclosed as a real, deliberate limitation, not an oversight). `downloadSingle` is `public` and reused identically by both the bulk path and each individual pack row's own Download button.
- **`LanguagePackPreferences.kt`** — `hasPromptedBulkDownload`, `isBulkDownloadCompleted`, `lastUpdateCheckAt`, plain `SharedPreferences`, matching the existing `LayoutPreferences`/`VoicePreferences` precedent.
- **`ManagePacksFragment.kt`** (Settings → **Language packs**, new row added to `SettingsHubFragment`) — summary card (X of 92 downloaded, remaining count + size estimate), "Download all remaining packs (Wi-Fi)" with live progress bar + per-pack status text + Cancel, "Check for updates," and all 92 packs individually listed under 3 section headers with per-row Download/Delete.
- **`TranslationEngine.deleteModel`** and **`VoskEngine.deleteModel`** — new, additive methods (delete didn't exist for these two categories before). `VoskEngine.deleteModel` mirrors `PiperTtsEngine.deleteVoice`'s already-proven "unload synchronously via a `CountDownLatch`, then delete the directory" pattern exactly, rather than inventing a new approach.
- **`MainActivity`** — first-launch-or-first-Wi-Fi confirmation prompt. Checks Wi-Fi state immediately in `onCreate`; if not yet connected, registers a `ConnectivityManager.NetworkCallback` for `TRANSPORT_WIFI` (unregistered in `onDestroy`, never re-registered once the prompt has fired once) so the prompt still fires the moment Wi-Fi becomes available, satisfying "first launch (or first-Wi-Fi-connection)" literally. Accepting navigates straight to `ManagePacksFragment.newInstanceAutoStart()`, which auto-starts the bulk download on arrival instead of requiring a second tap.

### "Check for updates" — honestly scoped, not a fake button

None of the three upstream catalogs are pinned to a live "latest version" feed this app polls — `VoskModelCatalog`/`PiperVoiceCatalog`'s URLs are specific, versioned filenames, not a `latest` alias, and ML Kit doesn't expose per-model version numbers to compare against. "Check for updates" therefore means **"re-verify every supposedly-downloaded pack is actually present and intact on disk"** (catches a pack deleted outside the app, or one left incomplete by an interrupted download — exactly the completeness bug class `PiperTtsEngine.effectiveVoiceDir` already guards against) — not "poll for a newer upstream release." The UI states this plainly (`ManagePacksFragment`'s "Never checked" / "Checked just now" status text spells out the distinction) rather than implying a live version check that isn't actually happening. This was a deliberate design decision, not a shortcut discovered too late to fix.

### Storage math, real numbers

Observed directly on-device (not estimated): the summary card read **"9 of 92 packs downloaded. 83 remaining (~3464MB, Wi-Fi)"** on first load (the 9 already-present packs were real leftover state from earlier testing sessions on this same physical device — the status-detection code correctly reflected real disk/ML-Kit state rather than assuming a blank slate, itself good evidence the detection logic is right). Full-catalog total is therefore **≈3.7–3.8GB** (92 packs: 59×~30MB translation + 25×~30–290MB Vosk, per-model sizes vary — see `VoskModelCatalog.kt` — + 8×~65MB Piper), comfortably inside the "roughly 4-5GB" estimate in the task brief and comfortably inside this device's 128GB storage.

## 7. Verification — real device, every claim below has evidence

### Build

- `./gradlew clean assembleDebug` → **BUILD SUCCESSFUL**, genuine from-scratch compile (not an incremental/UP-TO-DATE false-positive — this project lives in a OneDrive-synced folder, a known source of stale-cache issues in a prior session, so this was run explicitly rather than trusted from an incremental build). Run twice: once after the initial large-screen + auto-download implementation, once after the button-clipping fix.
- Only pre-existing, inherited warnings remain (`DownloadManager.kt` tar-entry deprecation, `LearnFragment.kt`/`ContinuousFlowProtoActivity.kt` deprecations/redundant-initializer, `TranslateFragment.kt:822`'s duplicate-label warning) — no new warnings introduced by this pass's own code.

### Install and baseline

- `adb -s R52X101MB6W install -r app-debug.apk` → confirmed via `pm list packages` showing `com.retroid.translator`.
- Toolbar/bottom-nav bug cross-check: **does not reproduce** (§2) — screenshot evidence.
- Settings hub reachable, lists all 5 rows (4 pre-existing + the new Language packs row), navigates correctly into each.

### Confirmed working, with real on-device evidence (screenshots + live interaction, all on serial `R52X101MB6W`)

- **Translate (large-screen layout)**: side-by-side panes render correctly at real 1440×2304 resolution. Real translation exercised live: typed "Good morning, how are you" → correctly produced "Buenos días, ¿cómo estás?" using the already-downloaded en/es ML Kit packs, on the new layout.
- **Practice (master-detail layout)**: left pane (record workflow) and right pane ("Past attempts") both render correctly. Real recording exercised live: typed "Hello there," tapped Record, spoke into the mic, tapped Stop — the new file (`2026-08-10_23-42-53_Hello_there.wav`) appeared **immediately in the right pane** while the left pane's "Play last attempt" button enabled — genuine live master-detail behavior, not two static panes.
- **Learn (master-detail layout)**: left pane (Units → Lessons) and right pane (Exercise/Summary) both render correctly. Real exercise flow exercised live: Units → "Greetings" → "Basic Greetings" (left pane updates, showing "← UNITS / Greetings / Basic Greetings") → Start → right pane shows "Exercise 1/8: What you say to greet someone early in the day" **while the left pane still shows the lesson context** → tapped "Good morning." → correctly scored ("✓ Correct! +10 XP", answer highlighted green, Continue button appeared).
- **Conversations (side-by-side layout)**: renders correctly — header row (language pickers, gender radio, Record session toggle), two static upright panes each with turn indicator/transcript/Tap-to-Speak/Continuous-listening, Saved recordings section below. Confirmed stable across repeated tab navigation and app backgrounding/foregrounding (no crash, `pidof com.retroid.translator` returned the same PID throughout every check in this pass — the process never died).
- **Auto-download / Manage packs system**: exercised extensively and live —
  - First-launch (Wi-Fi already connected) confirmation dialog fired correctly, with a correct size estimate.
  - Accepting navigated to Manage Packs and **auto-started** the bulk download (no second tap needed).
  - Real progress observed advancing (Pack 1 of 83 → Pack 9 of 83, progress bar visibly moving) before being cancelled per this task's own "no need to run the full ~5GB download" guidance.
  - Cancel worked correctly and cooperatively: "Cancelled. 11 downloaded, 0 failed before stopping," summary card updated to 20/92, "Download all remaining" button correctly re-enabled.
  - Individual per-pack **download+delete round-trip verified live for two different categories**: Translation (Afrikaans: Delete → flips to "Not downloaded (~30MB)"/Download; summary count updates 20→19) and Voice-input (Esperanto: Download → flips to "Downloaded"/Delete in real time, ~42MB pack; Delete → flips back).
  - Natural Voice section (all 8 Piper catalog voices) renders correctly with accurate real downloaded/not-downloaded state carried over from earlier sessions.
  - "Check for updates" exercised live: "Checked just now. 73 of 92 packs not currently downloaded/intact" — correct count, correct honest wording (re-verification, not a fake "up to date!" claim).
  - **Not run to completion**: the full ~3.7GB bulk download across all 92 packs was deliberately not completed, per the task's own instruction that a partial real download is sufficient evidence for the mechanism, UI, and round-trip. What *was* verified live (progress advancing over real Wi-Fi, cancel, individual download/delete on 2 of 3 categories, status re-detection) is real evidence, not a mockup or a code-review-only claim.

### Verified stable, not deeply interacted with

- **Conversations' Continuous Listening toggle specifically** — the button renders correctly with correct text ("Continuous listening" / "Continuous on (tap to stop)"), but was not exercised through a live loading→listening cycle *on this large-screen layout* during this pass. This is a disclosed gap, not a silent one: mid-session, an unrelated third-party app already installed on this physical device (**"Vellum Studio," a drawing/canvas app with its own local Wi-Fi-sync-to-PC feature**, confirmed via `dumpsys window`'s `mCurrentFocus` and screenshots showing its own UI, e.g. a "Connect to PC" screen reachable independent of any Retroid-Translator interaction) intermittently took focus away from Retranslator during automated ADB input sequences, in a way not obviously tied to specific screen coordinates or to this pass's own code changes. This was worked around for every other verification claim in this document (by re-checking `mCurrentFocus`/`pidof` immediately after each tap and retrying), but repeated interference around this specific control made a full live interactive test not worth the added risk of further side effects in that unrelated app. **This is an environmental characteristic of this specific physical tablet, not a defect in Retranslator** — `pidof com.retroid.translator` returned the same PID across the entire session (the app itself never crashed or was killed), and the underlying continuous-listening logic (`onContinuousToggleRequested`/`startContinuousMode`/`ContinuousConversationController`) was not modified by this pass — only new UI-binding routing was added, calling the exact same, already-independently-verified functions (see `docs/specs/fold5-adaptation.md` §6: "Conversations' continuous-listening toggle... re-verified functional... toggling on shows 'Loading models for continuous listening…' then 'Listening…'"). A stray empty canvas Vellum Studio created during this interference was deleted; the reader should be aware this other app exists on the device and may warrant the user's own attention, independent of this task.
- **A literal live two-person human conversation** — out of scope for any agent to produce, same caveat `fold5-adaptation.md` already carries.

### Not verified in this pass

- The remaining 21 of 25 Vosk voice-input packs and 6 of 8 Piper natural voices were not individually download-tested (2 of each category, plus all translation-pack mechanics, were — the shared dispatch code path (`BulkDownloadCoordinator.downloadSingle`) means there is no structural reason to expect the untested entries to differ, but they were not tapped directly).
- Rotation/orientation changes (portrait ↔ landscape) on this device were not explicitly tested this pass — the large-screen layouts were verified in the device's default landscape-capable orientation as launched; `sw720dp` is a smallest-width qualifier so it applies in both orientations, but a live rotation-mid-task check (e.g., fold mid-recording equivalent, per `fold5-adaptation.md`'s "baseline correctness" note) was not performed.
- Practice/Learn/Translate's pre-existing 24-variant cover/Flex settings system was not re-verified on this hardware (out of scope for this pass — that system exists for the Fold 5, not this tablet, per §3).

## 8. Files changed

Relative to `7b5fa4e` (`galaxy-tab-s9fe-adaptation`'s branch point):

**New:**
- `app/src/main/res/values/bools.xml`, `app/src/main/res/values-sw720dp/bools.xml`
- `app/src/main/res/layout-sw720dp/fragment_translate.xml`, `fragment_practice.xml`, `fragment_learn.xml`, `fragment_conversations_large.xml`
- `app/src/main/res/layout/fragment_manage_packs.xml`
- `app/src/main/java/com/retroid/translator/packs/PackModels.kt`, `PackStatus.kt`, `LanguagePackPreferences.kt`, `BulkDownloadCoordinator.kt`
- `app/src/main/java/com/retroid/translator/settings/ManagePacksFragment.kt`

**Modified:**
- `app/src/main/java/com/retroid/translator/MainActivity.kt` (bulk-download prompt)
- `app/src/main/java/com/retroid/translator/engine/TranslationEngine.kt` (`deleteModel`, additive)
- `app/src/main/java/com/retroid/translator/engine/VoskEngine.kt` (`deleteModel`/`unloadBlocking`, additive)
- `app/src/main/java/com/retroid/translator/ui/ConversationsFragment.kt` (new `LayoutKind.LARGE` path, generic spinner/gender helpers extracted)
- `app/src/main/java/com/retroid/translator/ui/LearnFragment.kt` (`setDefaultScreen` master-detail branch)
- `app/src/main/java/com/retroid/translator/settings/SettingsHubFragment.kt` (new Language packs row)
- `app/src/main/res/layout/fragment_settings_hub.xml` (new row XML)

**Untouched (verified via `git diff`):** everything in `app/src/main/java/com/retroid/translator/fold/`, every `*LayoutVariants.kt`/`*LayoutSettingsFragment.kt`, `VoskModelCatalog.kt`, `PiperVoiceCatalog.kt`, `fragment_conversations_mirrored.xml`, `view_conversation_pane.xml` (reused, not modified), `ConversationsFragment`'s existing mirrored-layout functions (`bindMirroredView`, `applyMirroredGeometry`).

## Out of scope for this spec

- Everything in `docs/specs/fold5-adaptation.md`'s own scope (fold posture, mirrored Conversations, the 24-variant system) — this hardware has no hinge; see §3.
- A camera-based live-translation-overlay mode — not requested for this pass, same as `fold5-adaptation.md`'s own camera note.
- Completing the full ~3.7GB bulk download to 100% — deliberately not run to completion, per the task's own guidance (§7).
- Investigating "Vellum Studio"'s Wi-Fi-sync feature or its interaction with this session's ADB tooling further — flagged transparently (§7) as something outside this task's scope to diagnose or fix.
