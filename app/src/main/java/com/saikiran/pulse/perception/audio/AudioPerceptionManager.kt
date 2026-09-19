package com.saikiran.pulse.perception.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.util.Log
import androidx.core.content.ContextCompat
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.audio.classifier.Classifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * On-device Environmental Audio Perception Manager using TensorFlow Lite Audio Classifier (YAMNet).
 *
 * Requirements & Architecture:
 *  1. Micro-latency audio capture using Android [AudioRecord] configured via YAMNet audio classifier.
 *  2. On-device YAMNet inference on background thread.
 *  3. Monitored Categories: Speech, Footsteps, Vehicle, Horn, Door, Doorbell, Alarm, Siren.
 *  4. Temporal Debouncing: Requires sound category to be probable across [consecutiveWindowsThreshold] (3) windows.
 *  5. Off-UI Thread Execution: Recording and classification run entirely on a background executor thread.
 *  6. Does NOT trigger TTS automatically (Milestone 7A perception only).
 */
class AudioPerceptionManager(
    private val context: Context,
    private val confidenceThreshold: Float = 0.35f,
    private val consecutiveWindowsThreshold: Int = 3,
) {
    private val _stateFlow = MutableStateFlow(AudioPerceptionState.UNINITIALIZED)
    val stateFlow: StateFlow<AudioPerceptionState> = _stateFlow.asStateFlow()

    private val _latestAudioEventFlow = MutableStateFlow<AudioEvent?>(null)
    val latestAudioEventFlow: StateFlow<AudioEvent?> = _latestAudioEventFlow.asStateFlow()

    private val _audioEventsHistoryFlow = MutableStateFlow<List<AudioEvent>>(emptyList())
    val audioEventsHistoryFlow: StateFlow<List<AudioEvent>> = _audioEventsHistoryFlow.asStateFlow()

    private var audioClassifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var recordingExecutor: ScheduledExecutorService? = null

    @Volatile
    private var isRecording = false

    private val consecutiveCountMap = mutableMapOf<SoundType, Int>()
    private val historyList = mutableListOf<AudioEvent>()

    companion object {
        private const val TAG = "AudioPerceptionManager"
        private const val MODEL_FILE = "yamnet.tflite"
    }

    /**
     * Start environmental audio perception.
     * Must be called when RECORD_AUDIO permission is granted.
     */
    fun start() {
        if (isRecording) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot start AudioPerceptionManager: RECORD_AUDIO permission denied")
            _stateFlow.value = AudioPerceptionState.NO_PERMISSION
            return
        }

        recordingExecutor = Executors.newSingleThreadScheduledExecutor()
        recordingExecutor?.execute {
            setupClassifierAndRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupClassifierAndRecording() {
        try {
            consecutiveCountMap.clear()

            val classifier = AudioClassifier.createFromFile(context, MODEL_FILE)
            audioClassifier = classifier

            val record = classifier.createAudioRecord()
            audioRecord = record

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                _stateFlow.value = AudioPerceptionState.ERROR
                return
            }

            val tensorAudio = classifier.createInputTensorAudio()

            record.startRecording()
            isRecording = true
            _stateFlow.value = AudioPerceptionState.LISTENING
            Log.d(TAG, "AudioPerceptionManager started recording successfully with YAMNet")

            // Continuous audio capture & classification loop off UI thread
            while (isRecording && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                tensorAudio.load(record)
                val output = classifier.classify(tensorAudio)
                processClassificationResult(output, System.currentTimeMillis())
                Thread.sleep(100L) // 100ms audio sampling window (~10 Hz sampling rate)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioClassifier / AudioRecord", e)
            _stateFlow.value = AudioPerceptionState.ERROR
        }
    }

    private fun processClassificationResult(classifications: List<Classifications>, timestampMs: Long) {
        if (classifications.isEmpty()) return

        val categories = classifications.firstOrNull()?.categories ?: return

        // Find highest confidence category matching target YAMNet categories
        var topMatchedType: SoundType = SoundType.UNKNOWN
        var topMatchedCategory: Category? = null
        var maxConfidence = 0f

        for (category in categories) {
            val label = category.label
            val score = category.score

            if (score < confidenceThreshold) continue

            val soundType = mapLabelToSoundType(label)
            if (soundType != SoundType.UNKNOWN && score > maxConfidence) {
                maxConfidence = score
                topMatchedType = soundType
                topMatchedCategory = category
            }
        }

        // Apply Temporal Debouncing / Smoothing
        for (type in SoundType.entries) {
            if (type == topMatchedType && topMatchedCategory != null) {
                val currentCount = consecutiveCountMap.getOrDefault(type, 0) + 1
                consecutiveCountMap[type] = currentCount

                // Emit AudioEvent ONLY when sound category persists across consecutiveWindowsThreshold
                if (currentCount == consecutiveWindowsThreshold) {
                    val event = AudioEvent(
                        timestamp = timestampMs,
                        soundType = topMatchedType,
                        label = topMatchedCategory.label,
                        confidence = topMatchedCategory.score,
                    )

                    _latestAudioEventFlow.value = event
                    synchronized(historyList) {
                        historyList.add(event)
                        if (historyList.size > 20) historyList.removeAt(0)
                        _audioEventsHistoryFlow.value = historyList.toList()
                    }

                    Log.d(TAG, "Emitted debounced AudioEvent: ${event.soundType} (${event.label}, ${(event.confidence * 100).toInt()}%)")
                }
            } else {
                // Decay consecutive count for inactive categories
                consecutiveCountMap[type] = 0
            }
        }
    }

    /**
     * Exact YAMNet Label Map Matching to Target Sound Types.
     */
    private fun mapLabelToSoundType(label: String): SoundType {
        val lower = label.lowercase()
        return when {
            lower.contains("speech") || lower.contains("conversation") || lower.contains("shouting") || lower.contains("screaming") || lower.contains("whispering") || lower.contains("laughter") -> SoundType.SPEECH
            lower.contains("footstep") || lower.contains("walk") || lower.contains("run") -> SoundType.FOOTSTEPS
            lower.contains("horn") || lower.contains("honk") -> SoundType.VEHICLE_HORN
            lower.contains("vehicle") || lower.contains("car") || lower.contains("truck") || lower.contains("bus") || lower.contains("motorcycle") || lower.contains("traffic") -> SoundType.VEHICLE
            lower.contains("doorbell") || lower.contains("ding-dong") || lower.contains("chime") -> SoundType.DOORBELL
            lower.contains("door") || lower.contains("slam") || lower.contains("knock") -> SoundType.DOOR
            lower.contains("siren") || lower.contains("ambulance") || lower.contains("police car") || lower.contains("fire engine") -> SoundType.SIREN
            lower.contains("alarm") || lower.contains("buzzer") || lower.contains("smoke detector") -> SoundType.ALARM
            else -> SoundType.UNKNOWN
        }
    }

    /**
     * Stop and release AudioRecord and AudioClassifier native resources.
     */
    fun stop() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }

        try {
            audioClassifier?.close()
            audioClassifier = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing AudioClassifier", e)
        }

        recordingExecutor?.shutdown()
        recordingExecutor = null
        _stateFlow.value = AudioPerceptionState.PAUSED
        Log.d(TAG, "AudioPerceptionManager stopped successfully")
    }
}
