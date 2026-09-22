package com.saikiran.pulse.engine.situation

import com.saikiran.pulse.engine.events.EventSource
import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.perception.audio.AudioEvent
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

class SituationTrackerTest {

    private lateinit var situationTracker: SituationTracker

    @Before
    fun setUp() {
        situationTracker = SituationTracker()
    }

    @Test
    fun testSingleApproachingPerson_createsEscalatingSituation() {
        val spatial = SpatialPosition(SpatialZone.LEFT, SpatialDistance.NEAR, "on your left nearby", "left near")
        val event = PulseEvent(
            timestamp = System.currentTimeMillis(),
            trackId = "PERSON_1",
            eventType = EventType.PERSON_APPROACHING,
            confidence = 0.88f,
            source = EventSource.VISION,
            spatialPosition = spatial,
        )

        situationTracker.processEvent(event)

        val active = situationTracker.getActiveSituations()
        assertEquals(1, active.size)
        assertEquals("PERSON_1", active[0].trackId)
        assertEquals(SituationLifecycleState.ESCALATING, active[0].lifecycleState)
        assertEquals(MovementType.PERSON_APPROACHING, active[0].movementType)
    }

    @Test
    fun testPersonApproachesAndStops_updatesLifecycleToChanged() {
        val now = System.currentTimeMillis()
        val spatial = SpatialPosition(SpatialZone.CENTER, SpatialDistance.NEAR, "in front of you nearby", "center near")

        val approachEvent = PulseEvent(
            timestamp = now,
            trackId = "PERSON_1",
            eventType = EventType.PERSON_APPROACHING,
            confidence = 0.90f,
            spatialPosition = spatial,
        )
        situationTracker.processEvent(approachEvent)

        val stopEvent = PulseEvent(
            timestamp = now + 1000L,
            trackId = "PERSON_1",
            eventType = EventType.PERSON_STOPPED,
            confidence = 0.90f,
            spatialPosition = spatial,
        )
        situationTracker.processEvent(stopEvent)

        val active = situationTracker.getActiveSituations()
        assertEquals(1, active.size)
        assertEquals(SituationLifecycleState.CHANGED, active[0].lifecycleState)
        assertEquals(MovementType.STATIONARY, active[0].movementType)
    }

    @Test
    fun testPersonExits_resolvesSituation() {
        val now = System.currentTimeMillis()
        val enterEvent = PulseEvent(
            timestamp = now,
            trackId = "PERSON_1",
            eventType = EventType.PERSON_ENTERED_VIEW,
            confidence = 0.85f,
        )
        situationTracker.processEvent(enterEvent)

        val exitEvent = PulseEvent(
            timestamp = now + 2000L,
            trackId = "PERSON_1",
            eventType = EventType.PERSON_LEFT_VIEW,
            confidence = 0.85f,
        )
        situationTracker.processEvent(exitEvent)

        val active = situationTracker.getActiveSituations()
        assertTrue(active.isEmpty()) // Resolved situations removed from active list
    }

    @Test
    fun testPersonAndFootstepsCorrelation() {
        val now = System.currentTimeMillis()

        // 1. Audio event: FOOTSTEPS
        val audio = AudioEvent(
            timestamp = now,
            soundType = SoundType.FOOTSTEPS,
            label = "Footsteps",
            confidence = 0.80f,
        )
        situationTracker.processAudio(audio)

        // 2. Vision event: PERSON_1 APPROACHING
        val vision = PulseEvent(
            timestamp = now + 200L,
            trackId = "PERSON_1",
            eventType = EventType.PERSON_APPROACHING,
            confidence = 0.88f,
        )
        situationTracker.processEvent(vision)

        val active = situationTracker.getActiveSituations()
        assertEquals(1, active.size)
        assertTrue(active[0].supportingAudio.contains(SoundType.FOOTSTEPS))
    }

    @Test
    fun testVehicleHornWithoutVehicle_doesNotClaimVehicleVisible() {
        val now = System.currentTimeMillis()
        val horn = AudioEvent(
            timestamp = now,
            soundType = SoundType.VEHICLE_HORN,
            label = "Vehicle horn",
            confidence = 0.85f,
        )
        situationTracker.processAudio(horn)

        val active = situationTracker.getActiveSituations()
        // No person active, horn remains correlated audio event without fake visual vehicle
        assertTrue(active.isEmpty())
    }

    @Test
    fun testTwoSimultaneousSituations() {
        val now = System.currentTimeMillis()
        val p1 = PulseEvent(timestamp = now, trackId = "PERSON_1", eventType = EventType.PERSON_APPROACHING, confidence = 0.85f)
        val p2 = PulseEvent(timestamp = now, trackId = "PERSON_2", eventType = EventType.PERSON_STOPPED, confidence = 0.85f)

        situationTracker.processEvent(p1)
        situationTracker.processEvent(p2)

        val active = situationTracker.getActiveSituations()
        assertEquals(2, active.size)
    }
}
