package com.saikiran.pulse.engine.change

/**
 * Meaningful semantic change types for Pulse Change Detector V1.
 */
enum class ChangeType {
    PERSON_ENTERED_VIEW,
    PERSON_APPROACHING,
    PERSON_MOVING_AWAY,
    PERSON_STOPPED,
    PERSON_PASSING_BY,
    PERSON_TRACK_LOST,
    PERSON_LEFT_VIEW,
}
