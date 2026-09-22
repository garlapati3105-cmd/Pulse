package com.saikiran.pulse.audio.haptics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.saikiran.pulse.engine.priority.PriorityLevel

/**
 * Manages tactile haptic feedback using standard public Android APIs.
 * Complements voice alerts with tactile feedback for LOW, MEDIUM, and HIGH priority events.
 */
class HapticFeedbackManager(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    companion object {
        private const val TAG = "HapticFeedbackManager"
    }

    /**
     * Perform tactile haptic feedback pattern matching the event priority level.
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    fun triggerHapticForPriority(priority: PriorityLevel) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "VIBRATE permission not granted")
            return
        }

        try {
            when (priority) {
                PriorityLevel.LOW -> {
                    // Subtle 15ms click pulse
                    vib.vibrate(VibrationEffect.createOneShot(15L, VibrationEffect.DEFAULT_AMPLITUDE))
                }

                PriorityLevel.MEDIUM -> {
                    // Distinct double pulse
                    val timings = longArrayOf(0L, 30L, 50L, 30L)
                    val amplitudes = intArrayOf(0, 150, 0, 150)
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                }

                PriorityLevel.HIGH -> {
                    // Heavy urgent triple pulse
                    val timings = longArrayOf(0L, 50L, 40L, 50L, 40L, 80L)
                    val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                    vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                }
            }
            Log.d(TAG, "Triggered haptic pattern for $priority")
        } catch (e: Exception) {
            Log.w(TAG, "Error triggering haptic feedback", e)
        }
    }
}
