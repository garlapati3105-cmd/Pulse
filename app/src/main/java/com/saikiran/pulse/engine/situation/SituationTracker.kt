package com.saikiran.pulse.engine.situation

import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.engine.priority.PriorityEngine
import com.saikiran.pulse.perception.audio.AudioEvent
import com.saikiran.pulse.perception.audio.SoundType
import com.saikiran.pulse.perception.vision.movement.MovementType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Engine that correlates events, manages situation lifecycles, and tracks continuity.
 */
class SituationTracker(
    private val priorityEngine: PriorityEngine = PriorityEngine(),
    private val staleTimeoutMs: Long = 8000L,
) {
    private val lock = Any()
    private val activeSituations = mutableMapOf<String, Situation>()
    private val recentAudioList = mutableListOf<AudioEvent>()

    private val _situationsFlow = MutableStateFlow<List<Situation>>(emptyList())
    val situationsFlow: StateFlow<List<Situation>> = _situationsFlow.asStateFlow()

    fun processEvent(event: PulseEvent) {
        synchronized(lock) {
            val now = event.timestamp
            cleanStaleSituations(now)

            val trackId = event.trackId ?: "SYSTEM"
            val existing = activeSituations[trackId]

            val priorityDecision = priorityEngine.evaluate(event)
            val importance = priorityDecision.priority

            val supportingAudio = recentAudioList
                .filter { now - it.timestamp <= 3000L }
                .map { it.soundType }
                .distinct()

            val newSituation = if (existing == null) {
                Situation(
                    situationId = "SITUATION_${UUID.randomUUID().toString().take(6)}",
                    trackId = trackId,
                    lifecycleState = if (event.eventType == EventType.PERSON_APPROACHING) SituationLifecycleState.ESCALATING else SituationLifecycleState.STARTED,
                    movementType = mapEventTypeToMovementType(event.eventType),
                    spatialPosition = event.spatialPosition,
                    supportingAudio = supportingAudio,
                    confidence = event.confidence,
                    startTimeMs = now,
                    lastUpdatedTimeMs = now,
                    importance = importance
                )
            } else {
                val newLifecycle = when (event.eventType) {
                    EventType.PERSON_APPROACHING -> {
                        if (existing.movementType != MovementType.PERSON_APPROACHING) SituationLifecycleState.ESCALATING
                        else SituationLifecycleState.ACTIVE
                    }
                    EventType.PERSON_MOVING_AWAY -> SituationLifecycleState.DE_ESCALATING
                    EventType.PERSON_LEFT_VIEW, EventType.PERSON_TRACK_LOST -> SituationLifecycleState.RESOLVED
                    else -> SituationLifecycleState.CHANGED
                }

                existing.copy(
                    lifecycleState = newLifecycle,
                    movementType = mapEventTypeToMovementType(event.eventType),
                    spatialPosition = event.spatialPosition ?: existing.spatialPosition,
                    supportingAudio = (existing.supportingAudio + supportingAudio).distinct(),
                    confidence = event.confidence,
                    lastUpdatedTimeMs = now,
                    importance = importance
                )
            }

            if (newSituation.lifecycleState == SituationLifecycleState.RESOLVED) {
                activeSituations.remove(trackId)
            } else {
                activeSituations[trackId] = newSituation
            }

            _situationsFlow.value = activeSituations.values.toList()
        }
    }

    fun processAudio(audioEvent: AudioEvent) {
        synchronized(lock) {
            val now = audioEvent.timestamp
            recentAudioList.add(audioEvent)
            recentAudioList.removeAll { now - it.timestamp > 5000L }

            // Correlate with active situations if applicable
            if (audioEvent.soundType == SoundType.FOOTSTEPS) {
                activeSituations.entries.forEach { (key, sit) ->
                    if (!sit.supportingAudio.contains(SoundType.FOOTSTEPS)) {
                        activeSituations[key] = sit.copy(
                            supportingAudio = sit.supportingAudio + SoundType.FOOTSTEPS,
                            lastUpdatedTimeMs = now
                        )
                    }
                }
                _situationsFlow.value = activeSituations.values.toList()
            }
        }
    }

    fun getActiveSituations(): List<Situation> {
        synchronized(lock) {
            cleanStaleSituations(System.currentTimeMillis())
            return activeSituations.values.toList()
        }
    }

    private fun cleanStaleSituations(nowMs: Long) {
        val iterator = activeSituations.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastUpdatedTimeMs > staleTimeoutMs) {
                iterator.remove()
            }
        }
        _situationsFlow.value = activeSituations.values.toList()
    }

    private fun mapEventTypeToMovementType(eventType: EventType): MovementType {
        return when (eventType) {
            EventType.PERSON_APPROACHING -> MovementType.PERSON_APPROACHING
            EventType.PERSON_MOVING_AWAY -> MovementType.PERSON_MOVING_AWAY
            EventType.PERSON_PASSING_BY  -> MovementType.PERSON_PASSING_BY
            EventType.PERSON_STOPPED      -> MovementType.STATIONARY
            else                         -> MovementType.UNKNOWN
        }
    }

    fun reset() {
        synchronized(lock) {
            activeSituations.clear()
            recentAudioList.clear()
            _situationsFlow.value = emptyList()
        }
    }
}
