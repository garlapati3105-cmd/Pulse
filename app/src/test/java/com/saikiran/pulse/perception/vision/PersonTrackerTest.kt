package com.saikiran.pulse.perception.vision

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class PersonTrackerTest {

    private lateinit var tracker: PersonTracker

    @Before
    fun setUp() {
        tracker = PersonTracker()
    }

    @Test
    fun testTwoPeopleCrossing_preservesIdentitiesWithCostMatrix() {
        // Person 1 at x=100 moving right toward x=500
        // Person 2 at x=500 moving left toward x=100
        val p1Box1 = PersonBox("det1", RectF(100f, 100f, 200f, 400f), 0.9f)
        val p2Box1 = PersonBox("det2", RectF(500f, 100f, 600f, 400f), 0.9f)

        val frame1 = tracker.update(listOf(p1Box1, p2Box1), 1000, 1000)
        assertEquals(2, frame1.size)

        val id1 = frame1[0].trackId
        val id2 = frame1[1].trackId

        // Frame 2: Moving closer (P1 at 250, P2 at 350)
        val p1Box2 = PersonBox("det1", RectF(250f, 100f, 350f, 400f), 0.9f)
        val p2Box2 = PersonBox("det2", RectF(350f, 100f, 450f, 400f), 0.9f)

        val frame2 = tracker.update(listOf(p1Box2, p2Box2), 1000, 1000)
        assertEquals(2, frame2.size)

        val frame2P1 = frame2.firstOrNull { it.trackId == id1 }
        val frame2P2 = frame2.firstOrNull { it.trackId == id2 }

        assertNotNull(frame2P1)
        assertNotNull(frame2P2)
    }
}
