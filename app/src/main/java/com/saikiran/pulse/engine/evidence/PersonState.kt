package com.saikiran.pulse.engine.evidence

import com.saikiran.pulse.perception.vision.movement.MovementType
import com.saikiran.pulse.perception.vision.spatial.SpatialPosition

/**
 * Immutable snapshot of an active tracked person's perception state.
 */
data class PersonState(
    val trackId: String,
    val movementType: MovementType,
    val spatialPosition: SpatialPosition?,
    val confidence: Float,
    val isFastApproach: Boolean = false,
) {
    fun toCompactText(): String {
        val spatial = spatialPosition?.shortDescription ?: "nearby"
        val fast = if (isFastApproach) " (FAST)" else ""
        return "[$trackId: ${movementType.name}$fast, Pos: $spatial, Conf: ${(confidence * 100).toInt()}%]"
    }
}
