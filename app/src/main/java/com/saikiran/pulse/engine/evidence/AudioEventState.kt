package com.saikiran.pulse.engine.evidence

import com.saikiran.pulse.perception.audio.SoundType

/**
 * Immutable snapshot of a recent environmental sound event.
 */
data class AudioEventState(
    val soundType: SoundType,
    val label: String,
    val confidence: Float,
    val timestampMs: Long,
) {
    fun toCompactText(): String {
        return "[SOUND: ${soundType.name} ($label), Conf: ${(confidence * 100).toInt()}%]"
    }
}
