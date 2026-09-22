package com.saikiran.pulse.audio.voice

/**
 * Supported deterministic voice commands for Pulse.
 */
enum class VoiceCommand {
    SHOW_CURRENT_SITUATION,
    SHOW_RECENT_EVENT_SUMMARY,
    SHOW_CHANGES,
    REPEAT_LAST_RESPONSE,
    MUTE_PROACTIVE_VOICE,
    UNMUTE_PROACTIVE_VOICE,
    IS_ANYONE_APPROACHING,
    WHERE_IS_PERSON,
    WHAT_DID_YOU_HEAR,
    ANYTHING_IMPORTANT,
    UNKNOWN,
}
