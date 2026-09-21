package com.saikiran.pulse.engine.fusion

import com.saikiran.pulse.engine.events.EventSource
import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.engine.events.TemporalEventStore
import com.saikiran.pulse.perception.audio.AudioEvent
import com.saikiran.pulse.perception.audio.SoundType
import com.saikiran.pulse.perception.sensors.PhoneMotionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Deterministic Sensor Fusion Engine combining Vision, Audio, and IMU observations.
 *
 * Rules:
 *  - CASE A: Vision = APPROACHING + IMU = STABLE -> increase confidence slightly.
 *  - CASE B: Vision = APPROACHING + Audio = FOOTSTEPS + IMU = STABLE -> increase confidence strongly.
 *  - CASE C: Vision = APPROACHING + IMU = MOVING -> reduce confidence heavily.
 *  - CASE D: Vision = APPROACHING + No Audio -> no penalty.
 *  - CASE E: Audio = VEHICLE_HORN + Vision evidence -> emit fused ENVIRONMENTAL_SOUND.
 *  - CASE F: Audio only -> emit AUDIO-only ENVIRONMENTAL_SOUND.
 *
 * Outputs to [TemporalEventStore].
 */
class SensorFusionEngine(
    private val eventStore: TemporalEventStore,
    private val temporalWindowMs: Long = 2000L,
) {
    private val recentAudioEvents = ArrayDeque<AudioEvent>()
    private val recentVisionEvents = ArrayDeque<PulseEvent>()
    private val lock = Any()

    private val _latestFusedEventFlow = MutableStateFlow<FusedEvent?>(null)
    val latestFusedEventFlow: StateFlow<FusedEvent?> = _latestFusedEventFlow.asStateFlow()

    fun onVisionEvent(visionEvent: PulseEvent, motionState: PhoneMotionState) {
        synchronized(lock) {
            val now = visionEvent.timestamp
            cleanOldEvents(now)
            recentVisionEvents.addLast(visionEvent)

            // Find overlapping audio events
            val supportingAudio = recentAudioEvents.filter {
                now - it.timestamp <= temporalWindowMs
            }

            var fusedConfidence = visionEvent.confidence
            val sources = mutableListOf(EventSource.VISION)
            var reason = "Visual observation."

            val hasFootsteps = supportingAudio.any { it.soundType == SoundType.FOOTSTEPS }
            val highestAudioConf = supportingAudio.maxOfOrNull { it.confidence }

            if (visionEvent.eventType == EventType.PERSON_APPROACHING) {
                if (motionState == PhoneMotionState.CAMERA_MOVING) {
                    // CASE C: Camera is moving -> penalize strongly
                    fusedConfidence = (fusedConfidence - 0.4f).coerceAtLeast(0.1f)
                    sources.add(EventSource.IMU)
                    reason = "Visual approach confidence heavily reduced due to CAMERA_MOVING."
                } else if (hasFootsteps) {
                    // CASE B: Approach + Footsteps + Stable Camera -> boost strongly
                    fusedConfidence = (fusedConfidence + 0.25f).coerceAtMost(1.0f)
                    sources.add(EventSource.AUDIO)
                    sources.add(EventSource.IMU)
                    reason = "Visual approach strongly supported by audible FOOTSTEPS and stable camera."
                } else {
                    // CASE A: Approach + Stable Camera (No audio) -> boost slightly
                    fusedConfidence = (fusedConfidence + 0.1f).coerceAtMost(1.0f)
                    sources.add(EventSource.IMU)
                    reason = "Visual approach supported by stable camera."
                }
            } else {
                if (motionState == PhoneMotionState.CAMERA_MOVING) {
                    fusedConfidence = (fusedConfidence - 0.2f).coerceAtLeast(0.1f)
                    sources.add(EventSource.IMU)
                    reason = "Vision confidence reduced due to camera motion."
                }
            }

            val fusedEvent = FusedEvent(
                eventId = visionEvent.eventId,
                timestamp = visionEvent.timestamp,
                eventType = visionEvent.eventType,
                fusedConfidence = fusedConfidence,
                spatialPosition = visionEvent.spatialPosition,
                supportingSources = sources,
                reason = reason,
                visionConfidence = visionEvent.confidence,
                audioType = if (hasFootsteps) "FOOTSTEPS" else supportingAudio.firstOrNull()?.soundType?.name,
                audioConfidence = highestAudioConf,
                imuState = motionState.name
            )

            _latestFusedEventFlow.value = fusedEvent

            eventStore.addEvent(
                visionEvent.copy(
                    confidence = fusedConfidence,
                    source = if (sources.size > 1) EventSource.FUSED else EventSource.VISION
                )
            )
        }
    }

    fun onAudioEvent(audioEvent: AudioEvent) {
        synchronized(lock) {
            val now = audioEvent.timestamp
            cleanOldEvents(now)
            recentAudioEvents.addLast(audioEvent)

            // Check for overlapping vision evidence
            val supportingVision = recentVisionEvents.filter {
                now - it.timestamp <= temporalWindowMs
            }

            var fusedConfidence = audioEvent.confidence
            val sources = mutableListOf(EventSource.AUDIO)
            var reason = "Audio-only detection."

            // CASE E: VEHICLE_HORN / SIREN + Visual Evidence
            if (audioEvent.soundType == SoundType.VEHICLE_HORN || audioEvent.soundType == SoundType.SIREN) {
                if (supportingVision.isNotEmpty()) {
                    fusedConfidence = (fusedConfidence + 0.2f).coerceAtMost(1.0f)
                    sources.add(EventSource.VISION)
                    reason = "${audioEvent.soundType.name} supported by nearby visual presence."
                }
            }

            val fusedEvent = FusedEvent(
                eventId = audioEvent.eventId,
                timestamp = audioEvent.timestamp,
                eventType = EventType.ENVIRONMENTAL_SOUND,
                fusedConfidence = fusedConfidence,
                spatialPosition = null,
                supportingSources = sources,
                reason = reason,
                visionConfidence = supportingVision.maxOfOrNull { it.confidence },
                audioType = audioEvent.soundType.name,
                audioConfidence = audioEvent.confidence,
                imuState = null
            )

            _latestFusedEventFlow.value = fusedEvent

            eventStore.addEvent(
                PulseEvent(
                    eventId = fusedEvent.eventId,
                    timestamp = fusedEvent.timestamp,
                    trackId = "AUDIO_${audioEvent.soundType.name}",
                    eventType = EventType.ENVIRONMENTAL_SOUND,
                    confidence = fusedConfidence,
                    source = if (sources.size > 1) EventSource.FUSED else EventSource.AUDIO,
                    spatialPosition = null,
                    description = audioEvent.soundType.name
                )
            )
        }
    }

    private fun cleanOldEvents(nowMs: Long) {
        val cutoff = nowMs - temporalWindowMs
        while (recentAudioEvents.isNotEmpty() && recentAudioEvents.first.timestamp < cutoff) {
            recentAudioEvents.removeFirst()
        }
        while (recentVisionEvents.isNotEmpty() && recentVisionEvents.first.timestamp < cutoff) {
            recentVisionEvents.removeFirst()
        }
    }

    fun reset() {
        synchronized(lock) {
            recentAudioEvents.clear()
            recentVisionEvents.clear()
            _latestFusedEventFlow.value = null
        }
    }
}
