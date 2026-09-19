package com.saikiran.pulse.engine.events

/**
 * Semantic event types for Pulse temporal event engine.
 */
enum class EventType {
    PERSON_ENTERED_VIEW,
    PERSON_APPROACHING,
    PERSON_STOPPED,
    PERSON_MOVING_AWAY,
    PERSON_PASSING_BY,
    PERSON_TRACK_LOST,
    PERSON_LEFT_VIEW,
}
