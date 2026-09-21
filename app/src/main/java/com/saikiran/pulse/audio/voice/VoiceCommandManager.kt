package com.saikiran.pulse.audio.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * On-Device Voice Command Manager for explicit hands-free control.
 *
 * Architecture & Requirements:
 *  1. Local-Only Speech Recognition: Strictly uses [SpeechRecognizer.createOnDeviceSpeechRecognizer].
 *  2. Availability Check: Checks [SpeechRecognizer.isOnDeviceRecognitionAvailable].
 *  3. No Cloud Fallback: Refuses silent fallback to remote/cloud speech services.
 *  4. Graceful Error Handling: If local recognition is unavailable or times out, communicates failure gracefully.
 *  5. Single-shot explicit session (begins ONLY on explicit user trigger, closes mic immediately on end).
 *  6. Main-thread execution for all Android SpeechRecognizer API calls.
 *  7. Coordinates microphone access by pausing environmental audio perception during active voice input.
 */
class VoiceCommandManager(
    private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _stateFlow = MutableStateFlow(VoiceCommandState.IDLE)
    val stateFlow: StateFlow<VoiceCommandState> = _stateFlow.asStateFlow()

    private val _recognizedTextFlow = MutableStateFlow("")
    val recognizedTextFlow: StateFlow<String> = _recognizedTextFlow.asStateFlow()

    private val _lastCommandFlow = MutableStateFlow<VoiceCommand?>(null)
    val lastCommandFlow: StateFlow<VoiceCommand?> = _lastCommandFlow.asStateFlow()

    private val _statusMessageFlow = MutableStateFlow("")
    val statusMessageFlow: StateFlow<String> = _statusMessageFlow.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    var isOnDeviceAvailable: Boolean = false
        private set

    companion object {
        private const val TAG = "VoiceCommandManager"
    }

    init {
        checkOnDeviceAvailability()
    }

    /**
     * Check if on-device speech recognition is supported on this Android device.
     */
    fun checkOnDeviceAvailability(): Boolean {
        isOnDeviceAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            false
        }

        val msg = if (isOnDeviceAvailable) {
            "On-device speech recognition is available."
        } else {
            "On-device speech recognition unavailable on this device."
        }

        _statusMessageFlow.value = msg
        Log.i(TAG, "On-device speech availability check: $isOnDeviceAvailable ($msg)")
        return isOnDeviceAvailable
    }

    /**
     * Start a single-shot voice recognition session on explicit user action.
     */
    fun startListening(
        onListeningStarted: () -> Unit,
        onListeningEnded: () -> Unit,
        onCommandExecuted: (String, VoiceCommand) -> Unit,
    ) {
        mainHandler.post {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                _stateFlow.value = VoiceCommandState.ERROR
                _statusMessageFlow.value = "Microphone permission denied."
                Log.w(TAG, "Cannot start voice command: RECORD_AUDIO permission denied")
                return@post
            }

            if (!isOnDeviceAvailable && !checkOnDeviceAvailability()) {
                _stateFlow.value = VoiceCommandState.ERROR
                _statusMessageFlow.value = "On-device voice input is temporarily unavailable."
                Log.w(TAG, "On-device speech recognition unavailable; cloud fallback strictly prohibited.")
                return@post
            }

            // Step 1: Coordinate microphone access (Pause Environmental Audio Classifier)
            onListeningStarted()

            // Step 2: Clean up previous recognizer
            destroyRecognizer()

            _stateFlow.value = VoiceCommandState.LISTENING
            _statusMessageFlow.value = "Listening... Speak now!"
            _recognizedTextFlow.value = ""

            // Allow 250ms delay for background AudioRecord to release microphone hardware cleanly
            mainHandler.postDelayed({
                try {
                    val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isOnDeviceAvailable) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }

                    speechRecognizer = recognizer

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }

                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _stateFlow.value = VoiceCommandState.LISTENING
                            _statusMessageFlow.value = "Listening... Speak now!"
                            Log.d(TAG, "SpeechRecognizer onReadyForSpeech")
                        }

                        override fun onBeginningOfSpeech() {
                            _stateFlow.value = VoiceCommandState.LISTENING
                            _statusMessageFlow.value = "Listening... Hearing speech..."
                            Log.d(TAG, "SpeechRecognizer onBeginningOfSpeech")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            _stateFlow.value = VoiceCommandState.PROCESSING
                            _statusMessageFlow.value = "Processing command..."
                            Log.d(TAG, "SpeechRecognizer onEndOfSpeech")
                        }

                        override fun onError(error: Int) {
                            val errorReason = mapErrorToString(error)
                            Log.e(TAG, "SpeechRecognizer onError: $errorReason ($error)")

                            _stateFlow.value = VoiceCommandState.ERROR
                            _statusMessageFlow.value = "On-device voice input error: $errorReason"

                            // Restore Environmental Audio Classifier & cleanup
                            onListeningEnded()
                            destroyRecognizer()
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            val command = CommandParser.parse(text)

                            _recognizedTextFlow.value = text
                            _lastCommandFlow.value = command
                            _stateFlow.value = VoiceCommandState.RESULT
                            _statusMessageFlow.value = if (command != VoiceCommand.UNKNOWN) {
                                "Recognized: \"$text\" → $command"
                            } else {
                                "Recognized: \"$text\" → Unrecognized Command"
                            }

                            Log.d(TAG, "Voice Command result: \"$text\" → $command")

                            // Execute Command Callback
                            onCommandExecuted(text, command)

                            // Restore Environmental Audio Classifier & cleanup
                            onListeningEnded()
                            destroyRecognizer()
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    recognizer.startListening(intent)

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start SpeechRecognizer session", e)
                    _stateFlow.value = VoiceCommandState.ERROR
                    _statusMessageFlow.value = "On-device voice input unavailable: ${e.message}"
                    onListeningEnded()
                    destroyRecognizer()
                }
            }, 250L)
        }
    }

    /**
     * Stop active voice recognition.
     */
    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping SpeechRecognizer", e)
            }
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying SpeechRecognizer", e)
        }
        speechRecognizer = null
    }

    /**
     * Destroy and release speech recognizer resources cleanly.
     */
    fun destroy() {
        mainHandler.post {
            destroyRecognizer()
            _stateFlow.value = VoiceCommandState.IDLE
        }
    }

    private fun mapErrorToString(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable"
            else -> "Error code: $error"
        }
    }
}
