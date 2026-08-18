# Italian Piper voice (it_IT-riccardo) — URL and license verification

Closes the Tier 2 item "Add 1-2 new-language Piper voices" (Italian pilot) in
`docs/specs/engines-upgrade-plan.md`. This file documents the real,
independently-verified download URL/coordinates and license check; see
`sherpa-onnx-1.13.5-regression.md` in this same directory (or the final task
report) for the real on-device download+synthesis confirmation.

## Real URL confirmed (not guessed)

Per the task's own instruction to verify the real URL works rather than
guess it: the sherpa-onnx `tts-models` GitHub release (the exact release the
existing 4 languages already download from) was queried directly via the
GitHub API (`api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models`),
which lists every real asset. Italian has 4 speakers upstream (riccardo,
paola, miro, dii); this task named `riccardo` specifically. The API confirms:

```
vits-piper-it_IT-riccardo-x_low.tar.bz2       26496614 bytes
vits-piper-it_IT-riccardo-x_low-int8.tar.bz2  13329285 bytes
vits-piper-it_IT-riccardo-x_low-fp16.tar.bz2  16499635 bytes
```

**Only `x_low` quality exists for this speaker** (no `medium`/`high` tier
upstream — confirmed via a direct `HEAD` request against a guessed
`vits-piper-it_IT-riccardo-medium.tar.bz2` URL, which 404s; the `x_low`
variant 302-redirects to a real signed asset URL). `PiperVoiceCatalog.kt`'s
new entry uses this real filename and the exact byte count above as
`approxSizeMiB` (26MB, rounded).

Real download URL used in the catalog:
```
https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-it_IT-riccardo-x_low.tar.bz2
```

## Real archive downloaded and inspected (host-side, not assumed)

Downloaded the real archive directly (not just HEAD-checked):

```
sha256: 8ad0791558ae2b3e96054fc5756ae3ae2f6457fb1765bc01f43bbab85e0a7021
size:   26,496,614 bytes (matches the GitHub API's reported size exactly)
```

Extracted and inspected against every check `PiperTtsEngine.isCompleteVoiceDir`
actually performs on-device before allowing a voice to load:

| Check | Result |
|---|---|
| `it_IT-riccardo-x_low.onnx` present, size | 20,578,720 bytes — well above `PiperTtsEngine.MIN_MODEL_BYTES` (10,000,000 bytes), so this voice will not trip the truncated-download safety check |
| `tokens.txt` present, non-empty | 763 bytes |
| `espeak-ng-data/` directory present | yes |
| Required files inside it: `phontab`, `phonindex`, `phondata`, `intonations` | all 4 present |
| `MODEL_CARD` | present — confirms `it_IT`, 1 speaker, quality `x_low`, 16,000Hz, "Trained from scratch" |
| `it_IT-riccardo-x_low.onnx.json` `espeak.voice` field | `"it"` — matches the `mlKitCode` used in the new catalog entry |

This is the identical archive shape (`MODEL_CARD` + `<voiceId>.onnx.json` +
`tokens.txt` + `espeak-ng-data/`) the existing 8 catalog voices already use —
no special-casing needed in `PiperTtsEngine`/`TtsRouter`, confirming the
plan's "zero code changes needed" claim for this addition.

## License verification (independent of the task's claim)

The `MODEL_CARD` inside the archive points to the M-AILABS dataset page
(`https://www.caito.de/2019/01/03/the-m-ailabs-speech-dataset/`) with
"License: See URL" — that domain did not resolve from this session's network
(`getaddrinfo ENOTFOUND www.caito.de`, confirmed via both `curl` and
`WebFetch` — a DNS/reachability issue, not a license red flag). Verified the
same license text via an independent mirror instead
(`github.com/imdatceleste/m-ailabs-dataset`'s `README.md`, corroborated by a
general web search):

- Dataset license is BSD-style: redistribution and commercial use permitted
  (with/without modification), provided the copyright notice/disclaimer is
  retained and the copyright holder's name isn't used to endorse derived
  products without permission.
- Audio was recorded by the LibriVox project (public domain); text sourced
  from LibriVox and Project Gutenberg (public domain), published 1884-1964.
- **The one documented per-language carve-out is Ukrainian** ("machine
  learning purposes only", contributed by Nash Format/Gwara Media) — this
  is the same exception already flagged in `engines-upgrade-plan.md`'s Tier 3
  section ("M-AILABS' Ukrainian audio specifically is licensed narrower...
  even though the rest of that dataset is public domain"). **Italian carries
  no such exception** — it is plain LibriVox/Gutenberg public-domain content
  under the dataset's general BSD-style terms.

This matches the task's "confirmed cleanly public-domain" framing, now traced
to an independently-fetched, quotable source rather than taken on faith.

## Catalog entry added

`PiperVoiceCatalog.kt` — single-gender entry (no acceptable-license female
Italian Piper voice was in scope for this pilot), same shape as the existing
`fr_FR-gilles-low` single-gender entry:

```kotlin
PiperVoiceInfo(
    "it", VoiceGender.MALE, "it_IT-riccardo-x_low", "Italian - riccardo", "x_low",
    "$RELEASE_BASE/vits-piper-it_IT-riccardo-x_low.tar.bz2", 26,
    "Public domain / BSD-style redistribution (M-AILABS dataset: LibriVox + Project Gutenberg)",
    "https://github.com/imdatceleste/m-ailabs-dataset/blob/master/README.md"
),
```

No `PiperTtsEngine.kt` or `TtsRouter.kt` changes — `"it"` is already a
standard ML Kit `TranslateLanguage` code (also already used elsewhere in this
codebase: `VoskModelCatalog.kt`'s Italian STT entry and
`SpeechSynthesis.java`'s `ita`→`it` eSpeak language map), so `LanguageCatalog`
and the existing `mlKitCode`-intersection logic in `TtsRouter` pick this
voice up automatically once downloaded.

See the final task report / `sherpa-onnx-1.13.5-regression.md` for the real
on-device download-and-synthesize confirmation on `RFCW80CK2RW`.
