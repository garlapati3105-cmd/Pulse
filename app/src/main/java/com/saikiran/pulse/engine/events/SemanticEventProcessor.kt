package com.saikiran.pulse.engine.events

import com.saikiran.pulse.engine.fusion.SensorFusionEngine
import com.saikiran.pulse.perception.sensors.PhoneMotionState
import com.saikiran.pulse.perception.vision.PersonBox
import com.saikiran.pulse.perception.vision.TrackDropInfo
import com.saikiran.pulse.perception.vision.movement.MovementType

/**
 * State machine that converts raw frame movement classifications into clean, non-duplicate
 * semantic events emitted to [SensorFusionEngine].
 *
 * Semantic Rules:
 *  1. [EventType.PERSON_ENTERED_VIEW]: Emitted ONCE (Source: VISION) when a new `trackId` is first observed.
 *  2. Movement Transitions: Emitted ONLY when stable movement state changes.
 *  3. Track Disappearance:
 *     - [EventType.PERSON_LEFT_VIEW] (Source: VISION): Emitted if the track dropped near the camera frame boundary.
 *     - [EventType.PERSON_TRACK_LOST] (Source: VISION): Emitted if the track dropped in the central frame area (visual contact lost).
 */
class SemanticEventProcessor(
    private val fusionEngine: SensorFusionEngine,
    private val eventStore: TemporalEventStore,
) {
    private val lastEmittedMovementMap = mutableMapOf<String, MovementType>()
    private val activeTrackIds = mutableSetOf<String>()

    /**
     * Process current classified frame detections and produce semantic events on state transitions.
     *
     * @param detections Currently active tracked and classified person boxes.
     * @param droppedTracks Tracks dropped by PersonTracker in the current frame update pass.
     * @param phoneMotionState Current IMU camera motion state.
     * @param timestampMs Frame analysis timestamp.
     */
    @Synchronized
    fun processFrame(
        detections: List<PersonBox>,
        droppedTracks: List<TrackDropInfo>,
        phoneMotionState: PhoneMotionState,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        eventStore.purgeExpired(timestampMs)

        // 1. Detect newly entered tracks (PERSON_ENTERED_VIEW)
        for (box in detections) {
            val trackId = box.trackId
            if (!activeTrackIds.contains(trackId)) {
                activeTrackIds.add(trackId)
                lastEmittedMovementMap[trackId] = MovementType.UNKNOWN

                fusionEngine.onVisionEvent(
                    PulseEvent(
                        timestamp = timestampMs,
                        trackId = trackId,
                        eventType = EventType.PERSON_ENTERED_VIEW,
                        confidence = box.confidence,
                        source = EventSource.VISION,
                        spatialPosition = box.spatialPosition,
                    ),
                    phoneMotionState
                )
            }
        }

        // 2. Detect movement state transitions (Duplicate Suppression)
        for (box in detections) {
            val trackId = box.trackId
            val currentType = box.movementType

            if (currentType == MovementType.UNKNOWN) continue

            val lastType = lastEmittedMovementMap[trackId]

            if (currentType != lastType) {
                val eventType = when (currentType) {
                    MovementType.PERSON_APPROACHING -> EventType.PERSON_APPROACHING
                    MovementType.PERSON_MOVING_AWAY -> EventType.PERSON_MOVING_AWAY
                    MovementType.PERSON_PASSING_BY  -> EventType.PERSON_PASSING_BY
                    MovementType.STATIONARY         -> EventType.PERSON_STOPPED
                    MovementType.UNKNOWN            -> null
                }

                if (eventType != null) {
                    lastEmittedMovementMap[trackId] = currentType
                    fusionEngine.onVisionEvent(
                        PulseEvent(
                            timestamp = timestampMs,
                            trackId = trackId,
                            eventType = eventType,
                            confidence = box.confidence,
                            source = EventSource.VISION, // Will be upgraded by Fusion Engine if supported
                            spatialPosition = box.spatialPosition,
                        ),
                        phoneMotionState
                    )
                }
            }
        }

        // 3. Detect track disappearances (PERSON_LEFT_VIEW vs PERSON_TRACK_LOST)
        for (dropped in droppedTracks) {
            val dropId = dropped.id
            if (activeTrackIds.contains(dropId)) {
                activeTrackIds.remove(dropId)
                lastEmittedMovementMap.remove(dropId)

                val eventType = if (dropped.isNearBorder) {
                    EventType.PERSON_LEFT_VIEW
                } else {
                    EventType.PERSON_TRACK_LOST
                }

                fusionEngine.onVisionEvent(
                    PulseEvent(
                        timestamp = timestampMs,
                        trackId = dropId,
                        eventType = eventType,
                        confidence = 0.85f,
                        source = EventSource.VISION,
                    ),
                    phoneMotionState
                )
            }
        }
    }

    @Synchronized
    fun reset() {
        activeTrackIds.clear()
        lastEmittedMovementMap.clear()
        fusionEngine.reset()
        eventStore.clear()
    }
}
