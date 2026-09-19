package com.saikiran.pulse.perception.vision

import android.graphics.RectF
import com.saikiran.pulse.perception.sensors.PhoneMotionState
import com.saikiran.pulse.perception.vision.movement.MovementEvent
import com.saikiran.pulse.perception.vision.movement.MovementType
import com.saikiran.pulse.perception.vision.spatial.SpatialPosition

/**
 * A detected and tracked bounding box for a single person, including movement trajectory and phone motion state.
 *
 * @param trackId           Unique temporary tracking identifier (e.g., "PERSON_1").
 * @param boundingBox       The pixel-space rectangle from the model (relative to the analysed frame).
 * @param confidence        Detection score in [0, 1].
 * @param movementType      Classified movement trajectory (APPROACHING, MOVING_AWAY, PASSING_BY, etc.).
 * @param movementEvent     Detailed movement event data.
 * @param cameraMotionState Current phone camera motion state (CAMERA_STABLE or CAMERA_MOVING).
 * @param spatialPosition   Spatial zone and distance (LEFT, CENTER, RIGHT, NEAR, FAR).
 */
data class PersonBox(
    val trackId: String = "PERSON_1",
    val boundingBox: RectF,
    val confidence: Float,
    val movementType: MovementType = MovementType.UNKNOWN,
    val movementEvent: MovementEvent? = null,
    val cameraMotionState: PhoneMotionState = PhoneMotionState.CAMERA_STABLE,
    val spatialPosition: SpatialPosition? = null,
)

/**
 * Aggregated result for one analysed frame.
 *
 * @param detections   All detected person boxes (may be empty).
 * @param imageWidth   Width of the frame fed to the detector (px).
 * @param imageHeight  Height of the frame fed to the detector (px).
 */
data class PersonDetectionResult(
    val detections: List<PersonBox>,
    val imageWidth: Int,
    val imageHeight: Int,
)

/**
 * Callback interface delivered on the MediaPipe result thread.
 * Implementations should be fast — heavy work must be offloaded.
 */
interface PersonDetectionListener {
    fun onPersonDetected(result: PersonDetectionResult)
    fun onError(message: String)
}
