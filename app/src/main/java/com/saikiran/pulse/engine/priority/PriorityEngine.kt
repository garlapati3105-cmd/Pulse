package com.saikiran.pulse.engine.priority

import com.saikiran.pulse.engine.events.EventSource
import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.perception.vision.spatial.SpatialDistance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic, explainable Event Importance / Priority Engine.
 *
 * Scoring Strategy (Score range 0 to 100):
 *  1. Semantic Base Score:
 *     - PERSON_APPROACHING: +40
 *     - PERSON_ENTERED_VIEW: +25
 *     - PERSON_STOPPED: +20
 *     - PERSON_LEFT_VIEW / PERSON_TRACK_LOST: +15
 *     - PERSON_MOVING_AWAY / PERSON_PASSING_BY: +15
 *  2. Spatial Proximity Modifier:
 *     - Distance NEAR: +20
 *     - Distance MID: +10
 *     - Distance FAR: +0
 *  3. Fast Approach Modifier:
 *     - Description contains "quickly": +15
 *  4. Confidence Modifier:
 *     - High confidence (>= 0.70): +15
 *     - Moderate confidence (0.45 .. 0.70): +5
 *     - Low confidence (< 0.45): -30
 *  5. Phone Camera Motion Modifier:
 *     - Camera MOVING (description or source): -20
 *  6. Cooldown / Deduplication:
 *     - Per-trackId & EventType cooldown (6000ms).
 */
class PriorityEngine(
    private val cooldownMs: Long = 6000L,
    private val highPriorityScoreThreshold: Int = 60,
    private val mediumPriorityScoreThreshold: Int = 35,
) {
    private val lock = Any()
    private val lastSpokenTimestampMap = mutableMapOf<String, Long>()

    private val _latestDecisionFlow = MutableStateFlow<PriorityDecision?>(null)
    val latestDecisionFlow: StateFlow<PriorityDecision?> = _latestDecisionFlow.asStateFlow()

    /** Clear cooldown history and reset decision flow. */
    fun reset() {
        synchronized(lock) {
            lastSpokenTimestampMap.clear()
            _latestDecisionFlow.value = null
        }
    }

    /**
     * Evaluate an incoming [PulseEvent] and produce a deterministic [PriorityDecision].
     */
    fun evaluate(event: PulseEvent): PriorityDecision {
        synchronized(lock) {
            val now = event.timestamp

            // 1. Calculate Base Semantic Score
            val baseScore = when (event.eventType) {
                EventType.PERSON_APPROACHING  -> 40
                EventType.PERSON_ENTERED_VIEW -> 25
                EventType.PERSON_STOPPED      -> 20
                EventType.PERSON_LEFT_VIEW    -> 15
                EventType.PERSON_TRACK_LOST   -> 15
                EventType.PERSON_MOVING_AWAY  -> 15
                EventType.PERSON_PASSING_BY   -> 15
            }

            // 2. Spatial Proximity Modifier
            val proximityScore = when (event.spatialPosition?.distance) {
                SpatialDistance.NEAR -> 20
                SpatialDistance.MID  -> 10
                SpatialDistance.FAR  -> 0
                null                 -> 0
            }

            // 3. Fast Approach Speed Modifier
            val speedScore = if (event.description.contains("quickly")) 15 else 0

            // 4. Confidence Modifier
            val confidenceScore = when {
                event.confidence >= 0.70f -> 15
                event.confidence >= 0.45f -> 5
                else -> -30
            }

            // 5. Phone Camera Motion Penalty
            val motionPenalty = if ((event.source == EventSource.IMU) || (event.description.contains("CAMERA_MOVING"))) -20 else 0

            // Compute Total Score clamped [0, 100]
            val totalScore = (baseScore + proximityScore + speedScore + confidenceScore + motionPenalty).coerceIn(0, 100)

            // Determine Priority Level
            val priority = when {
                totalScore >= highPriorityScoreThreshold -> PriorityLevel.HIGH
                totalScore >= mediumPriorityScoreThreshold -> PriorityLevel.MEDIUM
                else -> PriorityLevel.LOW
            }

            // Evaluate Cooldown Key ("TRACK_ID:EVENT_TYPE")
            val cooldownKey = "${event.trackId ?: "SYSTEM"}:${event.eventType.name}"
            val lastSpokenTime = lastSpokenTimestampMap.getOrDefault(cooldownKey, 0L)
            val isCooldownActive = (now - lastSpokenTime) < cooldownMs

            // Evaluate speakNow & Rationale
            val spatialPhrase = event.spatialPosition?.phrase ?: "nearby"
            val speakNow: Boolean
            val reason: String

            when {
                event.confidence < 0.45f -> {
                    speakNow = false
                    reason = "Suppressed: Low confidence (${(event.confidence * 100).toInt()}%)"
                }

                isCooldownActive -> {
                    speakNow = false
                    reason = "Suppressed: Cooldown active for ${event.eventType.name} (${(cooldownMs - (now - lastSpokenTime)) / 1000}s remaining)"
                }

                priority == PriorityLevel.LOW -> {
                    speakNow = false
                    reason = "Suppressed: Low priority score ($totalScore)"
                }

                priority == PriorityLevel.HIGH -> {
                    speakNow = true
                    lastSpokenTimestampMap[cooldownKey] = now
                    reason = "High priority: ${event.eventType.name} $spatialPhrase (Score: $totalScore)"
                }

                else -> { // PriorityLevel.MEDIUM
                    speakNow = true
                    lastSpokenTimestampMap[cooldownKey] = now
                    reason = "Medium priority: ${event.eventType.name} $spatialPhrase (Score: $totalScore)"
                }
            }

            val decision = PriorityDecision(
                event = event,
                priority = priority,
                score = totalScore,
                speakNow = speakNow,
                reason = reason,
            )

            _latestDecisionFlow.value = decision
            return decision
        }
    }
}
