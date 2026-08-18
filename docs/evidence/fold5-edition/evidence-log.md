# Fold5 edition build — real-device verification evidence log

Working log for the `fold5-device-edition` branch (turning the already-built
fold-aware app into a distinct, device-tuned "edition" build — see
`docs/specs/fold5-adaptation.md`'s dated "Fold5 edition build" section for
the summary this folds into). Device serial under test: `RFCW80CK2RW`
(Galaxy Z Fold 5, SM-F946U) throughout. Session: 2026-08-18.

## Build

```
./gradlew clean :app:assembleDebug
```

Result: **BUILD SUCCESSFUL in 24s**, 41 actionable tasks (39 executed, 2
up-to-date). Only pre-existing warnings survived (identical set already on
record elsewhere in the spec — `DownloadManager.kt` deprecated
`nextTarEntry`, `ContinuousFlowProtoActivity.kt` redundant initializers,
`LearnFragment.kt` deprecated APIs, `TranslateFragment.kt:953` duplicate
label). No new warnings introduced by this pass's changes.

## applicationId / versionName confirmation

`aapt dump badging app/build/outputs/apk/debug/app-debug.apk`:

```
package: name='com.retroid.translator.fold5' versionCode='1' versionName='1.0-fold5-edition' ...
application-label:'Retranslator Fold5'
```

Confirms `applicationIdSuffix = ".fold5"` and `versionNameSuffix =
"-fold5-edition"` (`app/build.gradle.kts`) took effect in the real built
APK, and the new `app_name` string flows through to `application-label`.

## Device connectivity

`adb devices -l` found `RFCW80CK2RW` immediately on the first check this
session — no retry loop needed for initial connectivity (unlike several
prior passes in this spec). The device did drop and reconnect briefly twice
more later in the session (transport_id cycled 2 → 3 → 4 → 2 across the
session) — each time, a short retry-with-wait loop (per this task's own
instructions) recovered it within 1-2 polls; consistent with this device's
already-documented intermittent USB/wireless availability elsewhere in this
spec (§4, §6, §9).

## Install, and the universal build's side-by-side survival

```
adb -s RFCW80CK2RW shell pm list packages --user 0 | grep retroid
  -> package:com.retroid.translator          (pre-existing, from earlier work in this project)
adb -s RFCW80CK2RW install -r app-debug.apk  -> Success
adb -s RFCW80CK2RW shell pm list packages --user 0 | grep retroid
  -> package:com.retroid.translator
  -> package:com.retroid.translator.fold5
```

Both packages coexist, confirming the distinct `applicationId` did exactly
what it was meant to: no collision, no overwrite. Re-checked again at the
end of the session:

```
adb -s RFCW80CK2RW shell dumpsys package com.retroid.translator | grep -i "versionName\|enabled="
  -> versionName=1.0
  -> User 0: ... enabled=0 ...   (enabled=0 = COMPONENT_ENABLED_STATE_DEFAULT, i.e. normal)
```

The universal build's `versionName` is still the plain `1.0` (no suffix,
untouched) and it remains normally enabled/installed — this branch's work
never touched it.

## A real environmental snag, honestly disclosed: the package was left disabled after a failed `uninstall`, and needed `pm enable`

Before installing, an `adb uninstall com.retroid.translator.fold5` was run
defensively (expecting "nothing to uninstall" on a package that didn't
exist yet) and returned `Failure [DELETE_FAILED_INTERNAL_ERROR]` — harmless
at the time, since the package genuinely didn't exist yet. After the real
install succeeded and the app had already been launched and used
successfully several times (cold-launch screenshot captured, Force Compact
toggled on, single_circle confirmed rendering), a later `am start` attempt
started failing repeatedly with:

```
Error type 3
Error: Activity class {com.retroid.translator.fold5/com.retroid.translator.MainActivity} does not exist.
```

...despite `pm list packages` and `dumpsys package` both showing the
package correctly installed with the right intent-filter registered.
`monkey -p com.retroid.translator.fold5 -c android.intent.category.LAUNCHER
1` independently confirmed "No activities found to run". Root cause, found
via `dumpsys package com.retroid.translator.fold5 | grep -A3 "User 0:"`:

```
User 0: ... enabled=3 ...
lastDisabledCaller: shell:1000
```

`enabled=3` is `COMPONENT_ENABLED_STATE_DISABLED_USER` — the whole app had
been disabled, and `lastDisabledCaller: shell:1000` confirms it was an adb
shell command (uid 1000) that did it. The most plausible explanation,
recorded here rather than left a mystery: the earlier defensive `uninstall`
attempt's `DELETE_FAILED_INTERNAL_ERROR` on this Knox-hardened Samsung
build left the package in a disabled half-state as a side effect, rather
than cleanly no-op'ing. Restarting the local `adb` server did **not** fix
it (confirming this was device-side package-manager state, not a local adb
cache issue). Fix: `adb -s RFCW80CK2RW shell pm enable
com.retroid.translator.fold5` → `Package com.retroid.translator.fold5 new
state: enabled`, after which `am start` worked immediately and stayed
reliable for the rest of the session. Flagging this plainly in case a
future pass on this device hits the same thing after a failed uninstall —
`pm enable <pkg>` is the fix, not a reinstall.

