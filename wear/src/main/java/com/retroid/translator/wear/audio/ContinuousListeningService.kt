package com.retroid.translator.wear.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.retroid.translator.wear.MainActivity
import com.retroid.translator.wear.R

/**
 * Wake-lock / foreground-service reliability fix for `:wear`'s continuous/
 * ambient listening (`TranslateController.startListening` ->
 * `MicPipeline.startContinuousListening`) - see
 * docs/specs/watch6-classic-adaptation.md's "Continuous-listening
 * reliability" follow-up item (§9/§12) for why this was needed: before this
 * class, nothing backed the capture thread with a wake lock or foreground
 * service at all, so it almost certainly died the instant the screen locked
 * - a form factor whose screen sleeps far more aggressively than a phone's.
 *
 * **Ports the CONCEPT of the phone app's
 * [com.retroid.translator.audio.ContinuousListeningService] fix
 * (docs/specs/fold5-adaptation.md §4), not its code verbatim.** The core
 * mechanism is identical on both, because the underlying platform
 * requirement and failure mode are identical: a foreground service typed
 * `microphone` (required since Android 14 to keep receiving mic audio while
 * backgrounded) holding a `PARTIAL_WAKE_LOCK` for its whole lifetime
 * (required so the CPU stays scheduled at all through a screen lock/Doze),
 * with an honest, persistent, user-visible notification - silent background
 * mic capture is worth being cautious about from a privacy-transparency
 * standpoint, doubly so for a translation app whose entire premise is
 * listening to what people say. What's deliberately DIFFERENT from the
 * phone's version, not copied for false parity:
 *
 * - **No `Build.VERSION.SDK_INT` gating on `startForeground`'s 3-arg
 *   overload or notification-channel creation.** The phone's `minSdk` 28 is
 *   below both API 26 (`NotificationChannel`) and API 29 (the typed
 *   `startForeground` overload), so it branches. `:wear`'s `minSdk` is 30
 *   (see `wear/build.gradle.kts`'s own doc comment on why - Wear OS 3 is
 *   the realistic floor for a new Wear Compose app), already above both
 *   gates, so those branches would be dead code here.
 * - **`setLocalOnly(true)` on the notification.** This is a standalone Wear
 *   OS app (`com.google.android.wearable.standalone=true`, see
 *   `AndroidManifest.xml`) doing its own on-watch mic capture, not a
 *   bridged/mirrored phone notification - there is nothing phone-side this
 *   should mirror to, and bridging it to a paired phone's shade would be
 *   actively misleading (implying the *phone* is what's listening). The
 *   phone app has no equivalent concern; it has no paired watch to bridge
 *   to.
 * - **A much shorter wake-lock safety-net ceiling (30 minutes, not the
 *   phone's 2 hours).** This ceiling is defense-in-depth only in both
 *   versions - the real release path is the guaranteed `onDestroy`/
 *   `onTaskRemoved` release below, not this timeout - but a small watch
 *   battery (Watch6 Classic 47mm: 425mAh) left silently pinned awake by a
 *   forgotten-on session is a much bigger fraction of total battery than
 *   the identical mistake on a phone or tablet.
 * - **No shared, Application-level `MicPipeline` reference for
 *   [onTaskRemoved] to reach into.** The phone's `TranslatorApp` holds a
 *   shared `mic` field this service calls directly; `:wear`'s engines are
 *   owned directly by `TranslateController`, not hung off
 *   `WearTranslatorApp` (see that class's own doc comment - deliberately
 *   minimal, no shared engines yet, since `:wear` has exactly one screen so
 *   far). [onTaskRemovedListener] is this module's equivalent single-
 *   purpose hook: `TranslateController` points it at a `{ mic.stop() }`
 *   closure right before starting this service and clears it right after
 *   stopping it, rather than introducing an app-wide shared-engine
 *   singleton just for this one callback.
 *
 * **Lifecycle contract with the caller** (see
 * `TranslateController.startListeningService`/`stopListeningService`,
 * called from the same choke points the phone's `ConversationsFragment`
 * uses: start right before `MicPipeline.startContinuousListening`, stop
 * unconditionally - safe even if never started - from every path that ends
 * listening): started only once the source-language Vosk model has loaded
 * and continuous listening is actually about to begin capturing; stopped on
 * explicit manual stop, on the mic pipeline's own error/early-failure path,
 * on the capture thread's natural stop callback, and defensively again on
 * `TranslateController.release()` (`MainActivity.onDestroy`). Screen-off/
 * lock does NOT stop this service or the underlying capture - that is the
 * entire point of the fix.
 */
class ContinuousListeningService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        acquireWakeLockIfNeeded()
        Log.i(TAG, "ContinuousListeningService started (foreground + wake lock acquired)")
        // Deliberately NOT START_STICKY - TranslateController is the single
        // source of truth for whether continuous listening should be
        // running; same reasoning as the phone's identical choice (see its
        // own class doc) - silently auto-restarting a foreground service
        // with no mic pipeline wired back up to it would be worse (a
        // "listening" notification lying about actual state) than staying
        // stopped.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLockIfHeld()
        Log.i(TAG, "ContinuousListeningService destroyed (wake lock released)")
        super.onDestroy()
    }

    /**
     * App swiped away from Recents entirely - treat this as real intent to
     * end the session, same as the phone's identical override. See
     * [onTaskRemovedListener]'s doc above for why this reaches a callback
     * instead of an app-global shared `MicPipeline` reference.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: app swiped from Recents, stopping continuous listening")
        onTaskRemovedListener?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        lock.setReferenceCounted(false)
        lock.acquire(MAX_WAKE_LOCK_DURATION_MS)
        wakeLock = lock
    }

    private fun releaseWakeLockIfHeld() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Continuous listening",
            NotificationManager.IMPORTANCE_LOW // ongoing status only - no sound/heads-up/wrist-buzz
        ).apply {
            description = "Shown while continuous listening is actively capturing audio."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Retranslator")
            // Honest, literal description of what's happening right now -
            // see class doc's privacy-transparency rationale for choosing a
            // foreground service over a silent wake lock in the first place.
            .setContentText("Listening…")
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .setLocalOnly(true) // standalone watch app doing its own on-watch capture - see class doc
            .build()
    }

    companion object {
        private const val TAG = "WearContListeningSvc"
        private const val CHANNEL_ID = "continuous_listening"
        private const val NOTIFICATION_ID = 4301
        private const val WAKE_LOCK_TAG = "RetroidTranslatorWear:ContinuousListening"
        private const val MAX_WAKE_LOCK_DURATION_MS = 30 * 60 * 1000L // 30 min safety-net ceiling, see class doc

        /**
         * Set by `TranslateController` right before starting this service,
         * cleared right after stopping it. See class doc's "No shared,
         * Application-level MicPipeline reference" section for why this
         * exists instead of reaching an app-global singleton.
         */
        @Volatile
        var onTaskRemovedListener: (() -> Unit)? = null
    }
}
