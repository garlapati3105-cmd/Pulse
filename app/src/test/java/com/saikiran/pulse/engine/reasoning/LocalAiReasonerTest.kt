package com.saikiran.pulse.engine.reasoning

import com.saikiran.pulse.engine.change.ChangeEvent
import com.saikiran.pulse.engine.events.EventSource
import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.engine.evidence.AudioEventState
import com.saikiran.pulse.engine.evidence.PersonState
import com.saikiran.pulse.engine.evidence.SituationState
import com.saikiran.pulse.perception.audio.SoundType
import com.saikiran.pulse.perception.vision.movement.MovementType
import com.saikiran.pulse.perception.vision.spatial.SpatialDistance
import com.saikiran.pulse.perception.vision.spatial.SpatialPosition
import com.saikiran.pulse.perception.vision.spatial.SpatialZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalAiReasonerTest {

    private lateinit var reasoner: LocalAiReasoner

    @Before
    fun setUp() {
        reasoner = LocalAiReasoner()
    }

    @Test
    fun testOneApproachingPerson() {
        val spatial = SpatialPosition(SpatialZone.LEFT, SpatialDistance.NEAR, "on your left nearby", "left near")
        val person = PersonState("PERSON_1", MovementType.PERSON_APPROACHING, spatial, 0.90f)
        val event = PulseEvent(
            timestamp = System.currentTimeMillis(),
            trackId = "PERSON_1",
            eventType = EventType.PERSON_APPROACHING,
            confidence = 0.90f,
            source = EventSource.VISION,
            spatialPosition = spatial,
        )
        val situation = SituationState(
            activePeople = listOf(person),
            recentEvents = listOf(event),
        )

        val result = reasoner.summarizeSituation(situation)
        assertTrue(result.text.contains("approaching"))
        assertFalse(result.isLowConfidence)
    }

    @Test
    fun testHornWithoutVisibleVehicle_doesNotClaimVehicleVisible() {
        val audioState = AudioEventState(SoundType.VEHICLE_HORN, "Vehicle horn", 0.88f, System.currentTimeMillis())
        val audioEvent = PulseEvent(
            timestamp = System.currentTimeMillis(),
            trackId = "AUDIO_VEHICLE_HORN",
            eventType = EventType.ENVIRONMENTAL_SOUND,
            confidence = 0.88f,
            source = EventSource.AUDIO,
            description = "VEHICLE_HORN",
        )
        val situation = SituationState(
            environmentalEvents = listOf(audioState),
            recentEvents = listOf(audioEvent),
        )

        val result = reasoner.summarizeSituation(situation)
        assertTrue(result.text.contains("vehicle horn"))
        assertFalse(result.text.lowercase().contains("vehicle is approaching"))
        assertFalse(result.text.lowercase().contains("visible vehicle"))
    }

    @Test
    fun testEmptyEvidence_returnsNoActiveEvents() {
        val situation = SituationState()
        val result = reasoner.summarizeSituation(situation)
        assertEquals("There are no active person or sound events right now.", result.text)
    }

    @Test
    fun testLowConfidenceEvidence_returnsCautiousSummary() {
        val event = PulseEvent(
            timestamp = System.currentTimeMillis(),
            trackId = "PERSON_1",
            eventType = EventType.PERSON_APPROACHING,
            confidence = 0.35f, // Below 0.45 threshold
            source = EventSource.VISION,
        )

        val result = reasoner.summarizeRecentEvents(listOf(event))
        assertTrue(result.isLowConfidence)
        assertTrue(result.text.contains("low") || result.text.contains("uncertain") || result.text.contains("may have"))
    }

    @Test
    fun testFallbackBehavior_onException() {
        val failingEngine = object : LocalReasoningEngine {
            override fun summarizeSituation(situation: SituationState): ReasoningResult {
                throw RuntimeException("Local inference error")
            }
            override fun summarizeRecentEvents(events: List<PulseEvent>): ReasoningResult {
                throw RuntimeException("Inference error")
            }
            override fun explainChange(changes: List<ChangeEvent>): ReasoningResult = TODO()
            override fun answerQuestion(question: String, situation: SituationState): ReasoningResult = TODO()
            override fun resetContext() {}
            override fun isAvailable(): Boolean = false
        }

        val safeReasoner = LocalAiReasoner(fallbackEngine = failingEngine)
        val situation = SituationState()

        // Should return empty evidence safe response without crashing
        val result = safeReasoner.summarizeSituation(situation)
        assertEquals("There are no active person or sound events right now.", result.text)
    }
}
