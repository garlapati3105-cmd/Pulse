package com.saikiran.pulse.perception.audio

/**
 * Status state for environmental audio perception layer.
 */
enum class AudioPerceptionState {
    UNINITIALIZED,
    LISTENING,
    PAUSED,
    NO_PERMISSION,
    ERROR,
}
