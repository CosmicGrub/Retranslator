package com.retroid.translator.engine

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

/**
 * Real device-RAM-tier checks, via [ActivityManager.MemoryInfo] - deliberately
 * NOT a device-model string match, so the resulting behavior is honest about
 * WHY it's enabled (real measured RAM headroom) rather than WHERE (a specific
 * device name/build). See docs/specs/engines-upgrade-plan.md's Tier 3
 * "device-tiered Vosk resident-model cap" section for the real on-device
 * measurements this is based on: the Fold 5 (11.4GB total RAM, ~3.7GB real
 * `MemAvailable` with 2 Vosk models already resident, confirmed via
 * `adb shell cat /proc/meminfo` in this project's §4 dual-recognizer spike)
 * and the Tab S9 FE (6GB total) both have "15-20x the headroom a 3rd
 * concurrent small model (~107MB average delta) would cost" per that plan.
 * Watch6 Classic is the explicitly-documented OPPOSITE case (only ~426MB
 * real `MemAvailable`, not its 1.8GB nominal total) and must NOT get this
 * treatment - which is exactly why this is a real runtime RAM check, not a
 * hardcoded "is this a Fold5/Tab" flag: a device-model check would need to
 * be manually extended for every future high-RAM device/edition, and would
 * wrongly enable this on a low-RAM device that merely shares a product
 * line/name pattern.
 *
 * Forward-ported unchanged from commit ad27ca2 (a divergent sibling line,
 * fold5-capacity-ocr -> fold5-device-edition, that forked before this
 * branch's HEAD and was never merged forward - docs/specs/
 * engineering-systems-pitch.md system #3's whole reason for existing).
 * [voskResidentModelCap] is real, already-reviewed code, but is NOT yet
 * wired into [VoskEngine] on this branch - that commit's other 200+ lines
 * (VoskEngine's multi-model-residency + prewarm changes) were deliberately
 * left behind as a separate, bigger change outside this pass's scope; only
 * the pure capability-check primitive comes over here. [isHighRamDevice] IS
 * consumed by this pass's new capability-gated call sites below.
 */
object DeviceCapabilities {
    private const val TAG = "DeviceCapabilities"

    /**
     * Picked to clearly separate "phone/tablet-class" real RAM (Fold5
     * 11.4GB, Tab S9 FE 6GB) from "watch/entry-level-class" real RAM (Watch6
     * Classic 1.8GB nominal, this app's original Retroid Pocket 2+ target,
     * whose ~1GB-usable-RAM design point [VoskEngine]'s original single-
     * resident-model comment already documents) - with real margin on both
     * sides, not tuned to exactly one measured device.
     */
    private const val HIGH_RAM_THRESHOLD_BYTES = 4L * 1024 * 1024 * 1024 // 4GiB

    /** `ActivityManager.MemoryInfo.totalMem` for the real device this process is running on - 0L only if the check itself fails (never treated as "high RAM" in that case). */
    fun totalRamBytes(context: Context): Long {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            Log.w(TAG, "ActivityManager unavailable - treating as low-RAM device")
            return 0L
        }
        return try {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        } catch (e: Exception) {
            Log.w(TAG, "getMemoryInfo failed - treating as low-RAM device", e)
            0L
        }
    }

    fun isHighRamDevice(context: Context): Boolean = totalRamBytes(context) >= HIGH_RAM_THRESHOLD_BYTES

    /**
     * How many Vosk models [VoskEngine] is allowed to keep simultaneously
     * resident before evicting the least-recently-used one. **1** preserves
     * this app's original design point unchanged on low-RAM devices/builds
     * (every [VoskEngine.loadModelAsync] call still unloads the previous
     * model first, exactly like before this feature existed - see
     * [VoskEngine]'s own class doc). **3** on real high-RAM devices - see
     * class doc above for the measured headroom this is grounded in, and
     * why 3 specifically: the existing dual-recognizer pattern
     * ([com.retroid.translator.conversation.ContinuousConversationController])
     * already proves 2 independently-resident models decode correctly with
     * sub-linear (1.9x, not 2x) wall-time cost; this adds real capacity for
     * one more on top of that, not an unbounded cache.
     *
     * Not yet consumed anywhere on this branch (see class doc's forward-port
     * note) - kept because it's real, already-reviewed logic that costs
     * nothing to carry forward, not because anything currently calls it.
     */
    fun voskResidentModelCap(context: Context): Int = if (isHighRamDevice(context)) 3 else 1

    /**
     * Live free-RAM signal, distinct from [totalRamBytes]/[isHighRamDevice]
     * (a static total-RAM tier): a device can pass the total-RAM tier and
     * still be under real memory pressure at the exact moment a large model
     * load is requested. New for docs/specs/engineering-systems-pitch.md
     * system #3 - not part of the ad27ca2 forward-port above.
     */
    fun availableRamBytes(context: Context): Long {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            Log.w(TAG, "ActivityManager unavailable - treating as zero available RAM")
            return 0L
        }
        return try {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem
        } catch (e: Exception) {
            Log.w(TAG, "getMemoryInfo failed - treating as zero available RAM", e)
            0L
        }
    }

    /**
     * NOT a measured threshold, unlike [HIGH_RAM_THRESHOLD_BYTES] (grounded
     * in real Fold5/Tab-S9-FE /proc/meminfo captures) - no on-device
     * memory-footprint measurement for [LlmAssistEngine]'s model exists
     * anywhere in this codebase ([LlmAssistEngine]'s own introducing commit
     * discloses this explicitly). 2.5x on-disk model size is engineering
     * judgment for runtime tensor/activation overhead, not a citation -
     * replace the moment a real measurement exists.
     */
    private const val LLM_RAM_MULTIPLIER_X10 = 25L

    fun hasHeadroomForLlm(context: Context, modelSizeMib: Int): Boolean =
        availableRamBytes(context) >= modelSizeMib.toLong() * 1024 * 1024 * LLM_RAM_MULTIPLIER_X10 / 10

    /**
     * Real billing-relevant signal, distinct from [DownloadManager.isOnWifi]'s
     * transport check - a mobile hotspot's Wi-Fi can be metered, and some
     * cellular plans aren't. Has known real-world platform quirks (some
     * carriers/regions misreport it, some access points don't propagate
     * metered hints) - a heuristic that reduces surprise, not a guarantee.
     */
    fun isMeteredConnection(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return cm.isActiveNetworkMetered
    }
}
