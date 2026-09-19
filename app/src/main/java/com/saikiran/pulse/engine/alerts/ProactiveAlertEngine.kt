package com.saikiran.pulse.engine.alerts

import android.util.Log
import com.saikiran.pulse.audio.TtsManager
import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.events.PulseEvent

/**
 * Hands-Free Proactive Safety Alert Engine.
 *
 * Responsibilities:
 *  1. Listens to high-priority perception events in real-time.
 *  2. Evaluates event urgency (e.g., PERSON_APPROACHING, PERSON_STOPPED).
 *  3. Formulates concise, natural 3–5 word spatial audio chimes (e.g. "Person approaching on your left").
 *  4. Applies a per-category cooldown ([cooldownMs], default 5 seconds) to prevent speech spamming.
 *  5. Automatically speaks urgent chimes via [TtsManager] when [isEnabled] is true.
 */
class ProactiveAlertEngine(
    private val ttsManager: TtsManager,
    private val cooldownMs: Long = 5000L,
) {
    @Volatile
    var isEnabled: Boolean = false

    private val lastAlertTimestampMap = mutableMapOf<EventType, Long>()

    companion object {
        private const val TAG = "ProactiveAlertEngine"
    }

    /**
     * Process an incoming [PulseEvent] and trigger a hands-free spoken audio alert if enabled and outside cooldown.
     */
    fun processEvent(event: PulseEvent) {
        if (!isEnabled) return

        val now = System.currentTimeMillis()
        val lastAlertTime = lastAlertTimestampMap.getOrDefault(event.eventType, 0L)

        // Enforce per-category cooldown
        if (now - lastAlertTime < cooldownMs) return

        val spatialPhrase = event.spatialPosition?.phrase ?: "nearby"

        val alertText = when (event.eventType) {
            EventType.PERSON_APPROACHING -> "Person approaching $spatialPhrase."
            EventType.PERSON_STOPPED      -> "Person stopped $spatialPhrase."
            EventType.PERSON_ENTERED_VIEW -> "Person appeared $spatialPhrase."
            EventType.PERSON_LEFT_VIEW    -> "Person walked out of view."
            else                          -> null
        }

        if (alertText != null) {
            lastAlertTimestampMap[event.eventType] = now
            Log.d(TAG, "Proactive alert triggered: \"$alertText\"")
            ttsManager.speak(alertText)
        }
    }

    /** Reset alert cooldowns. */
    fun reset() {
        lastAlertTimestampMap.clear()
    }
}
