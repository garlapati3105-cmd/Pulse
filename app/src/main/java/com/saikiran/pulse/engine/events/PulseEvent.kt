package com.saikiran.pulse.engine.events

import com.saikiran.pulse.perception.vision.spatial.SpatialPosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Immutable temporal event data model.
 *
 * @param eventId         Unique identifier for this event instance.
 * @param timestamp       Timestamp (ms) when the event occurred.
 * @param trackId         Identifier of the tracked person (e.g., "PERSON_1"), or null for system events.
 * @param eventType       Semantic event type.
 * @param confidence      Confidence score in [0.0, 1.0].
 * @param source          Perception or engine source of the event (VISION, IMU, FUSED, SYSTEM).
 * @param spatialPosition Spatial location and distance (e.g., LEFT, CENTER, RIGHT, NEAR, FAR).
 * @param description     Short human-readable event label (e.g. "PERSON_1_APPROACHING (center near)").
 */
data class PulseEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val trackId: String?,
    val eventType: EventType,
    val confidence: Float,
    val source: EventSource = EventSource.FUSED,
    val spatialPosition: SpatialPosition? = null,
    val description: String = if (trackId != null) {
        val typeLabel = when (eventType) {
            EventType.PERSON_ENTERED_VIEW -> "ENTERED_VIEW"
            EventType.PERSON_APPROACHING  -> "APPROACHING"
            EventType.PERSON_STOPPED      -> "STOPPED"
            EventType.PERSON_MOVING_AWAY  -> "MOVING_AWAY"
            EventType.PERSON_PASSING_BY   -> "PASSING_BY"
            EventType.PERSON_TRACK_LOST   -> "TRACK_LOST"
            EventType.PERSON_LEFT_VIEW    -> "LEFT_VIEW"
        }
        val posSuffix = spatialPosition?.shortDescription?.let { " ($it)" } ?: ""
        "${trackId}_$typeLabel$posSuffix"
    } else eventType.name,
) {
    /** Format timestamp as "HH:mm:ss" for developer debug log. */
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
