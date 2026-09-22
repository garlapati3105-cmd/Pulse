package com.saikiran.pulse.device

/**
 * Immutable hardware capability snapshot for Phone-First hardware adaptation.
 */
data class DeviceCapabilities(
    val deviceModel: String,
    val manufacturer: String,
    val androidVersion: Int,
    val sdkInt: Int,
    val hasCamera: Boolean,
    val hasMicrophone: Boolean,
    val hasGyroscope: Boolean,
    val hasLinearAcceleration: Boolean,
    val hasAccelerometer: Boolean,
    val hasMagnetometer: Boolean,
    val hasProximitySensor: Boolean,
    val hasLightSensor: Boolean,
    val hasHaptics: Boolean,
    val hasAmplitudeControl: Boolean,
    val isOnDeviceSpeechAvailable: Boolean,
    val totalMemoryMb: Long,
    val isLowRamDevice: Boolean,
    val supportedAbis: List<String>,
) {
    fun toSummaryString(): String {
        return "Device: $manufacturer $deviceModel (Android $androidVersion, API $sdkInt)\n" +
                "RAM: ${totalMemoryMb}MB (LowRam: $isLowRamDevice)\n" +
                "Sensors: Gyro=$hasGyroscope, Accel=$hasAccelerometer, LinearAccel=$hasLinearAcceleration, Mag=$hasMagnetometer\n" +
                "Hardware: Camera=$hasCamera, Mic=$hasMicrophone, Haptics=$hasHaptics (Amplitude=$hasAmplitudeControl)\n" +
                "Speech: On-Device Speech=$isOnDeviceSpeechAvailable\n" +
                "ABIs: ${supportedAbis.joinToString()}"
    }
}
