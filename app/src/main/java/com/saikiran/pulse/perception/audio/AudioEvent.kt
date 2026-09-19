package com.saikiran.pulse.perception.audio

import com.saikiran.pulse.engine.events.EventSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Immutable environmental audio perception event.
 *
 * @param eventId    Unique identifier for this audio event.
 * @param timestamp  Timestamp (ms) when the sound was detected.
 * @param soundType  High-level classified sound category.
 * @param label      Exact label string from YAMNet model output.
 * @param confidence Confidence score in [0.0, 1.0].
 * @param source     Event source (always [EventSource.AUDIO]).
 */
data class AudioEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val soundType: SoundType,
    val label: String,
    val confidence: Float,
    val source: EventSource = EventSource.AUDIO,
) {
    /** Format timestamp as "HH:mm:ss" for developer debug log. */
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
