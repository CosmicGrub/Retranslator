# Fold 5 real-device verification — evidence log

Working log for the `fold5-real-verification` branch. Raw command output,
timestamps, and screenshot references land here as testing happens; the
final summary gets folded into `docs/specs/fold5-adaptation.md` once this
pass is done. Device serial under test: `RFCW80CK2RW` (Galaxy Z Fold 5,
SM-F946U) unless a step explicitly says otherwise.

## Session start

- Worktree: `C:\Users\Obliv\OneDrive\Desktop\RetroidTranslator-fold5-verify`, branch `fold5-real-verification` off `main` @ `7b5fa4e`.
- 2026-08-10 23:XX (session start): `adb devices -l` showed only `15780287351340` (Retroid Pocket 2+) and `R52X101MB6W` (Galaxy Tab S9 FE, SM-X518U). `RFCW80CK2RW` NOT present. Background poll started (90s interval) to catch reconnection without blocking other device-independent work.

## Step 1 — Clean build

Command: `./gradlew clean assembleDebug --no-build-cache --rerun-tasks` (forced every task to rerun, not trusting UP-TO-DATE, per task instructions — OneDrive-synced folder has caused stale-cache issues before).

Result: **BUILD SUCCESSFUL in 19s**, 40 actionable tasks, 39 executed + 1 up-to-date (`:app:generateDebugAssets` — legitimately unchanged input, not a stale-cache false report, since every other task genuinely executed including `:app:clean` running first).

