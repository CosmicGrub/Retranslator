#!/usr/bin/env bash
# Guardrail against the exact recurrence this repo already had once: a raw
# setOnTouchListener gesture handler with zero TalkBack path
# (docs/specs/engineering-systems-pitch.md system #6 - PracticeFragment's
# drill carousel repeated the same gap TranslateFragment's cardCircle had
# already hit and fixed). Deliberately a small grep-based script, not a real
# lint rule - sized to match how small the actual surface is (setOnTouchListener
# has exactly 2 real call sites repo-wide as of this writing).
#
# Passes if every .kt file calling setOnTouchListener also references either
# the shared installAccessibleGestureCard() installer or a raw
# AccessibilityDelegateCompat (the technique used before the installer
# existed, e.g. TranslateFragment's installSingleCircleAccessibility) -
# fails loudly, naming the exact file, otherwise.
set -euo pipefail

fail=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if ! grep -q -e 'installAccessibleGestureCard' -e 'AccessibilityDelegateCompat' "$f"; then
    echo "::error file=$f::setOnTouchListener with no accessibility path in this file"
    fail=1
  fi
done < <(grep -rl 'setOnTouchListener' app/src/main/java --include='*.kt' 2>/dev/null || true)

exit $fail
