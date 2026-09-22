package com.saikiran.pulse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {

    @Test
    fun testOnboardingFeatureString() {
        val title = "Welcome to Pulse 👁️🎙️📱"
        assertTrue(title.contains("Pulse"))
    }
}