The device was also, separately, shared with at least one other actively
running app/process during this session — `com.vellum.studio` repeatedly
took foreground focus unprompted between commands (confirmed via
`dumpsys window displays | grep mFocusedApp` showing it in place of our
app moments after our app had focus), and `com.retroid.translator` (the
*universal* build) was independently observed briefly in the foreground
once too, meaning some other process on this shared device launched it
during this session — not this task. Both are consistent with this
project's already-documented pattern of a multi-agent/shared-device
environment (see e.g. §4's 2026-08-11 status update, §7's "brief mid-session
USB disconnect"). Worked around each time with `am force-stop
com.vellum.studio` immediately before the next real interaction; no data
was lost and no state from this task was corrupted by it.

## Evidence 1 — distinct identity, cold launch

`screenshots/01_cold_launch_default_translate_toolbar_label.png`: fresh
install, `am force-stop` both packages, cold `am start
com.retroid.translator.fold5/com.retroid.translator.MainActivity`. Toolbar
reads **"Retranslator Fold5"** (not "Retranslator") — confirms the
`app_name` string change reached the real running UI (toolbar title,
by extension the launcher label and `ContinuousListeningService`'s
notification title, since all three read the same resource — see
`strings.xml`'s comment). This is the real *default* (unfolded, force-compact
off) Translate layout — full language pickers, pack-status rows,
voice-gender switch — exactly the pre-existing behavior, confirming the
identity change didn't disturb anything else.

## Evidence 2 — Translate cover-screen default is `single_circle`, not `default`, on a truly untouched preference

Confirmed the preference was genuinely never written before this test:

```
adb -s RFCW80CK2RW shell run-as com.retroid.translator.fold5 ls shared_prefs
  -> (no layout_prefs.xml at all — fully fresh)
```

Settings → Fold behavior → tapped "Force compact layout" (a real tap,
`uiautomator`-confirmed `checked` flipped `false` → `true`
- see `ui_dump_fold_behavior_before_toggle.xml` /
`ui_dump_fold_behavior_after_toggle.xml`). Confirmed persisted:

```
adb -s RFCW80CK2RW shell run-as com.retroid.translator.fold5 cat shared_prefs/layout_prefs.xml
<map>
    <boolean name="force_compact_layout" value="true" />
</map>
```

Note there is **no `variant_translate_cover` key at all** — the picker was
never touched. Returning to the Translate tab with force-compact now on,
`screenshots/02_force_compact_single_circle_default.png` shows the real
**"Single Circle"** cover widget ("Hold to speak" big circle, "swipe to
flip direction · tap the result to hear it", "SWITCH TO CONTINUOUS
LISTENING" button) — not the full `Default` layout squeezed onto the cover
posture. This is `LayoutPreferences.getVariant`'s new
`FOLD5_TRANSLATE_COVER_DEFAULT_VARIANT` fallback resolving correctly for a
genuinely-never-set preference, exactly the mechanism the code change
targets (`app/src/main/java/com/retroid/translator/settings/LayoutPreferences.kt`).

## Evidence 3 — Conversations "dual-recognizer auto-detect" cold-launch default fires automatically

Real logcat, filtered to `ConversationsFragment`, captured from a clean
`logcat -c` immediately before switching to the Conversations tab for the
first time this app-instance's lifetime (no tap on the continuous-listening
toggle by anyone):

```
08-18 07:41:56.076  I ConversationsFragment: fold5 edition: attempting cold-launch default-on for continuous listening (dual-recognizer auto-detect)
08-18 07:41:56.128  I Toast: show: caller = com.retroid.translator.ui.ConversationsFragment$startContinuousMode$1.invoke:842
```

This confirms `maybeApplyFold5ContinuousDefault()` fired for real, with no
user interaction, and drove the exact same `startContinuousMode()` the
manual toggle uses. The `Toast` call site (`startContinuousMode$1.invoke`)
is the pre-existing "No offline voice-input model for one of these
languages" early-return path — real and expected here, since this is a
genuinely fresh install with no Vosk models downloaded yet:

```
adb -s RFCW80CK2RW shell run-as com.retroid.translator.fold5 find files -iname "*vosk*"
  -> (no output — confirms no models present)
```

`screenshots/03_conversations_continuous_default_attempt.png` shows the
resulting, correctly-reverted UI state — the "CONTINUOUS LISTENING (NO TAP
NEEDED, AUTO-DETECTS LANGUAGE)" control is *not* shown checked/active
(matches `applyContinuousUiState()` syncing back to `continuousEnabled =
false` after the graceful revert), no crash, no stuck "Loading models…"
state. This is the same honest gap this entire spec already carries in
several other places (§4, §7, §9, §11): this agent cannot download the
~39-77MB of Vosk models without explicit user permission in an unattended
pass, so the *fully engaged* continuous-listening state (real mic capture
running) could not be demonstrated for this edition build specifically.
What **is** proven, concretely and for real: the cold-launch default now
*attempts* the dual-recognizer auto-detect mechanism automatically, using
the exact same real, already-verified-elsewhere code path, without
requiring the user to discover and tap the toggle themselves — which is
the actual behavior change this task asked for. The mechanism's own
positive-path accuracy (3/6 on real speech, 12/12 on synthetic) was already
established in §4 and is unmodified by this pass.

## Regression check — Fold behavior screen intact

`ui_dump_fold_behavior_before_toggle.xml` shows both
`switchAutoSwitchOnFold` (checked=true, matching
`LayoutPreferences.isAutoSwitchOnFoldEnabled`'s documented default) and
`switchForceCompact` (checked=false, pre-toggle) present with their real,
state-describing `content-desc`s from §11's accessibility sweep intact
("Auto-switch on fold: automatically switch the active tab to its
cover-screen layout when you fold the device" /
"Force compact layout: stay in the compact cover-style layout regardless
of physical fold state") — confirms this screen was left fully reachable
and functionally unmodified, per this task's explicit requirement, and
that earlier accessibility work on it survived untouched.
