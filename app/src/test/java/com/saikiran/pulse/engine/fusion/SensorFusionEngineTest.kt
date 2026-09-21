package com.saikiran.pulse.engine.fusion

import com.saikiran.pulse.engine.events.EventSource
import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.engine.events.TemporalEventStore
import com.saikiran.pulse.perception.audio.AudioEvent
import com.saikiran.pulse.perception.audio.SoundType
import com.saikiran.pulse.perception.sensors.PhoneMotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SensorFusionEngineTest {

    private lateinit var eventStore: TemporalEventStore
    private lateinit var fusionEngine: SensorFusionEngine

    @Before
    fun setUp() {
        eventStore = TemporalEventStore()
        fusionEngine = SensorFusionEngine(eventStore)
    }

    @Test
    fun testVehicleHorn_WithoutVehicleVision_RemainsAudioOnly() {
        // Audio event: VEHICLE_HORN
        val now = System.currentTimeMillis()
        val audioEvent = AudioEvent(
            timestamp = now,
            soundType = SoundType.VEHICLE_HORN,
            label = "Vehicle horn",
            confidence = 0.85f,
        )

        fusionEngine.onAudioEvent(audioEvent)

        val latest = fusionEngine.latestFusedEventFlow.value
        assertEquals(EventType.ENVIRONMENTAL_SOUND, latest?.eventType)
        assertEquals(listOf(EventSource.AUDIO), latest?.supportingSources)
        assertFalse(latest?.reason?.contains("visual presence") ?: true)
    }

    @Test
    fun testApproach_WithFootsteps_BoostsConfidenceConservatively() {
        val now = System.currentTimeMillis()

        // 1. Audio event: FOOTSTEPS
        val audioEvent = AudioEvent(
            timestamp = now - 200L,
            soundType = SoundType.FOOTSTEPS,
            label = "Footsteps",
            confidence = 0.80f,
        )
        fusionEngine.onAudioEvent(audioEvent)

        // 2. Vision event: PERSON_APPROACHING
        val visionEvent = PulseEvent(
            timestamp = now,
            trackId = "PERSON_1",
            eventType = EventType.PERSON_APPROACHING,
            confidence = 0.80f,
            source = EventSource.VISION,
        )
        fusionEngine.onVisionEvent(visionEvent, PhoneMotionState.CAMERA_STABLE)

        val latest = fusionEngine.latestFusedEventFlow.value
        assertEquals(EventType.PERSON_APPROACHING, latest?.eventType)
        assertTrue(latest?.fusedConfidence ?: 0f > 0.80f)
        assertTrue(latest?.fusedConfidence ?: 1.0f <= 0.92f) // Conservative cap at 0.92
    }
}
