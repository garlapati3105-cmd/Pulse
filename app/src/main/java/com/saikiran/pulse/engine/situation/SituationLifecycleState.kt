package com.saikiran.pulse.engine.situation

/**
 * Evolving lifecycle state of a real-world situation.
 */
enum class SituationLifecycleState {
    STARTED,
    ACTIVE,
    CHANGED,
    ESCALATING,
    DE_ESCALATING,
    RESOLVED,
}
