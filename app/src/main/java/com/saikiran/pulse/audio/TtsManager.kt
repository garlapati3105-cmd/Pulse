package com.saikiran.pulse.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Clean wrapper for Android's native TextToSpeech engine.
 *
 * Responsibilities:
 *  1. Initialises Android [TextToSpeech] asynchronously with default device locale fallback.
 *  2. Configures speech audio attributes for standard media/speech playback ([AudioManager.STREAM_MUSIC]).
 *  3. Speaks natural language summaries aloud on explicit user request or proactive alerts.
 *  4. Flushes speech queue to interrupt current speech when new requests arrive.
 *  5. Queues early requests if invoked prior to TTS initialization completing.
 *  6. Releases TTS native resources cleanly when component is destroyed.
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)

    @Volatile
    private var isInitialized: Boolean = false

    @Volatile
    private var pendingTextToSpeak: String? = null

    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_ID = "PULSE_SUMMARY_UTTERANCE"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set AudioAttributes for standard media speaker output
            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
            } catch (e: Exception) {
                Log.w(TAG, "Could not set TTS AudioAttributes", e)
            }

            // Try default device locale first (e.g. en_GB, en_IN, en_US)
            var result = tts?.setLanguage(Locale.getDefault())

            // Fallback to Locale.US or Locale.ENGLISH if default locale isn't ready
            if ((result == TextToSpeech.LANG_MISSING_DATA) || (result == TextToSpeech.LANG_NOT_SUPPORTED)) {
                result = tts?.setLanguage(Locale.US)
            }
            if ((result == TextToSpeech.LANG_MISSING_DATA) || (result == TextToSpeech.LANG_NOT_SUPPORTED)) {
                result = tts?.setLanguage(Locale.ENGLISH)
            }

            isInitialized = true
            Log.d(TAG, "TextToSpeech initialised successfully with language result $result.")

            // If a speak request arrived while TTS was initializing, speak it now
            pendingTextToSpeak?.let { pending ->
                pendingTextToSpeak = null
                speak(pending)
            }
        } else {
            Log.e(TAG, "TextToSpeech initialisation failed with status: $status")
            isInitialized = false
        }
    }

    /**
     * Speak text summary aloud. Interrupts any ongoing speech.
     *
     * @param text Natural language summary to speak.
     */
    fun speak(text: String) {
        if (text.isBlank()) return

        if (!isInitialized) {
            Log.w(TAG, "TTS not yet initialised; queueing pending text: \"$text\"")
            pendingTextToSpeak = text
            return
        }

        try {
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val status = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "tts.speak() with stream params returned $status; retrying with default audio attributes")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            } else {
                Log.d(TAG, "tts.speak() executed successfully for text: \"$text\"")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in TTS speak()", e)
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Stop currently active speech.
     */
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS", e)
        }
    }

    /**
     * Shut down and release native TTS resources.
     */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            Log.d(TAG, "TextToSpeech shutdown completed.")
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS", e)
        }
        tts = null
        isInitialized = false
        pendingTextToSpeak = null
    }
}