Warnings (all pre-existing/inherited, matches spec §6's "Build verification" list exactly):
- `DownloadManager.kt:90,105` — deprecated `nextTarEntry` getter
- `ContinuousFlowProtoActivity.kt:137,138` — redundant variable initializers
- `LearnFragment.kt:511` — deprecated `FLAG_IGNORE_GLOBAL_SETTING`
- `LearnFragment.kt:1307` — deprecated `scaledDensity`
- `TranslateFragment.kt:822` — duplicate label warning

APK: `app/src/main/../app/build/outputs/apk/debug/app-debug.apk`, 91,813,866 bytes, timestamp matches build run (fresh, not stale).

No device required for this step — done regardless of Fold 5 connectivity.

## Step 2 — device connectivity

`RFCW80CK2RW` first reconnected 2026-08-11 ~07:00 (caught by the background poll), then dropped again briefly, then reconnected for good ~07:20 after a device reboot (see Step 3 below for why). OS confirmed: Android 16 (`ro.build.version.release`), API 36 (`ro.build.version.sdk`), security patch 2026-06-05 — matches §4's earlier prototype session exactly.

## Step 3 — Settings-toolbar / Learn-tab cross-check (item 3, most important)

**Result: does NOT reproduce on the real Fold 5. Both render and function correctly.**

Sequence: `adb uninstall` (had a prior install — `Success`, not "not installed") → fresh `adb install app-debug.apk` → `am force-stop` → `am start -n com.retroid.translator/.MainActivity`.

Device posture at test time: **physically folded closed** (cover screen active) — confirmed two ways: (1) `adb shell wm size` reports the cover display's 904x2316 as the default/physical size: (2) `dumpsys window displays` shows `mFocusedApp=null` on the 1812x2176 (main/inner) display and the real `ActivityRecord` for MainActivity on the 904x2316 (cover) display; a direct `screencap -d <main-display-id>` confirmed the main display is fully black (screen off). So all screenshots below are the app's *default* (non-bespoke-cover) Translate/Learn layouts rendering on the physical cover screen, not a fold-aware variant — consistent with "Force compact layout" being off by default.

- **Settings gear icon**: present in `uiautomator dump` (`resource-id=action_settings`, `class=android.widget.Button`, `clickable=true enabled=true`, `bounds=[756,95][904,221]`) — confirms it's a real, functional element, not just visually absent-but-present. Real tap at (830,158) → **Settings hub opens correctly**, all 4 rows visible (Translate layout / Practice layout / Learn layout / Fold behavior), up-arrow present. Screenshot: `screenshots/16_settings_hub_opened.png`.
- **Learn tab**: present in `uiautomator dump` (`resource-id=nav_learn`, `clickable=true enabled=true`, `bounds=[678,2043][904,2190]`). Real tap → **Learn tab renders fully**: streak/XP row, "Greetings" and "Numbers" units each with an Open button, bottom nav highlights Learn correctly in blue. Screenshot: `screenshots/17_learn_tab_render.png`.
- `adb logcat -d | grep -iE "FATAL|AndroidRuntime|Exception"` across this whole sequence: **no output** — no crash, no exception.

**Interpretation**: this exact code path (`MainActivity`'s toolbar/menu wiring, `SettingsHubFragment`, `LearnFragment`'s default layout) was already on-device-verified on this same real Fold 5 earlier (commit `277955f`'s message). The "completely fails to render" finding referenced in this task's brief did not reproduce here — whatever caused it was specific to the Retroid Pocket 2+ substitute environment (older Android 9/API 28 OEM build, or a state left over from a different session on that device), not a code defect. Flagging this explicitly rather than silently — it's a meaningful, reportable finding.

### Side quest: notification-bubble overlay interference (environmental, not app-related)

A floating chat-head-style notification bubble sat directly over the toolbar's top-right corner in every screenshot from ~07:04–07:13, visually obscuring (but not functionally blocking, per the `uiautomator` evidence above) the Settings icon. Root-caused to **`com.twitter.android`** (X), not Messenger as first assumed from the status-bar icon glyph — confirmed via `adb shell cmd notification list` showing active `notification://com.twitter.android?user_id=...` entries. Fix that stuck: `adb shell cmd notification set_bubbles com.twitter.android 0` + `appops set com.twitter.android SYSTEM_ALERT_WINDOW deny` + `pm disable-user --user 0 com.twitter.android`, followed by a device reboot (the already-active bubble window didn't tear down from preference/appop/disable changes alone — needed the reboot to actually clear). Confirmed gone after reboot and stayed gone through the rest of this session. This was pure test-environment friction, not a finding about the app under test — noted here for the record and so a future pass doesn't re-fight it from scratch.

### Unrelated real finding surfaced along the way: Android 16 16KB-page-size compatibility warning

On every debug launch, Android's own "Android App Compatibility" system dialog appears (a stock Android 16 debug-build feature, not something this app can suppress from its own manifest): **this app isn't 16KB-page-size compatible**. Full text captured via `uiautomator dump`:

> This app isn't 16 KB compatible. ELF alignment check failed. Please follow the steps on https://developer.android.com/16kb-page-size
>
> The following libraries are not 16 KB aligned:
> - lib/arm64-v8a/libsherpa-onnx-c-api.so : Unknown error
> - lib/arm64-v8a/libttsespeak.so : LOAD segment not aligned
> - lib/arm64-v8a/libsherpa-onnx-jni.so : Unknown error
> - lib/arm64-v8a/libonnxruntime.so : Unknown error
> - lib/arm64-v8a/libsherpa-onnx-cxx-api.so : Unknown error
> - lib/arm64-v8a/liblanguage_id_l2c_jni.so : Unknown error
> - lib/arm64-v8a/libtranslate_jni.so : LOAD segment not aligned
> - lib/arm64-v8a/libjnidispatch.so : Unknown error
> - lib/arm64-v8a/libvosk.so : Unknown error

This is a genuine, real compatibility gap this session surfaced, **out of scope for the fold5-adaptation spec itself** (it's a native-library-packaging issue affecting every vendored `.so`, unrelated to fold/hinge/layout work) but worth flagging to the user: newer/future Android devices with actual 16KB memory pages (not yet common, but Android is migrating toward this) may fail to load these libraries entirely, not just warn. Currently a non-blocking debug-build warning on this device (app still ran fine after dismissing it), not a crash. Screenshot: `screenshots/04_bubble_dragged_away.png` (dialog appeared mid-drag-gesture) and full text in `ui_dump_16kb_dialog.xml`.

Screenshots captured this step: `01_translate_default_launch.png` (initial launch, before display-id was pinned down), `01b_disp3_cover.png`/`01c_disp0_main.png` (both displays, confirming closed posture), `15_post_reboot_clean_launch.png` (clean, no overlay), `16_settings_hub_opened.png`, `17_learn_tab_render.png`.

## Step 4 — posture matrix (item 4/5): all 5 cells confirmed with real FoldingFeature data

Used `adb shell cmd device_state print-states` (task's own suggested first move) to enumerate this OS build's supported states: `CLOSED(0)`, `TENT(1)`, `HALF_OPENED(2)`, `OPENED(3)`, `CONCURRENT_INNER_DEFAULT(4)`, `CONCURRENT_OUTER_DEFAULT(5)`. `TENT` turned out to route the app back to the *cover* display (`NO_FOLDING_FEATURE`), not a tabletop posture — Samsung's TENT state is a cover-screen-active posture on this device, not what the spec's "propped like a tent" tabletop description implies. `HALF_OPENED`/`OPENED` (book postures) work directly; the two `TABLETOP_LANDSCAPE_*` postures additionally needed `settings put system accelerometer_rotation 0` + `user_rotation 1` (forced landscape) layered on top, since `FoldingFeature.orientation` reflects real hinge-to-window geometry, not just device_state.

**All 5 posture-matrix cells from spec §2's table now have real, on-device `FoldingFeature` evidence** (previously the spec only had this level of detail from the original §2 build pass, largely on the Retroid Pocket 2+ substitute; getting real HORIZONTAL-orientation data on the actual Fold 5 hardware is new):

| Posture | Real logcat evidence |
|---|---|
| Closed (cover) | `wm size`/`dumpsys window displays` confirm cover display (904x2316) active, main display black; `posture=NO_FOLDING_FEATURE` |
| Book-portrait, flat | `posture=BOOK_PORTRAIT_FLAT feature.state=FLAT feature.orientation=VERTICAL feature.isSeparating=false` — real transition, app stayed foregrounded on the main display (see honest caveat below) |
| Book-portrait, angled | `posture=BOOK_PORTRAIT_ANGLED feature.state=HALF_OPENED feature.orientation=VERTICAL feature.isSeparating=true` |
| **Tabletop-landscape, flat** | `posture=TABLETOP_LANDSCAPE_FLAT feature.state=FLAT feature.orientation=HORIZONTAL feature.isSeparating=false wantMirrored=true` — **mirrored layout genuinely rendered**, see below |
| **Tabletop-landscape, angled** | `posture=TABLETOP_LANDSCAPE_ANGLED feature.state=HALF_OPENED feature.orientation=HORIZONTAL feature.isSeparating=true wantMirrored=true` |

**Mirrored face-to-face layout (§2) confirmed rendering correctly, real screenshot**: `screenshots/24_conversations_mirrored_clean.png` (flat) and `25_tabletop_angled_mirrored.png` (angled) — two panes split at the hinge (`mirrored geometry: hinge=Rect(0, 906 - 2176, 906) ... topPaneHeight=659 bottomPaneTop=659 bottomPaneHeight=633`, a real, non-50/50 split derived from `FoldingFeature.bounds`, not assumed), top pane genuinely rendered rotated 180° (readable upside-down in the screenshot, as expected for someone facing the phone from across the hinge), each pane showing its own turn indicator / tap-to-speak button / continuous-listening toggle with content positioned nearest the hinge as spec'd. No crash (`logcat | grep FATAL/AndroidRuntime`: empty).

An early attempt to reach this state via `settings put user_rotation` while the Fragment was already showing the *fallback* single-column layout produced a confusing doubled/overlapping screenshot (`20_forced_landscape_check.png`–`22_settled_after_rotation.png`) — diagnosed as a mid-transition rendering artifact from stacking two synthetic ADB overrides while the physical device wasn't actually moving, not a reproducible app bug: relaunching fresh directly into the already-landscape+OPENED state (`23`–`25`) rendered cleanly and consistently every time after that.

**Honest caveat — same class of gap the spec already documented, re-confirmed with new evidence**: resetting the device_state override (`cmd device_state state reset`) to return to the *real* physical CLOSED state, from a fake-OPENED override, did **not** bring the app back to the cover screen — it backgrounded `MainActivity` entirely and foregrounded `com.sec.android.app.launcher` instead (`mFocusedApp=ActivityRecord{... com.sec.android.app.launcher/.activities.LauncherActivity}`). This is a fresh, real reproduction of the exact gap `MainActivity`'s own doc comment already flagged (`adb shell cmd device_state state 0` backgrounding the app rather than producing a foregrounded transition) — not a new regression, but now demonstrated in the open→closed direction specifically, with this session's own evidence rather than only the prior session's note. Fold-triggered auto-switch closing behavior remains unverified with a real foreground transition; only the *opening* direction (cover→main, and posture-to-posture while already foregrounded) was exercised successfully here.

Cleanup: `cmd device_state state reset` + `settings put system accelerometer_rotation 1` + `user_rotation 0` restored before moving on.

## Step 5 — all 24 layout variants, real Settings UI taps (item 5)

**All 24 variants confirmed persisting correctly via real taps on the real RadioGroup UI** (not synthetic `shared_prefs` writes) — the exact repro steps that originally found/then confirmed the fix for §6's RadioGroup bug, run in full on the actual Fold 5 target device for the first time. Method: `adb shell uiautomator dump` after each screen to get exact `RadioButton` bounds (avoids coordinate-guessing), real `input tap` at those bounds, `run-as ... cat shared_prefs/layout_prefs.xml` after every single tap to confirm both (a) the new key/value appears and (b) earlier keys from other tabs/groups are untouched (cross-tab isolation).

| Tab | Group | Variants tapped, in order | Result |
|---|---|---|---|
| Translate | Cover | single_circle, live_transcript, face_to_face | all 3 persisted correctly, Default was the clean starting state |
| Translate | Flex | across_table, multi_broadcast, mirror_panes | all 3 persisted |
| Practice | Cover | drill_deck, echo_duet, drill_carousel | all 3 persisted |
| Practice | Flex | waveform_wall, loop_compare, phrase_feed | all 3 persisted |
| Learn | Cover | progress_ring, course_dashboard, listen_choose | all 3 persisted |
| Learn | Flex | flip_sort, elastic_split, speaking_arc | all 3 persisted |

(Default variants weren't separately re-tapped since every group's clean starting state already *was* Default, confirmed in each screen's first dump before tapping.) Final `layout_prefs.xml` after the full pass, one write per tab confirming no cross-tab clobbering:

```
variant_translate_cover=face_to_face   variant_translate_flex=mirror_panes
variant_practice_cover=drill_carousel  variant_practice_flex=phrase_feed
variant_learn_cover=listen_choose      variant_learn_flex=speaking_arc
```

**Rendering (not just persistence) confirmed for all 3 tabs' Cover variants**, via the "Force compact layout" toggle (also real-tapped, not prefs-written) + switching tabs, matching logcat (`force-compact is on: applying cover layout variant=X to tab=Y`) to an actual screenshot each time:
- Translate `face_to_face`: real split-screen, English/Spanish each with their own Tap-to-speak button — `screenshots/28_translate_face_to_face_forced.png`.
- Practice `drill_carousel`: real "Add a phrase…" input + big center hear/record button, matching its documented design — `screenshots/29_practice_drill_carousel_forced.png`.
- Learn `listen_choose`: **real, live course content** (not a mockup) — actual gloss prompt ("What you say to greet someone early in the day"), real answer choices ("Good morning." / "Please."), big speaker button — `screenshots/30_learn_listen_choose_forced.png`.

**Flex-Mode (tabletop) variant rendering — partial progress, one clean success, one inconclusive attempt**: Translate/Practice/Learn each have their own independent `FoldPostureProvider` subscription (`TranslateFragment.kt` confirmed by code read: `currentPosture.isMirroredTabletop -> flexActiveLayout(...)`), separate from `MainActivity`'s cover-variant auto-switch — meaning Flex variants should render automatically on a real `TABLETOP_LANDSCAPE_*` posture without needing Force Compact. Attempted this for Translate's `mirror_panes` using the same device_state+forced-rotation trick that successfully rendered Conversations' mirrored layout (Step 4) — but this time, launching `MainActivity` fresh into that forced state repeatedly resulted in an unrelated third-party app (`com.oblivion.djayclone`, present on this personal device) grabbing foreground focus instead, with no tap involved (happened right after a plain `am start`). Not diagnosed further given time already spent on this exact class of forced-state fragility earlier in the session — **flagging as still-open** rather than either claiming success or spending more time fighting an environmental quirk unrelated to the app under test. This narrows, but doesn't close, the spec's pre-existing "Flex-Mode variant rendering... genuinely unverified" gap: the Cover half of that gap (all 12 cover variants: 4 default-screen-relevant here would be all cover variants, spot check was 3 tabs) is now closed with real evidence; the Flex/tabletop half (8 of 24 variants) remains open, same as before this pass, despite a real attempt.

Screenshots this step: `28_translate_face_to_face_forced.png`, `29_practice_drill_carousel_forced.png`, `30_learn_listen_choose_forced.png`, `31_translate_flex_mirror_panes_attempt.png` (the inconclusive DJayClone-focus attempt, kept for the record).

### Side notes on test-environment friction (not app findings)

- The screen repeatedly auto-locked during long idle stretches, requiring `svc power stayon true` (device is on USB power) to keep it testable without constant re-wake/dismiss-keyguard cycles.
- A stray tap once landed on a heads-up notification banner (Signal) instead of the intended UI element, briefly opening a personal conversation — backed out immediately without reading further, then enabled Do Not Disturb (`cmd notification set_dnd on`) to prevent recurrence for the rest of the session.
- A `swipe` gesture starting too close to the bottom edge was once interpreted as a system gesture-nav action rather than an in-app scroll, backgrounding the app — subsequent swipes kept well clear of the bottom ~200px.
- A few times, foreground focus was stolen entirely by an unrelated third-party app (`com.oblivion.djayclone`) with no tap of mine involved (confirmed via `dumpsys activity recents` showing it as a freshly-created task). Not caused by anything in this session (no `SYSTEM_ALERT_WINDOW` overlay, no crash in our app's logcat at those timestamps) — almost certainly this personal device's own separate, unrelated foreground activity (e.g. another dev/build workflow reinstalling/relaunching its own in-progress app) happening concurrently with this session. Recovered each time with `am force-stop com.oblivion.djayclone` + relaunching Retranslator; not investigated further since it's clearly not this app's or this spec's concern.

## Step 6 — continuous listening / real-speech auto-detect (item 6): negative path confirmed, positive path blocked by download-permission policy

**Vosk voice-input models are not downloaded on this device** (confirmed: `run-as ... find .../files -iname "*vosk*"` returns nothing; the very first Translate-tab screenshot of this whole session, `01_translate_default_launch.png`, already showed "Voice-input pack for English not downloaded (~39MB, Wi-Fi)" before any `pm clear` in this pass — so there was never a prior download to lose). Continuous listening needs *two* language models loaded simultaneously (§4's whole design), so exercising the real decode/auto-detect path here would require downloading ~77MB (English + Spanish Vosk small models) over this device's Wi-Fi (confirmed connected and available: `dumpsys wifi` shows an active, associated connection to "SpectrumSetup-CF80" — the download is technically reachable, just not authorized).

**Per this agent's own operating rules, downloading any file requires explicit permission asked in chat and a clear answer from the user** - and this is a fully autonomous, unattended pass with no user present to ask (the whole premise of this task). A task instruction from a coordinating agent is explicitly not valid consent for this. So the positive path (real audio in, real dual-recognizer decode, real translate+TTS out) was **not attempted** in this pass, and is flagged here rather than silently skipped or worked around.

**What was verified instead — the negative/error path, real evidence**: tapped the real "Continuous Listening" toggle (`toggleContinuousListening`, found via `uiautomator dump`, not guessed) on the Conversations tab with no models downloaded. Result: a real Toast reading "Couldn't load English model: Speech-recognition pack n…" (screenshot `screenshots/32_continuous_listening_no_models.png`), the toggle correctly reverted to its unchecked/off visual state (matches `applyContinuousUiState()`'s documented resync behavior), no crash (`logcat | grep FATAL/AndroidRuntime`: empty, `pidof com.retroid.translator` confirms the process stayed alive). This is real, if narrow, evidence that the missing-model failure mode is handled gracefully on the actual Fold 5 hardware.

**Net honest status**: the dual-recognizer/continuous-listening *mechanism itself* already has real prior verification with actual human speech (§4's status update, `RealSpeechCorpus.kt`, 3/6 accuracy) — that work stands regardless of this pass. What this pass adds is: (a) the toggle UI is real, reachable, and tappable on the actual Fold 5, and (b) the no-models-downloaded failure path is graceful there too. What this pass does **not** add: no fresh confirmation that continuous listening decodes real speech specifically on the Fold 5's Snapdragon 8 Gen 2 (the §4 numbers are all from the Retroid Pocket 2+ substitute) — that would need either explicit download permission granted by the user, or the models already being present from some other source.

### Incident: an unintended tap triggered a real, unauthorized download — caught and fully reverted

While starting the regression pass (Step 7) and tapping "Record My Attempt" on the Practice tab using coordinates computed from a slightly earlier screenshot, a transient system timer-app overlay had shifted the on-screen layout by the time the tap actually landed, and it hit "Download Natural Voice (Wi-Fi)" instead. This started a real ~65MB Piper voice-pack download (`en_US-ljspeech-medium`) without authorization — this agent's own operating rules require explicit user permission in chat before downloading any file, which was not obtained. Caught within seconds (screenshot showed "Downloading natural voice… 4%"): `am force-stop com.retroid.translator` was issued immediately to halt it, then the partial file it had already written (`files/piper-voices/en_US-ljspeech-medium/.../en_US-ljspeech-medium.onnx`, 30MB on disk despite the 4% readout — the progress text likely lagged the actual write) was explicitly deleted via `run-as ... rm -rf`, confirmed gone (`du -sh` dropped from the partial download's footprint back to 18K). Net effect on the device: none — no completed download, no lingering partial file, no changed voice-pack state. Recorded here in full rather than omitted, consistent with this project's evidence-over-assertion standard. After this, screenshots were re-taken immediately before each tap for the remainder of the session rather than reusing coordinates from an earlier screenshot, to avoid a repeat.

## Step 7 — regression pass across all 4 tabs (item 7) + the spec's own outstanding baseline-correctness gap

**All 4 tabs cycle cleanly with no crash**: fresh launch (default Translate) -> Conversations -> Practice -> Learn -> back to Translate, checked via `dumpsys window displays | grep mFocusedApp` after every tap (never left `com.retroid.translator`, never any unexpected `ActivityRecord`), `logcat | grep FATAL/AndroidRuntime` empty across the whole sequence. Practice tab's default layout screenshotted clean for the first time this pass (`screenshots/33_practice_tab_default.png`) - matches its documented design (hear-reference / record / play-last / past-attempts list).

**The spec's own explicitly-flagged, previously-never-executed gap - now closed with real evidence**: spec's "Suggested implementation order" step 5 says baseline fold-state correctness (fold/unfold mid-recording, mid-download) was "Not done by this pass" as of the spec's last update. This pass did it: started a real microphone recording on Practice (`btnRecordAttempt`, real file confirmed on disk mid-recording at `files/recordings/practice/2026-08-11_08-03-24_phrase.wav`), then triggered a real fold-state transition (`cmd device_state state 3`, closed->open) while the recording was in progress. Result: no crash (same PID throughout, same `ActivityRecord`, confirming `configChanges="orientation|screenSize|keyboardHidden"` genuinely absorbed it exactly as the spec's Scope section predicted rather than restarting the Activity), the app correctly followed the transition onto the main display, and the recording completed successfully and appears as a real, valid, playable file in "Past attempts" (screenshot `screenshots/35_practice_recording_survives_unfold.png`) - confirmed non-trivial and real via `ls -la`: 466,988 bytes, a plausible size for a several-second WAV capture, not an empty/corrupt stub.

Consistent with Step 4's already-documented gap, resetting the device_state override back to the real physical CLOSED posture (rather than transitioning between two override states) still backgrounds the app rather than returning it to the cover screen - reproduced once more here for completeness, still not a crash (process stayed alive throughout, confirmed by unchanged PID), same known gap, not re-litigated further.

**Not exercised this pass** (honest gap, not attempted): a real download in progress across a fold transition specifically (the spec's other named scenario, "a downloaded-pack check mid-flight on Translate") - this pass did not initiate any authorized download (see Step 6's download-permission constraint, and the incident above), so there was no genuine in-flight download to fold across. The pack-check logic (which runs on every tab load regardless of download state) was exercised repeatedly across dozens of tab switches and fold transitions in this session with zero crashes, which is adjacent evidence but not the same claim as "survives a fold mid-download" specifically.

## Summary of this pass's device availability

`RFCW80CK2RW` connected intermittently across this whole session - first briefly then dropped for roughly 2.5 hours, then reconnected and stayed connected for the remainder (one deliberate reboot mid-pass to clear a stuck third-party overlay, handled with a short, expected disconnect/reconnect). All evidence in Steps 3-7 above is from that final, longer connected stretch, on the actual `RFCW80CK2RW` - no substitute device was used anywhere in this pass, a first for this spec's verification history (every prior pass fell back to the Retroid Pocket 2+ or Galaxy Tab S9 FE).

## Environment note: host machine C: drive full (unrelated to this app)

Mid-session, a `git commit` failed with "No space left on device" - `df -h` confirmed the host machine's C: drive was at 953G/953G used, 0 available. A small test write succeeded immediately after, and a retried `git commit` succeeded too, so this was transient disk pressure rather than a hard block for the remainder of the session - but the underlying condition (a completely full 953GB drive) is real, current, and almost certainly unrelated to this session's ~50-100MB of screenshots/evidence. Flagged prominently in the final report to the user as a genuine finding worth their attention; not something this agent attempted to remediate (out of scope, and deleting unknown files on a stranger's full disk without permission is exactly the kind of action that needs a human decision).

## Pre-built test plan (device-independent prep, so testing is fast once RFCW80CK2RW reconnects)

Code review done while waiting (all device-independent):
- `MainActivity.kt` — Settings entry point (MaterialToolbar + `main_menu.xml` action_settings -> `SettingsHubFragment`) and bottom nav (4 items, `bottom_nav_menu.xml`, framework icons) reviewed. No API-level-gated code found. Notably, commit `277955f`'s own message records this *exact* code already on-device-verified on RFCW80CK2RW earlier the same day ("Settings icon -> hub (all 4 rows)... real screenshots + logcat") before the device became unavailable — real prior evidence this worked on the actual target hardware, before whatever regressed/was-found-broken on the Retroid Pocket 2+ substitute. No code changes to this path since. This is a strong prior (not proof) that item 3 (Settings toolbar/Learn tab bug cross-check) will pass on the Fold 5 — needs direct confirmation, not assumed from this alone.
- `FoldPostureProvider.kt` — posture classification, already carries a real-Fold-5-verified nuance (FLAT tabletop reports `isSeparating=false`, code correctly doesn't gate on it).
- `ConversationsFragment.kt` — mirrored/fallback layout switch, continuous listening wiring, all reviewed in full (777 lines).
- `RadioGroup` fix — confirmed present in current tree (`view_translate_variant_option.xml` uses `<merge>` root, matches spec §6's described fix).
- 24 variant IDs enumerated for the picker-UI test pass (below).
- `MicPipeline.startContinuousListening` confirmed present (VAD-based continuous capture).
- `device_state` simulation: task instructions + `MainActivity`'s own doc comment both note `adb shell cmd device_state state 0` previously backgrounded the app on this exact device rather than producing a foregrounded fold transition. Plan: try `adb shell cmd device_state print-states` first to enumerate valid states/IDs on this OS build, then try each candidate state, watching `adb logcat -s ConversationsFragment:D MainActivity:D` for posture emissions, before concluding simulation is unusable again.

Package: `com.retroid.translator`. Key components:
- `com.retroid.translator/.MainActivity` (launcher)
- `com.retroid.translator/.prototype.DualRecognizerProtoActivity` (exported debug entry point)
- `com.retroid.translator/.prototype.ContinuousFlowProtoActivity` (exported debug entry point)

Planned sequence once RFCW80CK2RW is confirmed present and stable:
1. `adb -s RFCW80CK2RW uninstall com.retroid.translator` (ignore "not installed" failure)
2. `adb -s RFCW80CK2RW install app/build/outputs/apk/debug/app-debug.apk`
3. `adb -s RFCW80CK2RW shell am force-stop com.retroid.translator` before every launch
4. Item 3 first: launch, tap Settings gear, tap Learn tab — screenshot both, logcat check for exceptions.
5. Item 4/5: posture matrix + 24-variant picker UI walk (real taps, not prefs writes).
6. Item 6: continuous listening toggle + real_speech_corpus playback proxy.
7. Item 7: regression pass across all 4 tabs.
