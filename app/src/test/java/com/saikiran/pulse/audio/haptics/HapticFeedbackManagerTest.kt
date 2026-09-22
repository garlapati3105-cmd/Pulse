package com.saikiran.pulse.audio.haptics

import com.saikiran.pulse.engine.priority.PriorityLevel
import org.junit.Assert
import org.junit.Test

class HapticFeedbackManagerTest {

    @Test
    fun testPriorityLevelsExistForHaptics() {
        Assert.assertNotNull(PriorityLevel.LOW)
        Assert.assertNotNull(PriorityLevel.MEDIUM)
        Assert.assertNotNull(PriorityLevel.HIGH)
    }
}
