package com.saikiran.pulse.engine.alerts

import android.util.Log
import com.saikiran.pulse.audio.TtsManager
import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.priority.PriorityDecision
import com.saikiran.pulse.engine.priority.PriorityLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit log entry for the Proactive Alert Coordinator debug panel.
 */
data class ProactiveAlertAuditLog(
    val eventDescription: String,
    val priority: PriorityLevel,
    val isSpoken: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
    val reason: String,
) {
    /** Format timestamp as "HH:mm:ss" for developer debug panel. */
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }
}

/**
 * Proactive Alert Coordinator that bridges [PriorityEngine] decisions to [TtsManager].
 *
 * Behavior & Rules:
 *  1. Only [PriorityLevel.HIGH] decisions with [speakNow = true] trigger automatic speech.
 *  2. [PriorityLevel.MEDIUM] and [PriorityLevel.LOW] remain silent.
 *  3. [isProactiveVoiceEnabled] toggle controls overall proactive voice output (Default: ON).
 *  4. [isMuted] toggle silences proactive speech without disabling detection or memory.
 *  5. Interruption Policy: Higher-scoring events interrupt ongoing lower-scoring speech.
 *  6. Deduplication: Suppresses repeated speech while a person's stable state remains unchanged.
 */
class ProactiveAlertCoordinator(
    private val ttsManager: TtsManager,
) {
    @Volatile
    var isProactiveVoiceEnabled: Boolean = true

    @Volatile
    var isMuted: Boolean = false

    private val lock = Any()
    private var lastSpokenScore: Int = 0
    private var lastSpokenTimestampMs: Long = 0L

    private val _latestAuditLog = MutableStateFlow<ProactiveAlertAuditLog?>(null)
    val latestAuditLog: StateFlow<ProactiveAlertAuditLog?> = _latestAuditLog.asStateFlow()

    companion object {
        private const val TAG = "ProactiveAlertCoordinator"
    }

    /**
     * Process a new [PriorityDecision] and speak if it meets HIGH-priority proactive alert criteria.
     */
    fun processDecision(decision: PriorityDecision) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val event = decision.event

            // Rule 1: Check master toggles
            if (!isProactiveVoiceEnabled) {
                logAudit(decision, isSpoken = false, reason = "Proactive Voice is OFF")
                return
            }

            if (isMuted) {
                logAudit(decision, isSpoken = false, reason = "Audio is Muted")
                return
            }

            // Rule 2: ONLY HIGH priority triggers automatic speech in Milestone 6B
            if (decision.priority != PriorityLevel.HIGH || !decision.speakNow) {
                val reason = when (decision.priority) {
                    PriorityLevel.MEDIUM -> "Silent: MEDIUM priority"
                    PriorityLevel.LOW    -> "Silent: LOW priority"
                    else                 -> decision.reason
                }
                logAudit(decision, isSpoken = false, reason = reason)
                return
            }

            // Rule 3: Interruption policy — higher score interrupts old speech
            val speechDurationMs = 2500L // Estimated average utterance time
            val isTtsSpeakingNow = (now - lastSpokenTimestampMs) < speechDurationMs

            if (isTtsSpeakingNow && decision.score <= lastSpokenScore) {
                logAudit(
                    decision,
                    isSpoken = false,
                    reason = "Suppressed: Ongoing speech has equal/higher score ($lastSpokenScore vs ${decision.score})"
                )
                return
            }

            // Rule 4: Formulate spatial speech phrase
            val spatialPhrase = event.spatialPosition?.phrase ?: "nearby"
            val textToSpeak = generateSpokenAlertText(event.eventType, spatialPhrase, event.description)

            if (textToSpeak != null) {
                lastSpokenScore = decision.score
                lastSpokenTimestampMs = now

                ttsManager.speak(textToSpeak)
                Log.d(TAG, "Proactive alert spoken: \"$textToSpeak\" (Score: ${decision.score})")

                logAudit(
                    decision,
                    isSpoken = true,
                    reason = "HIGH priority alert spoken: \"$textToSpeak\""
                )
            } else {
                logAudit(decision, isSpoken = false, reason = "No speech phrase mapped")
            }
        }
    }

    private fun generateSpokenAlertText(eventType: EventType, spatialPhrase: String, description: String): String? {
        return when (eventType) {
            EventType.PERSON_APPROACHING -> {
                if (description.contains("quickly")) {
                    "Warning! Someone is approaching quickly $spatialPhrase."
                } else {
                    "Someone is approaching $spatialPhrase."
                }
            }
            EventType.PERSON_STOPPED      -> "Someone stopped $spatialPhrase."
            EventType.PERSON_MOVING_AWAY  -> "Someone is moving away."
            EventType.PERSON_ENTERED_VIEW -> "Someone appeared $spatialPhrase."
            EventType.PERSON_LEFT_VIEW    -> "Someone walked out of view."
            else                          -> null
        }
    }

    private fun logAudit(decision: PriorityDecision, isSpoken: Boolean, reason: String) {
        _latestAuditLog.value = ProactiveAlertAuditLog(
            eventDescription = decision.event.description,
            priority = decision.priority,
            isSpoken = isSpoken,
            timestampMs = decision.event.timestamp,
            reason = reason,
        )
    }

    /** Reset state and audit logs. */
    fun reset() {
        synchronized(lock) {
            lastSpokenScore = 0
            lastSpokenTimestampMs = 0L
            _latestAuditLog.value = null
        }
    }
}
