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
