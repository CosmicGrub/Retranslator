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

(updated live as polling continues)

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
