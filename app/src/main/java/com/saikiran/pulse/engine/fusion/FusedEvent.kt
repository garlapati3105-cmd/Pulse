package com.saikiran.pulse.engine.fusion

import com.saikiran.pulse.engine.events.EventSource
import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.perception.vision.spatial.SpatialPosition

/**
 * Immutable output of the [SensorFusionEngine] combining multimodal observations.
 * Contains detailed telemetry for explainability and UI debugging.
 */
data class FusedEvent(
    val eventId: String,
    val timestamp: Long,
    val eventType: EventType,
    val fusedConfidence: Float,
    val spatialPosition: SpatialPosition?,
    val supportingSources: List<EventSource>,
    val reason: String,
    
    // Telemetry for Debug Panel
    val visionConfidence: Float?,
    val audioType: String?,
    val audioConfidence: Float?,
    val imuState: String?,
)
