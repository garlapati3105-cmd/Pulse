package com.saikiran.pulse.device

import org.junit.Assert
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeviceCapabilityManagerTest {

    @Test
    fun testDeviceCapabilitiesDataModel() {
        val caps = DeviceCapabilities(
            deviceModel = "iQOO 12",
            manufacturer = "iQOO",
            androidVersion = 14,
            sdkInt = 34,
            hasCamera = true,
            hasMicrophone = true,
            hasGyroscope = true,
            hasLinearAcceleration = true,
            hasAccelerometer = true,
            hasMagnetometer = true,
            hasProximitySensor = true,
            hasLightSensor = true,
            hasHaptics = true,
            hasAmplitudeControl = true,
            isOnDeviceSpeechAvailable = true,
            totalMemoryMb = 12000L,
            isLowRamDevice = false,
            supportedAbis = listOf("arm64-v8a"),
        )

        val summary = caps.toSummaryString()
        assertNotNull(summary)
        Assert.assertTrue(summary.contains("iQOO 12"))
    }
}
