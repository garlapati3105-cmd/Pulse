package com.saikiran.pulse.perception.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Monitors phone motion using Android IMU sensors (Gyroscope & Linear Acceleration).
 *
 * Responsibilities:
 *  1. Collects angular velocity (Gyroscope) and linear acceleration (without gravity).
 *  2. Evaluates short-term motion intensity over a rolling time window.
 *  3. Computes whether the camera phone is [PhoneMotionState.CAMERA_STABLE] or [PhoneMotionState.CAMERA_MOVING].
 *  4. Gracefully handles devices missing [Sensor.TYPE_LINEAR_ACCELERATION] by falling back to Gyroscope or Accelerometer.
 *
 * Threading & Lifecycle:
 *  - Must be started in [start] and stopped in [stop] matching active screen lifecycle.
 *  - High-efficiency sensor sampling ([SensorManager.SENSOR_DELAY_GAME] ~20ms).
 */
class SensorMotionMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccel = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    private var gyroMagnitude: Float = 0f

    @Volatile
    private var accelMagnitude: Float = 0f

    @Volatile
    var currentMotionState: PhoneMotionState = PhoneMotionState.CAMERA_STABLE
        private set

    @Volatile
    var motionIntensityRatio: Float = 0f
        private set

    companion object {
        private const val TAG = "SensorMotionMonitor"

        // Thresholds calibrated for handheld operation
        private const val GYRO_MOVING_THRESHOLD = 0.45f     // rad/s (~25 degrees/sec)
        private const val ACCEL_MOVING_THRESHOLD = 1.20f    // m/s^2 (excluding gravity)
    }

    /** Register sensor listeners matching active screen lifecycle. */
    fun start() {
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager unavailable on this device")
            return
        }

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        linearAccel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        Log.d(TAG, "SensorMotionMonitor started. Gyro: ${gyroscope != null}, Accel: ${linearAccel != null}")
    }

    /** Unregister sensor listeners when screen is no longer active. */
    fun stop() {
        sensorManager?.unregisterListener(this)
        Log.d(TAG, "SensorMotionMonitor stopped.")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val currentMag = sqrt(gx * gx + gy * gy + gz * gz)
                gyroMagnitude = gyroMagnitude * 0.7f + currentMag * 0.3f
                updateMotionState()
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val currentMag = sqrt(ax * ax + ay * ay + az * az)
                accelMagnitude = accelMagnitude * 0.7f + currentMag * 0.3f
                updateMotionState()
            }

            Sensor.TYPE_ACCELEROMETER -> {
                // Fallback for devices without dedicated linear acceleration sensor
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val rawMag = sqrt(ax * ax + ay * ay + az * az)
                val currentMag = abs(rawMag - SensorManager.GRAVITY_EARTH)
                accelMagnitude = accelMagnitude * 0.7f + currentMag * 0.3f
                updateMotionState()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun updateMotionState() {
        val gyroRatio = (gyroMagnitude / GYRO_MOVING_THRESHOLD).coerceIn(0f, 2f)
        val accelRatio = (accelMagnitude / ACCEL_MOVING_THRESHOLD).coerceIn(0f, 2f)

        val combinedRatio = maxOf(gyroRatio, accelRatio)
        motionIntensityRatio = combinedRatio

        currentMotionState = if (gyroMagnitude >= GYRO_MOVING_THRESHOLD || accelMagnitude >= ACCEL_MOVING_THRESHOLD) {
            PhoneMotionState.CAMERA_MOVING
        } else {
            PhoneMotionState.CAMERA_STABLE
        }
    }
}
