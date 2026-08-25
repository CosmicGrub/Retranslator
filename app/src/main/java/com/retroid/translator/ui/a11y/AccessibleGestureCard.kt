package com.retroid.translator.ui.a11y

import android.os.Bundle
import android.view.View
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * One named, conditionally-available custom accessibility action - exposed
 * in TalkBack's actions menu. [perform] always calls into the SAME function
 * the view's touch/gesture handler already calls - never a reimplementation
 * of the underlying behavior, matching
 * [com.retroid.translator.ui.TranslateFragment.installSingleCircleAccessibility]'s
 * established precedent (docs/specs/engineering-systems-pitch.md system #6).
 */
class AccessibleGestureAction(
    val label: String,
    val isAvailable: () -> Boolean = { true },
    val perform: () -> Unit,
)

/**
 * Generalizes [com.retroid.translator.ui.TranslateFragment.installSingleCircleAccessibility]'s
 * proven pattern into a reusable installer for any gesture-only card: a raw
 * `setOnTouchListener` swipe handler with no TalkBack path at all - the
 * exact failure mode that function already fixed once for `cardCircle`, and
 * which recurred at this app's only other `setOnTouchListener` site
 * (`PracticeFragment`'s drill carousel) before this installer closed it.
 *
 * Attaches one [AccessibilityDelegateCompat] exposing [actions] as named
 * custom actions (`AccessibilityNodeInfoCompat.addAction`), each dispatched
 * to the exact function the touch path already calls, plus an optional
 * [onClick] mapped to the standard `ACTION_CLICK` (TalkBack's double-tap) -
 * mirroring the reference implementation's own `ACTION_CLICK` handling for
 * "the single most expected first action on this widget", not a guess.
 *
 * Call this exactly ONCE per view, from the same one-time setup function
 * the touch listener is installed in (not from a per-refresh function) -
 * [actions]' `isAvailable`/`perform` closures read live fragment state at
 * call-time, so availability stays correct across state changes without
 * ever needing to reinstall the delegate. Content description is
 * deliberately NOT managed here - the caller should assign
 * `view.contentDescription` directly from its own live-refresh function,
 * the same way [com.retroid.translator.ui.TranslateFragment]'s
 * `refreshSingleCircleContent` assigns `singleCircleAccessibilityDescription()`
 * on every state change, so TalkBack announces the update immediately
 * rather than only on the next explicit re-query.
 *
 * Deliberately additive: never touches [view]'s existing
 * `OnTouchListener`/`OnClickListener` - only adds an accessibility delegate
 * alongside them. Does not retrofit `cardCircle`'s already-verified path.
 */
fun installAccessibleGestureCard(
    view: View,
    actions: List<AccessibleGestureAction>,
    onClick: (() -> Unit)? = null,
) {
    val actionIds = actions.associateWith { View.generateViewId() }
    ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            for (action in actions) {
                if (action.isAvailable()) {
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(actionIds.getValue(action), action.label))
                }
            }
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK && onClick != null) {
                onClick()
                return true
            }
            val matched = actionIds.entries.firstOrNull { it.value == action }?.key
            if (matched != null && matched.isAvailable()) {
                matched.perform()
                return true
            }
            return super.performAccessibilityAction(host, action, args)
        }
    })
}
