package com.retroid.translator.fold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log

/**
 * Optional progressive-enhancement layer over the discrete [FoldingFeature]
 * state: [Sensor.TYPE_HINGE_ANGLE] (API 30+) reports a continuous 0-360°
 * hinge angle, which [FoldPostureProvider] can use to drive a smooth
 * interpolated transition instead of a hard cut when the discrete state
 * flips (spec §2 "Transition polish").
 *
 * Real, tested fallback: on any device/API level where
 * [SensorManager.getDefaultSensor] returns null for this sensor type,
 * [isAvailable] is false and [start] is a no-op that always returns false —
 * callers must snap directly on [FoldingFeature] state changes instead of
 * waiting for angle updates that will never arrive.
 *
 * Verified on-device (Galaxy Z Fold 5, serial RFCW80CK2RW, Android 16/API 36):
 * `adb shell dumpsys sensorservice` lists a real
 * `hinge_angle Wakeup | Samsung | type: android.sensor.hinge_angle(36)`
 * sensor, i.e. this device takes the live-sensor path, not the fallback —
 * confirmed via logcat during on-device testing (see
 * docs/specs/fold5-adaptation.md implementation-order note for step 1).
 */
class HingeAngleSensor(context: Context) {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val sensor: Sensor? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        } else {
            null
        }

    /** True only when this device+API level actually exposes a hinge-angle sensor. */
    val isAvailable: Boolean get() = sensor != null

    private var listener: SensorEventListener? = null

    /**
     * Starts listening for continuous hinge-angle updates (degrees, 0=closed,
     * 180=flat). Returns false immediately, without registering anything, if
     * no hinge-angle sensor exists on this device/API level — callers should
     * treat that as "no smooth transition available, rely on discrete
     * FoldingFeature state changes only" (spec §2's stated fallback).
     */
    fun start(onAngleDegrees: (Float) -> Unit): Boolean {
        val sm = sensorManager
        val s = sensor
        if (sm == null || s == null) {
            Log.i(TAG, "TYPE_HINGE_ANGLE not available on this device — falling back to discrete FoldingFeature-only transitions")
            return false
        }
        stop()
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) onAngleDegrees(event.values[0])
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = l
        val registered = sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        if (!registered) {
            Log.w(TAG, "registerListener for TYPE_HINGE_ANGLE returned false")
            listener = null
        }
        return registered
    }

    fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
    }

    companion object {
        private const val TAG = "HingeAngleSensor"
    }
}
