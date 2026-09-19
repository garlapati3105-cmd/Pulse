package com.saikiran.pulse.perception.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.saikiran.pulse.engine.events.SemanticEventProcessor
import com.saikiran.pulse.engine.events.TemporalEventStore
import com.saikiran.pulse.perception.sensors.PhoneMotionState
import com.saikiran.pulse.perception.sensors.SensorMotionMonitor
import com.saikiran.pulse.perception.vision.movement.MovementAnalyzer
import com.saikiran.pulse.perception.vision.movement.MovementType
import com.saikiran.pulse.perception.vision.spatial.SpatialPosition
import java.util.concurrent.atomic.AtomicLong

/**
 * Wraps the MediaPipe ObjectDetector and filters detections to "person" class only.
 *
 * IMPORTANT: [setup] must be called on the SAME thread that will call [detect].
 * In practice that means both should run on the ImageAnalysis executor thread.
 * Do NOT call setup() on the main/UI thread when using LIVE_STREAM mode.
 */
class PersonDetector(
    private val context: Context,
    private val listener: PersonDetectionListener,
    private val sensorMotionMonitor: SensorMotionMonitor? = null,
) {
    private var objectDetector: ObjectDetector? = null
    private val lastTimestampMs = AtomicLong(0L)
    private val personTracker = PersonTracker()
    private val movementAnalyzer = MovementAnalyzer()

    /** In-memory rolling temporal event store. */
    val eventStore = TemporalEventStore()
    private val eventProcessor = SemanticEventProcessor(eventStore)

    companion object {
        private const val TAG = "PersonDetector"
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val PERSON_LABEL = "person"
        private const val CONFIDENCE_THRESHOLD = 0.25f   // Optimized for side profiles and close-up upper bodies
        private const val MAX_RESULTS = 5

        init {
            try {
                System.loadLibrary("mediapipe_tasks_vision_jni")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to load mediapipe_tasks_vision_jni", e)
            }
        }
    }

    /** Returns true if ObjectDetector is loaded and ready. */
    fun isSetupReady(): Boolean = (objectDetector != null)

    /**
     * Initialise and load the detector.
     * MUST be called from the same executor thread that will call [detect].
     */
    fun setup() {
        try {
            System.loadLibrary("mediapipe_tasks_vision_jni")
            personTracker.reset()
            movementAnalyzer.reset()
            eventProcessor.reset()
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .setDelegate(Delegate.CPU)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(CONFIDENCE_THRESHOLD)
                .setCategoryAllowlist(listOf(PERSON_LABEL))
                .setResultListener { result, image ->
                    // Category allowlist filters to person natively in MediaPipe.
                    // Safely map detections to PersonBox for the overlay.
                    val rawDetections = result.detections().mapNotNull { det ->
                        val cat = det.categories().firstOrNull {
                            it.categoryName().equals(PERSON_LABEL, ignoreCase = true)
                        } ?: det.categories().firstOrNull()

                        cat?.let {
                            PersonBox(
                                boundingBox = det.boundingBox(),
                                confidence = it.score(),
                            )
                        }
                    }

                    // Associate detections across frames to assign persistent tracking IDs
                    val trackedDetections = personTracker.update(
                        rawDetections = rawDetections,
                        imageWidth = image.width,
                        imageHeight = image.height,
                    )

                    val currentPhoneMotion = sensorMotionMonitor?.currentMotionState
                        ?: PhoneMotionState.CAMERA_STABLE

                    // Classify movement trajectory for tracked persons, fused with phone sensor motion state
                    val movementEvents = movementAnalyzer.analyze(
                        detections = trackedDetections,
                        imageWidth = image.width,
                        imageHeight = image.height,
                        phoneMotionState = currentPhoneMotion,
                        timestampMs = System.currentTimeMillis(),
                    )

                    val classifiedDetections = trackedDetections.map { box ->
                        val event = movementEvents[box.trackId]
                        val spatialPos = SpatialPosition.fromBoundingBox(
                            box = box.boundingBox,
                            imageWidth = image.width,
                            imageHeight = image.height,
                        )
                        box.copy(
                            movementType = event?.movementType ?: MovementType.UNKNOWN,
                            movementEvent = event,
                            cameraMotionState = currentPhoneMotion,
                            spatialPosition = spatialPos,
                        )
                    }

                    // Emit non-duplicate semantic events on state transitions to TemporalEventStore
                    eventProcessor.processFrame(
                        detections = classifiedDetections,
                        droppedTracks = personTracker.getDroppedTracks(),
                        timestampMs = System.currentTimeMillis(),
                    )

                    if (classifiedDetections.isNotEmpty()) {
                        Log.d(TAG, "Classified ${classifiedDetections.size} person(s): ${classifiedDetections.map { "${it.trackId}: ${it.movementType} (${(it.confidence * 100).toInt()}%), Phone: $currentPhoneMotion" }}")
                    }

                    listener.onPersonDetected(
                        PersonDetectionResult(
                            detections = classifiedDetections,
                            imageWidth = image.width,
                            imageHeight = image.height,
                        )
                    )
                }
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe ObjectDetector error: ${error.message}", error)
                    listener.onError(error.message ?: "Unknown detection error")
                }
                .build()

            objectDetector = ObjectDetector.createFromOptions(context, options)
            Log.d(TAG, "ObjectDetector initialised successfully on thread: ${Thread.currentThread().name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise ObjectDetector", e)
            listener.onError("Failed to initialise detector: ${e.message}")
        }
    }

    /**
     * Feed a bitmap frame. Must be called on the same thread as [setup].
     * Timestamp must be monotonically increasing — managed internally.
     *
     * @param bitmap    ARGB_8888 or RGB_565 bitmap (rotation already applied).
     */
    fun detect(bitmap: Bitmap) {
        if (objectDetector == null) {
            Log.w(TAG, "detect() called before setup() completed")
            return
        }
        // Guarantee monotonically increasing timestamps required by LIVE_STREAM mode
        val ts = System.currentTimeMillis().coerceAtLeast(lastTimestampMs.get() + 1)
        lastTimestampMs.set(ts)

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            objectDetector?.detectAsync(mpImage, ts)
        } catch (e: Exception) {
            Log.e(TAG, "detectAsync failed", e)
        }
    }

    /** Release native resources. Call from the same executor thread or after it is shut down. */
    fun close() {
        try {
            objectDetector?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing detector", e)
        }
        objectDetector = null
    }
}
