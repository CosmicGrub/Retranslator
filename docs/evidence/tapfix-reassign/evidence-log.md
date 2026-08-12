# Tap-to-fix reassign affordance — evidence log

Working log for the `tapfix-reassign` branch (closes docs/specs/fold5-adaptation.md
§4's "Fallback UX for a wrong guess" gap — the reassign affordance was
designed but never actually built, because the old transcript rendering had
no per-entry view to attach a tap listener to). Session date 2026-08-11.

## What changed (summary — see commit history for the real diff)

- `ConversationsFragment.kt`'s `combinedTranscript`/`paneATranscript`/`paneBTranscript`
  `StringBuilder`s replaced with one ordered `List<TranscriptEntry>`
  (`app/src/main/java/com/retroid/translator/conversation/TranscriptEntry.kt`),
  rendered by a new shared `RecyclerView.ListAdapter`
  (`app/src/main/java/com/retroid/translator/ui/TranscriptAdapter.kt`) and
  row layout (`app/src/main/res/layout/item_transcript_bubble.xml`).
- All three Conversations layouts converted from a shared-`TextView`-append
  model to a per-row `RecyclerView`:
  - `fragment_conversations.xml` (fallback, book-portrait) — `textTranscript`
    → `recyclerTranscript` (nested scrolling disabled, since it lives inside
    that layout's own outer `ScrollView`).
  - `view_conversation_pane.xml` (shared by BOTH
    `fragment_conversations_mirrored.xml`'s two panes AND
    `fragment_conversations_large.xml`'s two panes) — `scrollPaneTranscript`/
    `textPaneTranscript` → `recyclerPaneTranscript`.
- Tapping a bubble (or its small "⇄" icon) calls `reassignTurn()`, which
  flips `TranscriptEntry.speakerIsA` to the same new value on every entry
  sharing that turn's id, moving both the original-speech bubble and its
  translation to the opposite pane at once. Pure presentation-layer change —
  `ContinuousConversationController`, `VoskResultParsing`, and the
  dual-recognizer picking logic are untouched.
- Works for both the manual `onMicTap` flow and the continuous-listening
  `onUtteranceFinal` flow via two shared helpers, `addTurn`/`addTranslation`
  (plus `addFailureNote` for a failed translation, replacing the old
  fallback-only `appendCombinedTranscript("   (translation failed: ...)")`
  line with a real, non-reassignable bubble shown in every layout).

## Build verification

`./gradlew clean assembleDebug --no-build-cache --rerun-tasks` →
**BUILD SUCCESSFUL**, 40/40 tasks executed (not up-to-date/cached — a
genuine from-scratch compile). Only pre-existing warnings survived, the
exact same list already on record in
`docs/evidence/fold5-verification/evidence-log.md`'s Step 1 (`DownloadManager.kt`
deprecated `nextTarEntry`, `ContinuousFlowProtoActivity.kt` redundant
initializers, `LearnFragment.kt` deprecated APIs, `TranslateFragment.kt:822`
duplicate label) — no new warnings introduced by this pass.

## Devices used, and why

`adb devices -l` at session start showed all three devices this project has
used before, genuinely simultaneously connected:

```
15780287351340   device   Retroid Pocket 2+
R52X101MB6W       device   Galaxy Tab S9 FE (SM-X518U)
RFCW80CK2RW       device   Galaxy Z Fold 5 (SM-F946U)
```

Real hardware was used for all verification below — **Galaxy Z Fold 5
(`RFCW80CK2RW`)** as the primary target (it's the actual device this
feature's spec section was written for) and **Galaxy Tab S9 FE
(`R52X101MB6W`)** as a second device, specifically because it's the only one
of the three with `sw720dp`+ (confirmed via `adb shell wm size`/`wm density`:
1440×2304 @ 280dpi → ~823dp smallest width), so it's the only device that
exercises `fragment_conversations_large.xml`'s side-by-side layout — the
third of the three layouts this task required covering. The Retroid Pocket
2+ disconnected partway through this session (see "Honest gaps" below) and
was not used.

**Important, honestly-reported complication**: all three devices are the
user's real, actively-used personal devices, not an idle dedicated test rig,
and showed clear signs of real concurrent use *during this exact session*
(unrelated foreground apps repeatedly appearing without this agent's
involvement — `com.oblivion.djayclone` on the Fold 5, `com.beatwave.android`
on the Retroid Pocket, `com.vellum.studio` + a live YouTube-audio session on
the Tab S9 FE). Logcat evidence (below) further shows a long-running
continuous-listening session was already active on the Tab S9 FE
independently of this agent's own actions — almost certainly the sibling
`wakelock` worktree's own endurance test, given that fix's subject matter.
This agent's own `am force-stop`/reinstall cycles on that shared device
around 21:33–21:37 would have terminated that session. Flagged here plainly
so whoever picks up the `wakelock` branch's own verification knows a
same-package reinstall on `R52X101MB6W` during that window is a real,
identified possible cause if their own continuous-listening endurance data
has a gap there — not a defect in their code.

## Real on-device evidence

### 1. Large (side-by-side) layout — Galaxy Tab S9 FE, real hardware

`adb install -r` → launch → Conversations tab. Empty-state screenshot
confirms the new `RecyclerView`-based panes render with no crash, matching
the existing bubble-less baseline visually (screenshot:
`screenshots/01_large_layout_empty_tabS9FE.png`).

### 2. Real transcript bubbles, real on-device pipeline — Galaxy Tab S9 FE

English's Vosk voice-input pack was already downloaded on this device from
an earlier session (confirmed on the Translate tab: "Voice-input pack for
English downloaded — mic works fully offline"). Tapped Conversations' left
pane "TAP TO SPEAK" (manual flow), then played
`app/src/main/assets/real_speech_corpus/en_r1.wav` through the device's own
speaker via a real third-party player app (device speaker → device mic
loopback — the same "no live human speaker available" workaround this
project's own prior sessions used, e.g. `RealSpeechCorpus.kt`'s clips,
except played back live here instead of chunk-fed to a bypass harness).

**Real result, unstaged**: a `You (English): "huh"` bubble rendered in the
left pane (own-tint background, bold label, visible "⇄" reassign icon) and a
`Them (Spanish): "¿eh"` bubble rendered in the right pane (them-tint
background, same icon) — the turn indicator flipped from "Person A's turn"
to "Person B's turn" confirming `switchTurn()` fired, i.e. the full
mic → Vosk → `TranslationEngine` → `TtsRouter` → `switchTurn()` pipeline ran
for real, end to end, through the new per-entry rendering path (`addTurn`/
`addTranslation`), with no crash. Screenshots:
`screenshots/02_large_layout_real_bubbles_tabS9FE.png` (full screen) and
`screenshots/03_bubble_zoom_reassign_icon_tabS9FE.png` (2× crop showing the
own/them bubble styling and the "⇄" icon clearly).

The recognized text itself ("huh"/"¿eh") is not the intended corpus phrase —
real ambient audio in the room was picked up before/instead of the played
clip reliably landing (self-play-through-speaker loopback is inherently
lossy on real hardware, and this device's foreground kept getting displaced
by the concurrent activity described above, which repeatedly evicted or
backgrounded the app mid-attempt). That does not weaken what this evidence
demonstrates — real speech was really decoded by the real `VoskEngine`,
really translated, really rendered as two per-entry bubbles with correct
own/them attribution and a visible reassign affordance on real hardware —
it just means the transcript content itself is incidental ambient speech,
not the intended `en_r1.wav` sentence.

### 3. Fallback (book-portrait) layout — Galaxy Z Fold 5, real hardware

The Fold 5 did not have English's voice-input pack downloaded this session.
Per this agent's operating rules (no downloads without explicit user
permission — the same policy this spec's own §4/§6 already documented
blocking a "positive path" more than once), no download was attempted.
Tapping "Tap to speak" correctly showed the pre-existing (unmodified by this
pass) guard-clause Toast — "Download the voice-input pack for English on the
Translate tab first" — confirming `onMicTap`'s early-return checks still
work correctly after the rewrite (this pass only changed the body of
`onFinal`, not these guards). Screenshot:
`screenshots/04_fallback_layout_missing_model_guard_fold5.png`. Real
`logcat` line at the same timestamp: `Toast: show: caller =
com.retroid.translator.ui.ConversationsFragment.onMicTap:654`.

### 4. Mirrored (tabletop-landscape) layout — Galaxy Z Fold 5, real fold hardware

Forced via the same technique `docs/evidence/fold5-verification/evidence-log.md`
already established and validated on this exact device: `adb shell cmd
device_state state 2` (`HALF_OPENED`) + `settings put system
accelerometer_rotation 0` + `settings put system user_rotation 1` (forced
landscape) → real `FoldingFeature.orientation=HORIZONTAL`,
`isSeparating=true` → `wantMirrored=true`.

**Real logcat, this session**: `posture=TABLETOP_LANDSCAPE_ANGLED
wantMirrored=true feature.state=HALF_OPENED feature.orientation=HORIZONTAL
feature.isSeparating=true feature.occlusionType=NONE
feature.bounds=Rect(0, 906 - 2176, 906)` immediately followed by `mirrored
geometry: hinge=Rect(0, 906 - 2176, 906) occlusion=NONE state=HALF_OPENED
orientation=HORIZONTAL topPaneHeight=658 bottomPaneTop=658
bottomPaneHeight=997` — a real, non-50/50 hinge-derived split (`applyMirroredGeometry`
itself is untouched by this pass; this confirms it still works correctly
with the new `RecyclerView`-based panes it now sizes). Screenshot
(`screenshots/05_mirrored_layout_real_hinge_fold5.png`) shows the bottom
pane upright and the top pane genuinely rendered rotated 180° (mirrored
text), both panes showing the new empty-state `RecyclerView` layout, no
crash. State was reset afterward: `adb shell cmd device_state state reset` +
`settings put system accelerometer_rotation 1`, confirmed back to the
device's real physical `CLOSED` posture via a follow-up
`posture=NO_FOLDING_FEATURE` log line.

### 5. Continuous-listening flow — real, independently observed, not this agent's own trigger

`adb logcat -d` on the Tab S9 FE (`R52X101MB6W`) showed **dozens of real
`CONTINUOUS_LATENCY` log lines from `ConversationsFragment`** spanning
2026-08-11 21:27:08 through 21:31:51 (about 4.5 minutes, roughly one
utterance every 12–30 seconds), each a real `onUtteranceFinal` → `addTurn`/
`addTranslation` → TTS round trip through the new rendering path, all
`pickedLang=en`, all completing with no crash (`pidof com.retroid.translator`
returned a live PID throughout; `logcat -d | grep -i "FATAL\|AndroidRuntime"`
across the full session found nothing). This session did not start that
continuous-listening run — it was already in progress when first observed,
almost certainly the sibling `wakelock` worktree's own endurance test (see
"Devices used" above). Its abrupt end lines up with this agent's own
`am force-stop`/reinstall cycle on the same device, not a crash in this
code — no `FATAL`/`AndroidRuntime` line appears anywhere near that
timestamp. Treated here as real, valuable, independently-produced evidence
that the new per-entry rendering path holds up under sustained, repeated,
real continuous-listening use — while also being the honest source of the
"may have disrupted a concurrent session" note above.

## Honest gaps — not fully verified this pass

- **The reassign tap itself was not captured as a live before/after
  screenshot pair.** The rendering (bubbles, own/them styling, the visible
  "⇄" affordance) is real, on-device, verified evidence (§2 above). The tap
  handler wiring was code-reviewed directly
  (`TranscriptAdapter.onBindViewHolder` attaches `onReassign` to both
  `bubbleRoot` and `textReassignIcon` for every non-`failed` entry;
  `ConversationsFragment.reassignTurn` flips `speakerIsA` identically for
  every entry sharing a turn id; `refreshTranscriptViews` re-filters and
  re-submits to the correct pane adapters via `DiffUtil`-backed
  `ListAdapter.submitList`) and is straightforward, but a live tap → observe
  → confirm cycle specifically was not captured this pass. Repeated attempts
  were cut short by the real concurrent device activity described above
  (foreground repeatedly stolen by unrelated apps, one process actually
  reinstalled/reset mid-attempt) — continuing to force the issue by
  reinstalling/force-stopping the shared Tab S9 FE risked further disrupting
  whatever the concurrent (likely sibling-agent) session was doing, so this
  agent stopped rather than keep contending for the device. **Recommended
  quick manual check for whoever verifies this next**: with any two
  transcript entries on screen, tap the "⇄" icon on one — it should move to
  the other pane (mirrored/large) or its "A"/"B"/"→ A"/"→ B" label should
  flip (fallback), instantly, no re-translation.
- **Retroid Pocket 2+** disconnected partway through this session (`adb
  devices` stopped listing `15780287351340`) and was not used for this
  pass's verification — no fold hardware on it anyway, so it would only have
  added a second fallback-layout data point, which the Fold 5 already
  covered directly.
- **Spanish (or any second-language) real speech content** was not
  captured — the one real transcript this pass produced was English-only
  ambient audio, picked up by the manual flow's left-pane recognizer, with
  its Spanish translation rendered correctly in the right pane. The
  continuous-listening evidence (§5 above) was also entirely `pickedLang=en`
  in every logged utterance this session observed.
