package com.saikiran.pulse.perception.vision.movement

/**
 * Semantic movement trajectory types for tracked persons.
 */
enum class MovementType {
    PERSON_APPROACHING,
    PERSON_MOVING_AWAY,
    PERSON_PASSING_BY,
    STATIONARY,
    UNKNOWN,
}
