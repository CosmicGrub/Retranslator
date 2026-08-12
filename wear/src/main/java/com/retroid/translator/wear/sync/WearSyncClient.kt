package com.retroid.translator.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

/**
 * Phone-sync SCAFFOLD ONLY - matches this pass's "functionally independent
 * AND phone-synced, in that order of priority" decision (see spec): the
 * watch must work fully standalone with zero code here ever running, and
 * this class is not called from any UI flow in this pass. It exists so a
 * follow-up pass has a real, compiling starting point rather than a blank
 * page - see docs/specs/watch6-classic-adaptation.md's "What's scaffolded
 * but not working" section.
 *
 * Mirrors the phone-side scaffold added in this same pass,
 * `com.retroid.translator.wearsync.PhoneWearSyncService` (see that class's
 * doc comment) - this is the watch-side half of the same not-yet-wired
 * Data Layer API pair.
 */
class WearSyncClient(context: Context) {
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context.applicationContext)

    /**
     * Checks whether a phone advertising [PHONE_CAPABILITY] is currently
     * reachable. Never called yet - a future pass would use this to decide
     * "skip re-downloading a language pack the phone already has" per the
     * spec's phone-sync design intent.
     */
    fun isPhoneCompanionReachable(onResult: (Boolean) -> Unit) {
        capabilityClient.getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info -> onResult(info.nodes.isNotEmpty()) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Capability lookup failed (expected if no phone paired - standalone is the priority mode)", e)
                onResult(false)
            }
    }

    companion object {
        private const val TAG = "WearSyncClient"
        /** Must match PhoneWearSyncService's advertised capability name exactly (see app-side scaffold). */
        const val PHONE_CAPABILITY = "retroid_translator_phone_companion"
    }
}
