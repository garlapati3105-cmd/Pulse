package com.saikiran.pulse.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Programmatically discovers and exposes device hardware capabilities.
 * Allows Pulse to adapt gracefully across various Android smartphones (iQOO, OnePlus, Samsung, Pixel).
 */
class DeviceCapabilityManager(private val context: Context) {

    val capabilities: DeviceCapabilities by lazy {
        discoverCapabilities()
    }

    companion object {
        private const val TAG = "DeviceCapabilityManager"
    }

    private fun discoverCapabilities(): DeviceCapabilities {
        val pm = context.packageManager
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        val hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val hasMicrophone = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

        val hasGyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val hasLinearAccel = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
        val hasAccel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasMag = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        val hasProximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        val hasLight = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null

        val (hasHaptics, hasAmplitude) = checkHapticCapabilities()

        val isOnDeviceSpeech = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            false
        }

        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val isLowRam = activityManager?.isLowRamDevice ?: false

        val abis = Build.SUPPORTED_ABIS.toList()

        val caps = DeviceCapabilities(
            deviceModel = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE.toIntOrNull() ?: 0,
            sdkInt = Build.VERSION.SDK_INT,
            hasCamera = hasCamera,
            hasMicrophone = hasMicrophone,
            hasGyroscope = hasGyroscope,
            hasLinearAcceleration = hasLinearAccel,
            hasAccelerometer = hasAccel,
            hasMagnetometer = hasMag,
            hasProximitySensor = hasProximity,
            hasLightSensor = hasLight,
            hasHaptics = hasHaptics,
            hasAmplitudeControl = hasAmplitude,
            isOnDeviceSpeechAvailable = isOnDeviceSpeech,
            totalMemoryMb = totalRamMb,
            isLowRamDevice = isLowRam,
            supportedAbis = abis,
        )

        Log.i(TAG, "Discovered Device Capabilities:\n${caps.toSummaryString()}")
        return caps
    }

    private fun checkHapticCapabilities(): Pair<Boolean, Boolean> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vibrator = vibratorManager?.defaultVibrator
            Pair(vibrator?.hasVibrator() == true, vibrator?.hasAmplitudeControl() == true)
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            Pair(vibrator?.hasVibrator() == true, vibrator?.hasAmplitudeControl() == true)
        }
    }
}
