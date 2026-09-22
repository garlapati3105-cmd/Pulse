package com.saikiran.pulse.engine.situation

import com.saikiran.pulse.engine.priority.PriorityLevel
import com.saikiran.pulse.perception.audio.SoundType
import com.saikiran.pulse.perception.vision.movement.MovementType
import com.saikiran.pulse.perception.vision.spatial.SpatialPosition

/**
 * Represents a coherent, evolving real-world situation tracked across time.
 * Correlates visual movements, spatial location, audio evidence, and priority.
 */
data class Situation(
    val situationId: String,
    val trackId: String?,
    val lifecycleState: SituationLifecycleState,
    val movementType: MovementType,
    val spatialPosition: SpatialPosition?,
    val supportingAudio: List<SoundType> = emptyList(),
    val confidence: Float,
    val startTimeMs: Long,
    val lastUpdatedTimeMs: Long,
    val importance: PriorityLevel,
) {
    val durationMs: Long get() = lastUpdatedTimeMs - startTimeMs

    fun toNaturalDescription(): String {
        val spatialPhrase = spatialPosition?.phrase ?: "nearby"
        val audioSuffix = if (supportingAudio.isNotEmpty()) {
            val names = supportingAudio.distinct().joinToString(" and ") { it.name.replace("_", " ").lowercase() }
            ". I can hear $names"
        } else ""

        return when (lifecycleState) {
            SituationLifecycleState.STARTED -> "A person appeared $spatialPhrase$audioSuffix."
            SituationLifecycleState.ESCALATING -> "Someone is approaching $spatialPhrase$audioSuffix."
            SituationLifecycleState.ACTIVE -> "Someone is $spatialPhrase$audioSuffix."
            SituationLifecycleState.CHANGED -> "Someone who was moving is now stationary $spatialPhrase$audioSuffix."
            SituationLifecycleState.DE_ESCALATING -> "Someone is moving away $spatialPhrase."
            SituationLifecycleState.RESOLVED -> "Visual contact resolved."
        }
    }
}
