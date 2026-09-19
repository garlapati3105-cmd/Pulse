package com.saikiran.pulse.engine.change

import com.saikiran.pulse.engine.events.EventSource
import java.util.UUID

/**
 * Immutable object representing a detected semantic state change.
 *
 * @param changeId    Unique identifier for this change event.
 * @param timestamp   Timestamp in milliseconds when the change occurred.
 * @param trackId     Identifier of the tracked entity (e.g. "PERSON_1"), or null.
 * @param changeType  Specific type of state transition.
 * @param confidence  Confidence score in [0.0, 1.0].
 * @param description Conservative human-readable change description.
 * @param source      Perception or engine source (VISION, IMU, FUSED, SYSTEM).
 */
data class ChangeEvent(
    val changeId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val trackId: String?,
    val changeType: ChangeType,
    val confidence: Float,
    val description: String,
    val source: EventSource = EventSource.FUSED,
)
