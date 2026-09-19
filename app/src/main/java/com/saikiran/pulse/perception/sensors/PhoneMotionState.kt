package com.saikiran.pulse.perception.sensors

/**
 * Camera phone motion state determined by on-device IMU sensors (Gyroscope & Linear Acceleration).
 */
enum class PhoneMotionState {
    CAMERA_STABLE,
    CAMERA_MOVING,
}
