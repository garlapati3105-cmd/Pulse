package com.saikiran.pulse.engine.evidence

import com.saikiran.pulse.engine.change.ChangeEvent
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.perception.sensors.PhoneMotionState

/**
 * Structured Evidence Contract representing the current multimodal situation state.
 * Consumed by local AI reasoning layer and deterministic engines.
 */
data class SituationState(
    val timestampMs: Long = System.currentTimeMillis(),
    val activePeople: List<PersonState> = emptyList(),
    val environmentalEvents: List<AudioEventState> = emptyList(),
    val recentEvents: List<PulseEvent> = emptyList(),
    val importantChanges: List<ChangeEvent> = emptyList(),
    val sensorState: PhoneMotionState = PhoneMotionState.CAMERA_STABLE,
    val overallConfidence: Float = 1.0f,
) {
    /**
     * Serializes structured situation state into a compact, human-readable prompt string.
     */
    fun toPromptText(): String {
        val sb = StringBuilder()
        sb.append("Current Situation State:\n")
        sb.append("- Sensor Motion: ${sensorState.name}\n")
        sb.append("- Overall Confidence: ${(overallConfidence * 100).toInt()}%\n")

        if (activePeople.isEmpty()) {
            sb.append("- Active People: None in view\n")
        } else {
            sb.append("- Active People:\n")
            activePeople.forEach { person ->
                sb.append("  * ${person.toCompactText()}\n")
            }
        }

        if (environmentalEvents.isEmpty()) {
            sb.append("- Environmental Sounds: None detected\n")
        } else {
            sb.append("- Environmental Sounds:\n")
            environmentalEvents.forEach { sound ->
                sb.append("  * ${sound.toCompactText()}\n")
            }
        }

        if (recentEvents.isNotEmpty()) {
            sb.append("- Recent Events (${recentEvents.size}):\n")
            recentEvents.takeLast(3).forEach { ev ->
                sb.append("  * ${ev.formattedTime()} ${ev.description}\n")
            }
        }

        return sb.toString()
    }
}
