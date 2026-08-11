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
