package com.saikiran.pulse.audio.voice

/**
 * Lifecycle states for explicit voice command input.
 */
enum class VoiceCommandState {
    IDLE,
    LISTENING,
    PROCESSING,
    RESULT,
    ERROR,
}
