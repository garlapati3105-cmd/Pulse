package com.saikiran.pulse.perception.vision.movement

import android.graphics.RectF
import com.saikiran.pulse.perception.sensors.PhoneMotionState
import com.saikiran.pulse.perception.vision.PersonBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

private fun createTestRect(left: Float, top: Float, right: Float, bottom: Float): RectF {
    return RectF().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}

class MovementAnalyzerTest {

    private lateinit var analyzer: MovementAnalyzer

    @Before
    fun setUp() {
        analyzer = MovementAnalyzer()
    }

    @Test
    fun testStationaryPersonRaisingArms_isNotApproaching() {
        // Person raising arms: height increases vertically, but width does not expand proportionally
        val trackId = "PERSON_1"
        var time = 1000L

        // Initial frames (height 200, width 100)
        for (i in 0 until 5) {
            val box = PersonBox(trackId, createTestRect(100f, 100f, 200f, 300f), 0.85f)
            analyzer.analyze(listOf(box), 1080, 1920, PhoneMotionState.CAMERA_STABLE, time)
            time += 100L
        }

        // Arm raising frames (height increases to 235, but width remains 100)
        var lastResult: MovementEvent? = null
        for (i in 0 until 10) {
            val box = PersonBox(trackId, createTestRect(100f, 65f, 200f, 300f), 0.85f) // height = 235 (+17.5%), width = 100 (0% change)
            val results = analyzer.analyze(listOf(box), 1080, 1920, PhoneMotionState.CAMERA_STABLE, time)
            lastResult = results[trackId]
            time += 100L
        }

        // Must NOT classify as APPROACHING
        assertNotEquals(MovementType.PERSON_APPROACHING, lastResult?.movementType)
    }

    @Test
    fun testProportional2DScale_isApproaching() {
        // Actual approach: BOTH height AND width expand proportionally frame by frame
        val trackId = "PERSON_1"
        var time = 1000L

        var lastResult: MovementEvent? = null

        // Progressive approach over 16 frames (width 100 -> 160, height 200 -> 320)
        for (i in 0 until 16) {
            val scale = 1.0f + (i * 0.04f) // 4% growth per frame
            val w = 100f * scale
            val h = 200f * scale
            val left = 500f - w / 2f
            val right = 500f + w / 2f
            val top = 500f - h / 2f
            val bottom = 500f + h / 2f

            val box = PersonBox(trackId, createTestRect(left, top, right, bottom), 0.85f)
            val results = analyzer.analyze(listOf(box), 1080, 1920, PhoneMotionState.CAMERA_STABLE, time)
            lastResult = results[trackId]
            time += 100L
        }

        assertEquals(MovementType.PERSON_APPROACHING, lastResult?.movementType)
    }
}
