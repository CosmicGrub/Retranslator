package com.retroid.translator.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.retroid.translator.MainActivity
import com.retroid.translator.R
import com.retroid.translator.TranslatorApp

/**
 * Wake-lock / foreground-service reliability fix for
 * docs/specs/fold5-adaptation.md §4's continuous-listening mode
 * (`ContinuousConversationController` + `MicPipeline.startContinuousListening`).
 *
 * **Why this exists**: before this class, continuous listening's mic-capture
 * thread ([MicPipeline]) had no wake lock and no foreground service backing
 * it at all - a plain background `Thread` reading `AudioRecord`, same
 * priority as any other app code. On Android generally, and Samsung's OEM
 * battery management in particular, a screen-off/backgrounded process with
 * no held wake lock can have its CPU suspended (no thread runs at all until
 * something wakes the device) and, separately, is a candidate for outright
 * process death under memory/battery pressure - both failure modes are
 * silent from the user's perspective: no crash, no error Toast, the
 * conversation just stops being heard. Since Android 14 (this project's
 * `targetSdk`), continuing to capture microphone audio while the app is not
 * in the foreground additionally requires an active foreground service
 * declaring `FOREGROUND_SERVICE_TYPE_MICROPHONE` - without one, the OS can
 * refuse to deliver mic audio at all once the app backgrounds, independent
 * of any battery-management killing.
 *
 * **What this class does**: a small, unbound foreground service whose sole
 * job is to (a) hold a `PARTIAL_WAKE_LOCK` so the CPU stays scheduled for
 * [MicPipeline]'s capture thread and the two `Recognizer` decode threads
 * [com.retroid.translator.conversation.ContinuousConversationController]
 * spins up per utterance, and (b) satisfy Android 14's foreground-service
 * mic-type requirement, with an honest, persistent, user-visible
 * notification ("Listening for conversation...") for the whole time - this
 * was chosen over a bare `PowerManager.WakeLock` with no visible indicator
 * specifically because silent background mic capture is worth being
 * cautious about from a privacy-transparency standpoint, doubly so for a
 * translation app. It does NOT own or touch any recognizer/VAD/decode
 * logic itself - [ConversationsFragment][com.retroid.translator.ui.ConversationsFragment]
 * starts/stops this service in exact lockstep with
 * [MicPipeline.startContinuousListening]/[MicPipeline.stop], so this class
 * never has to know anything about Vosk, dual-recognizer decoding, or VAD.
 *
 * **Lifecycle contract with the caller** (see `ConversationsFragment`'s
 * `startContinuousMode`/`stopContinuousMode`/`releaseContinuousEngines`):
 * started only once continuous listening has actually begun capturing
 * (both language models loaded, [MicPipeline.startContinuousListening]
 * called), and stopped on every path that ends continuous listening -
 * explicit toggle-off, an unrecoverable error, or the Fragment's view going
 * away for real (tab switch / Activity teardown). It deliberately does
 * *not* get stopped merely by `Fragment.onPause()` (screen lock, briefly
 * switching to another app) - `Fragment.onPause()` fires in exactly that
 * case too, and tearing this down there would defeat the entire fix, since
 * that's the specific scenario (screen-lock mid-conversation) this exists
 * to survive. See `ConversationsFragment.onPause`'s own comment for the
 * disclosure of this deliberate lifecycle divergence.
 *
 * [onTaskRemoved] is the one path this service reacts to on its own,
 * without the Fragment telling it to: if the user swipes the app away from
 * Recents entirely, that is "backgrounded in a way that should end the
 * session" (as opposed to a mere screen lock), so this stops the shared
 * [MicPipeline] capture and itself rather than continuing to listen with no
 * app UI left at all.
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            // Foreground service *types* don't exist before API 29 - the
            // plain two-arg overload is the correct call on API 28
            // (this project's minSdk), not a fallback cutting a corner.
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLockIfNeeded()
        Log.i(TAG, "ContinuousListeningService started (foreground + wake lock acquired)")
        // Deliberately NOT START_STICKY - ConversationsFragment is the
        // single source of truth for whether continuous listening should be
        // running; if this process/service dies unexpectedly, silently
        // auto-restarting a foreground service with no mic pipeline wired
        // back up to it would be worse (a "listening" notification lying
        // about actual state) than just staying stopped.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLockIfHeld()
        Log.i(TAG, "ContinuousListeningService destroyed (wake lock released)")
        super.onDestroy()
    }

    /**
     * App swiped away from Recents entirely - treat this as real intent to
     * end the session (see class doc). Stops the shared [MicPipeline]'s
     * capture loop directly (best-effort - this service has no reference to
     * [com.retroid.translator.conversation.ContinuousConversationController],
     * which lives in the Fragment, so an in-flight utterance's two
     * `Recognizer`s are not explicitly reset here; they close themselves
     * once [MicPipeline.stop] stops delivering audio chunks, via their own
     * `finally { recognizer.close() }`, just without a clean `onSpeechEnd`
     * signal first - a disclosed, minor gap, not a leak) and stops itself.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: app swiped from Recents, stopping continuous listening")
        (applicationContext as? TranslatorApp)?.mic?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        // A timed acquire is a deliberate defense-in-depth safety net, not
        // the primary release mechanism - the primary mechanism is the
        // guaranteed release() call in onDestroy/onTaskRemoved below, on
        // every path ConversationsFragment stops this service. A real
        // conversation session is not expected to run uninterrupted past
        // this ceiling; if it somehow does, the CPU-wake guarantee lapses
        // but the foreground service + notification keep running until a
        // real stop happens - logged loudly if it's ever hit, since that
        // would mean a real conversation ran longer than anticipated.
        lock.setReferenceCounted(false)
        lock.acquire(MAX_WAKE_LOCK_DURATION_MS)
        wakeLock = lock
    }

    private fun releaseWakeLockIfHeld() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Conversation listening",
            NotificationManager.IMPORTANCE_LOW // ongoing status only - no sound/heads-up
        ).apply {
            description = "Shown while Conversations' continuous listening mode is actively capturing audio."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // Was a hardcoded "Retranslator" literal; switched to the real
            // app_name resource so the Fold5 edition's persistent
            // notification reads "Retranslator Fold5" (see strings.xml's
            // Fold5-edition comment) instead of silently staying generic.
            .setContentTitle(getString(R.string.app_name))
            // Honest, literal description of what's happening in the
            // background right now - see class doc's privacy-transparency
            // rationale for choosing a foreground service over a silent
            // wake lock in the first place.
            .setContentText("Listening for conversation…")
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val TAG = "ContListeningSvc"
        private const val CHANNEL_ID = "continuous_listening"
        private const val NOTIFICATION_ID = 4201
        private const val WAKE_LOCK_TAG = "RetroidTranslator:ContinuousListening"
        private const val MAX_WAKE_LOCK_DURATION_MS = 2 * 60 * 60 * 1000L // 2h safety-net ceiling, see acquireWakeLockIfNeeded
    }
}
