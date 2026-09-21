package com.saikiran.pulse.audio.voice

import java.util.Locale

/**
 * Deterministic voice command parser.
 *
 * Normalizes recognized speech (lowercase, trim, punctuation removal) and maps it
 * to supported [VoiceCommand] enums without using an LLM.
 */
object CommandParser {

    /**
     * Parse raw recognized text into a deterministic [VoiceCommand].
     */
    fun parse(rawText: String): VoiceCommand {
        if (rawText.isBlank()) return VoiceCommand.UNKNOWN

        // Normalize text: lowercase, remove punctuation, trim extra whitespace
        val normalized = rawText
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9\\s']"), "")
            .trim()
            .replace(Regex("\\s+"), " ")

        return when {
            // A: Current situation / What's happening
            normalized.contains("what's happening") ||
                    normalized.contains("what is happening") ||
                    normalized.contains("whats happening") ||
                    normalized.contains("happening") ||
                    normalized.contains("current situation") ||
                    normalized.contains("situation") -> VoiceCommand.SHOW_CURRENT_SITUATION

            // B: What just happened
            normalized.contains("what just happened") ||
                    normalized.contains("what happened") ||
                    normalized.contains("happened") ||
                    normalized.contains("tell me what happened") -> VoiceCommand.SHOW_RECENT_EVENT_SUMMARY

            // C: What changed
            normalized.contains("what changed") ||
                    normalized.contains("changed") ||
                    normalized.contains("tell me what changed") ||
                    normalized.contains("what has changed") -> VoiceCommand.SHOW_CHANGES

            // D: Repeat
            normalized.contains("repeat that") ||
                    normalized.contains("say again") ||
                    normalized.contains("repeat") -> VoiceCommand.REPEAT_LAST_RESPONSE

            // E: Mute / Stop talking
            normalized.contains("stop talking") ||
                    normalized.contains("be quiet") ||
                    normalized.contains("mute") ||
                    normalized.contains("quiet") -> VoiceCommand.MUTE_PROACTIVE_VOICE

            // F: Unmute / Start speaking
            normalized.contains("unmute") ||
                    normalized.contains("start speaking") ||
                    normalized.contains("speak") -> VoiceCommand.UNMUTE_PROACTIVE_VOICE

            else -> VoiceCommand.UNKNOWN
        }
    }
}
