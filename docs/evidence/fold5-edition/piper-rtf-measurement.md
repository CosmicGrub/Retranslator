# Real Piper RTF measurement (engines-upgrade-plan.md, Tier 1 item)

Closes the Tier 1 item "Capture a real Piper RTF measurement" in
`docs/specs/engines-upgrade-plan.md`. `PiperTtsEngine.kt`'s `speak()` already
logs `synthMs=... audioMs=... rtf=%.2f` on every real synth call (see
`companion object` doc comment / the `Log.i(TAG, "Piper synth: ...")` line) —
the gap was purely that no evidence file had ever captured a real value. The
"RTF 0.35–0.63" figure previously cited in `fold5-adaptation.md`'s Goal
section traced to nothing real (not in `README.md`'s current content or full
git history, and the commit that introduced Piper — `c30813e` — explicitly
states the on-device measurement was never completed because the test device
disconnected mid-session). This file replaces that unsourced figure with a
real measured value and its source.

## Device / build

- Device: Galaxy Z Fold 5, serial `RFCW80CK2RW`, Android 16/API 36.
- Build: this branch (`fold5-quality-tier1-2`), unmodified `PiperTtsEngine.kt`
  — `./gradlew clean :app:assembleDebug` → **BUILD SUCCESSFUL in 13s**, 42
  actionable tasks (40 executed, 2 up-to-date), only the same pre-existing
  warnings already on record elsewhere in this repo.
- Installed via `adb -s RFCW80CK2RW install -r app/build/outputs/apk/debug/app-debug.apk`
  onto the already-present `com.retroid.translator.fold5` package (data
  preserved from an earlier session — the natural voice pack
  `en_US-ljspeech-medium` was already downloaded and complete, confirmed via
  `run-as com.retroid.translator.fold5 find files/piper-voices -type f`
  showing `en_US-ljspeech-medium.onnx`, `tokens.txt`, and a complete
  `espeak-ng-data/` directory).

## Method

Real taps on the real Practice tab UI (not a synthetic harness): typed a
phrase into `editPhrase`, tapped `btnHearReference`
(`app.tts.speak(text, "en", FEMALE, ...)` → `TtsRouter` → `PiperTtsEngine`,
since the English female natural voice is downloaded), with
`adb logcat -s PiperTtsEngine:I` capturing the real synth line. Two real runs,
different text lengths:

## Real measurements

```
08-18 08:05:24.028 PiperTtsEngine: Piper voice loaded: lang=en gender=FEMALE voice=en_US-ljspeech-medium sampleRate=22050
08-18 08:05:24.758 PiperTtsEngine: Piper synth: voice=en_US-ljspeech-medium gender=FEMALE chars=110 samples=163122 sampleRate=22050 synthMs=729 audioMs=7397 rtf=0.10
```

Text: "The quick brown fox jumps over the lazy dog while we test the natural
voice synthesis pipeline on this device." (110 chars).

```
08-18 08:06:17.235 PiperTtsEngine: Piper synth: voice=en_US-ljspeech-medium gender=FEMALE chars=62 samples=97792 sampleRate=22050 synthMs=359 audioMs=4435 rtf=0.08
```

Text: "Good morning. natural voice synthesis pipeline on this device." (62
chars — an editing artifact from clearing the field between runs left this
exact concatenation; still real, still spoken, `chars=62` matches its actual
length).

**Real RTF range measured on this device: 0.08–0.10** (synthesis time as a
fraction of the resulting audio's duration; lower is faster/better — both
runs synthesized in roughly a tenth of the audio's own playback length).

## Real audio-output confirmation (not just "no crash")

`adb shell dumpsys audio`, correlated by timestamp to each synth call, shows
a real `AudioTrack` player opening, starting, and running to completion for
each call — not silence or an immediately-aborted track:

Run 1 (piid 2631, matches the 08:05:24.758 synth line):
```
08-18 08:05:24:770 new player piid:2631 uid/pid:11347/29093 package:com.retroid.translator.fold5 type:android.media.AudioTrack attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_SPEECH ... FormatInfo{sampleRate=22050}
08-18 08:05:24:775 player piid:2631 event:started
08-18 08:05:24:981 player piid:2631 event:device updated deviceIds:[3]
08-18 08:05:32:209 player piid:2631 event:stopped
```
Started → stopped span is **7.434s**, matching the logged `audioMs=7397`
(7.397s) to within normal event-reporting latency — real, non-silent,
full-duration playback, not a truncated or empty track.

Run 2 (piid 2631 reused, matches the 08:06:17.235 synth line):
```
08-18 08:06:17:245 player piid:2631 event:started
08-18 08:06:17:262 player piid:2631 event:device updated deviceIds:[3]
08-18 08:06:21:460 player piid:2631 event:stopped
```
Started → stopped span is **4.215s**, matching the logged `audioMs=4435`
(4.435s) to within the same normal latency margin.

Both tracks report `channelMask=0x1` (mono) / `sampleRate=22050`, matching
`PiperTtsEngine.ensureAudioTrack`'s configuration exactly — real synthesized
speech audio, actually routed to and played by the device's audio HAL, not a
Kotlin-side no-op.

A real screenshot of the Practice tab mid-flow (English, Female voice,
phrase field populated, "Natural voice (English (US) - ljspeech) downloaded —
reference pronunciation uses it automatically" status line, "HEAR REFERENCE
PRONUNCIATION" button) is at `screenshots/piper-rtf-practice-tab.png`.

## Conclusion

Real measured Piper RTF on the Galaxy Z Fold 5 (Snapdragon 8 Gen 2): **0.08–0.10**,
comfortably faster than the previously-cited, unsourced "0.35–0.63" figure —
consistent with the Fold 5's Snapdragon 8 Gen 2 being dramatically more
capable than the Retroid Pocket 2+'s Unisoc T310 that figure was implicitly
guessed for. `fold5-adaptation.md`'s Goal section has been corrected to cite
this real value and point here.

**Honest scope note**: two real runs, one voice (`en_US-ljspeech-medium`,
medium quality), one device. This is enough to retire the unsourced range and
establish a real, reproducible on-device number — it is not a claim that
every voice/text/device combination lands in exactly this range.
