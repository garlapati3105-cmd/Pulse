package com.saikiran.pulse.engine.priority

import com.saikiran.pulse.engine.events.PulseEvent

/**
 * Immutable decision object produced by [PriorityEngine].
 *
 * @param event     The perception [PulseEvent] evaluated.
 * @param priority  Determined priority level (LOW, MEDIUM, HIGH).
 * @param score     Computed priority score [0..100].
 * @param speakNow  True if the decision engine recommends automatic spoken output.
 * @param reason    Deterministic, explainable rationale for the decision.
 */
data class PriorityDecision(
    val event: PulseEvent,
    val priority: PriorityLevel,
    val score: Int,
    val speakNow: Boolean,
    val reason: String,
)
