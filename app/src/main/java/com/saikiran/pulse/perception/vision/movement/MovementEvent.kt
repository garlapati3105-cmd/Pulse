package com.saikiran.pulse.perception.vision.movement

/**
 * Movement classification event for a single tracked person.
 *
 * @param trackId        Identifier of the tracked person (e.g. "PERSON_1").
 * @param movementType   Classified movement trajectory.
 * @param confidence     Classification confidence score in [0.0, 1.0].
 * @param isFastApproach True if the person is approaching rapidly (high urgency).
 * @param timestamp      Timestamp (ms) of the frame analysis.
 */
data class MovementEvent(
    val trackId: String,
    val movementType: MovementType,
    val confidence: Float,
    val isFastApproach: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)
