package com.saikiran.pulse.device

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Manages thermal status monitoring and power optimization.
 * Dynamically adjusts execution parameters under thermal throttling (SEVERE/CRITICAL).
 */
class ThermalPowerManager(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    companion object {
        private const val TAG = "ThermalPowerManager"
    }

    /**
     * Get current device thermal status integer level.
     * Maps to PowerManager.THERMAL_STATUS_* constants on Android 10+ (API 29+).
     */
    fun getThermalStatus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } else {
            0 // PowerManager.THERMAL_STATUS_NONE equivalent
        }
    }

    /**
     * Returns true if device is experiencing elevated thermal throttling (SEVERE or CRITICAL).
     */
    fun isThermalThrottling(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val status = getThermalStatus()
            return status >= PowerManager.THERMAL_STATUS_SEVERE
        }
        return false
    }

    /**
     * Log current thermal state for developer telemetry.
     */
    fun logThermalStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val status = getThermalStatus()
            val label = when (status) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN ($status)"
            }
            Log.d(TAG, "Device Thermal Status: $label")
        }
    }
}
