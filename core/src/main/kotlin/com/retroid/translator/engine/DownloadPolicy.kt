package com.retroid.translator.engine

/**
 * The Wi-Fi-gating decision every download path in this app needs, pulled
 * out of the two places it was independently written: [DownloadManager]'s
 * `runDownload` (the real download plumbing) and [TranslationEngine]'s
 * `attemptTranslate` (a pre-flight check before the runtime translate path
 * touches the network at all - see that function's own doc comment for why
 * it needs this check independently of `runDownload`). Both call sites were
 * hand-writing the same `requireWifi && !onWifi` shape rather than sharing
 * one implementation - a real, if narrow, drift risk (docs/specs/
 * engineering-systems-pitch.md system #2).
 */
object DownloadPolicy {
    /**
     * True if a download must be blocked right now - no [android.content.Context],
     * no network call, just the two booleans the caller already has.
     *
     * @param requireWifi whether THIS caller's policy requires Wi-Fi before
     *   proceeding. [DownloadManager.runDownload] passes its own `requireWifi`
     *   parameter directly; [TranslationEngine.attemptTranslate] passes
     *   `!bothReady` - "we still need to fetch something" plays the same
     *   role there as "this download requires Wi-Fi" does in `runDownload`.
     * @param onWifi the device's current Wi-Fi state (from
     *   [DownloadManager.isOnWifi]).
     */
    fun shouldBlockDownload(requireWifi: Boolean, onWifi: Boolean): Boolean =
        requireWifi && !onWifi
}
