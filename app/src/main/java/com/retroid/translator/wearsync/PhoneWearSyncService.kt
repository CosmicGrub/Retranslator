package com.retroid.translator.wearsync

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Phone-side Data Layer API SCAFFOLD for the watch6-classic-adaptation
 * (docs/specs/watch6-classic-adaptation.md) - a genuine starting point for a
 * follow-up sync pass, not a functional feature yet. Per that spec's
 * explicit priority order ("functionally independent AND phone-synced, in
 * that order - the watch works standalone first"), nothing in the phone
 * app calls into this today, and the watch side
 * (com.retroid.translator.wear.sync.WearSyncClient) never sends it a
 * message either. This is intentionally the smallest real addition that
 * gives a follow-up pass something to build on: a registered, working
 * WearableListenerService that will actually receive messages once the
 * watch side sends any (verified receivable by the manifest's intent-filter
 * path prefix below - see spec for what was/wasn't verified on-device).
 *
 * Deliberately does NOT touch any existing phone-app class - additive-only,
 * matching this project's established house style for every other spec in
 * docs/specs/.
 */
class PhoneWearSyncService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        // No real handling yet - a follow-up pass would parse
        // messageEvent.path (e.g. "/retroid/langpack-sync") and
        // messageEvent.data here. Logged, not silently dropped, so a real
        // message arriving during future testing is observable without
        // more code.
        Log.i(TAG, "onMessageReceived: path=${messageEvent.path} sourceNodeId=${messageEvent.sourceNodeId} bytes=${messageEvent.data.size}")
    }

    companion object {
        private const val TAG = "PhoneWearSyncService"
        /** Must match wear/AndroidManifest's intent-filter path prefix below. */
        const val MESSAGE_PATH_PREFIX = "/retroid"
    }
}
