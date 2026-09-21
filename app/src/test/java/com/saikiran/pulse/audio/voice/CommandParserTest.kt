package com.saikiran.pulse.audio.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {

    @Test
    fun testParseSupportedCommands() {
        assertEquals(VoiceCommand.SHOW_CURRENT_SITUATION, CommandParser.parse("What's happening?"))
        assertEquals(VoiceCommand.SHOW_CURRENT_SITUATION, CommandParser.parse("what is happening"))
        assertEquals(VoiceCommand.SHOW_RECENT_EVENT_SUMMARY, CommandParser.parse("What just happened?"))
        assertEquals(VoiceCommand.SHOW_CHANGES, CommandParser.parse("what changed"))
        assertEquals(VoiceCommand.REPEAT_LAST_RESPONSE, CommandParser.parse("repeat that"))
        assertEquals(VoiceCommand.MUTE_PROACTIVE_VOICE, CommandParser.parse("mute"))
        assertEquals(VoiceCommand.UNMUTE_PROACTIVE_VOICE, CommandParser.parse("unmute"))
    }

    @Test
    fun testParseUnsupportedCommand_returnsUnknown() {
        assertEquals(VoiceCommand.UNKNOWN, CommandParser.parse("open YouTube"))
        assertEquals(VoiceCommand.UNKNOWN, CommandParser.parse("play music"))
    }
}
