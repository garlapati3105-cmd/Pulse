package com.saikiran.pulse.perception.vision.movement

import com.saikiran.pulse.perception.sensors.PhoneMotionState
import com.saikiran.pulse.perception.vision.PersonBox
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Single frame sample for tracking history.
 */
private data class FrameSample(
    val timestampMs: Long,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

/**
 * Internal movement history and voting state per tracked person.
 */
private class TrackHistoryState(
    val trackId: String,
) {
    val samples: ArrayDeque<FrameSample> = ArrayDeque()
    var currentStableType: MovementType = MovementType.UNKNOWN
    var isFastApproach: Boolean = false
    val typeVotes = mutableMapOf<MovementType, Int>()
}

/**
 * Noise-robust Movement Analyzer evaluating spatial trajectories over a rolling time window,
 * fused with real-time IMU Phone Motion State.
 *
 * P0 Robustness: Evaluates 2D area expansion (width AND height) to distinguish actual physical
 * approach/departure from stationary posture shifts (arm raising, crouching/standing in place).
 */
class MovementAnalyzer(
    private val windowDurationMs: Long = 1500L,
    private val minHistoryDurationMs: Long = 400L,
    private val heightChangeThresholdRatio: Float = 0.10f,      // 10% height change required
    private val widthChangeThresholdRatio: Float = 0.05f,       // 5% width change required to confirm 2D proportional scaling
    private val passingByDisplacementRatio: Float = 0.06f,       // 6% screen width displacement
    private val fastApproachSpeedRatioPerSec: Float = 0.22f,     // 22% height expansion per second
) {
    private val historyMap = mutableMapOf<String, TrackHistoryState>()

    /** Clear all track histories (e.g., camera session reset). */
    @Synchronized
    fun reset() {
        historyMap.clear()
    }

    /**
     * Analyze current tracked detections and produce movement events fused with phone motion state.
     */
    @Synchronized
    fun analyze(
        detections: List<PersonBox>,
        imageWidth: Int,
        imageHeight: Int,
        phoneMotionState: PhoneMotionState = PhoneMotionState.CAMERA_STABLE,
        timestampMs: Long = System.currentTimeMillis(),
    ): Map<String, MovementEvent> {
        val currentTrackIds = detections.map { it.trackId }.toSet()

        // Prune stale track histories no longer present in detections
        historyMap.keys.retainAll(currentTrackIds)

        val results = mutableMapOf<String, MovementEvent>()

        for (person in detections) {
            val trackState = historyMap.getOrPut(person.trackId) {
                TrackHistoryState(person.trackId)
            }

            val box = person.boundingBox
            val width = box.right - box.left
            val height = box.bottom - box.top
            val centerX = (box.left + box.right) / 2f
            val centerY = (box.top + box.bottom) / 2f

            val sample = FrameSample(
                timestampMs = timestampMs,
                centerX = centerX,
                centerY = centerY,
                width = width,
                height = height,
            )

            trackState.samples.addLast(sample)

            // Evict samples older than windowDurationMs
            val cutoff = timestampMs - windowDurationMs
            while ((trackState.samples.isNotEmpty()) && (trackState.samples.first.timestampMs < cutoff)) {
                trackState.samples.removeFirst()
            }

            // Calculate raw vision movement candidate and fast approach flag
            val (rawCandidate, rawConfidence, isFast) = classifyTrajectory(
                samples = trackState.samples,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )

            trackState.isFastApproach = isFast

            // Motion Fusion: penalize confidence and suppress movement if phone is moving
            val (candidate, fusedConfidence) = if (phoneMotionState == PhoneMotionState.CAMERA_MOVING) {
                val penalizedConfidence = (rawConfidence * 0.35f).coerceIn(0.15f, 0.40f)
                val fusedType = if (rawCandidate == MovementType.STATIONARY) MovementType.STATIONARY else MovementType.UNKNOWN
                Pair(fusedType, penalizedConfidence)
            } else {
                Pair(rawCandidate, rawConfidence)
            }

            // Leaky-accumulator voting algorithm for smooth, noise-resistant state transitions
            if (candidate != MovementType.UNKNOWN) {
                for (type in MovementType.entries) {
                    val currentVote = trackState.typeVotes.getOrDefault(type, 0)
                    if (type == candidate) {
                        trackState.typeVotes[type] = (currentVote + 2).coerceAtMost(10)
                    } else {
                        trackState.typeVotes[type] = (currentVote - 1).coerceAtLeast(0)
                    }
                }

                // Transition stable state if winning candidate reaches threshold (>= 6 votes ~ 3-4 consecutive frames)
                val leadingVote = trackState.typeVotes.maxByOrNull { it.value }
                if ((leadingVote != null) && (leadingVote.value >= 6)) {
                    trackState.currentStableType = leadingVote.key
                }
            } else {
                // Decay votes slowly when candidate is UNKNOWN
                for (type in MovementType.entries) {
                    val currentVote = trackState.typeVotes.getOrDefault(type, 0)
                    trackState.typeVotes[type] = (currentVote - 1).coerceAtLeast(0)
                }
            }

            results[person.trackId] = MovementEvent(
                trackId = person.trackId,
                movementType = trackState.currentStableType,
                confidence = fusedConfidence,
                isFastApproach = trackState.isFastApproach && (trackState.currentStableType == MovementType.PERSON_APPROACHING),
                timestamp = timestampMs,
            )
        }

        return results
    }

    private fun classifyTrajectory(
        samples: ArrayDeque<FrameSample>,
        imageWidth: Int,
        imageHeight: Int,
    ): Triple<MovementType, Float, Boolean> {
        if ((samples.size < 5) || (imageWidth <= 0) || (imageHeight <= 0)) {
            return Triple(MovementType.UNKNOWN, 0.5f, false)
        }

        val oldest = samples.first
        val newest = samples.last
        val timeSpanMs = newest.timestampMs - oldest.timestampMs

        if (timeSpanMs < minHistoryDurationMs) {
            return Triple(MovementType.UNKNOWN, 0.5f, false)
        }

        // Compare earliest 25% of samples vs latest 25% of samples
        val sampleList = samples.toList()
        val quarterCount = maxOf(1, sampleList.size / 4)

        val earliestSamples = sampleList.subList(0, quarterCount)
        val latestSamples = sampleList.subList(sampleList.size - quarterCount, sampleList.size)

        if (earliestSamples.isEmpty() || latestSamples.isEmpty()) {
            return Triple(MovementType.UNKNOWN, 0.5f, false)
        }

        val avgHeightOld = earliestSamples.map { it.height.toDouble() }.average().toFloat()
        val avgHeightNew = latestSamples.map { it.height.toDouble() }.average().toFloat()

        val avgWidthOld = earliestSamples.map { it.width.toDouble() }.average().toFloat()
        val avgWidthNew = latestSamples.map { it.width.toDouble() }.average().toFloat()

        val avgXOld = earliestSamples.map { it.centerX.toDouble() }.average().toFloat()
        val avgXNew = latestSamples.map { it.centerX.toDouble() }.average().toFloat()

        if (avgHeightOld <= 0f || avgWidthOld <= 0f) {
            return Triple(MovementType.UNKNOWN, 0.5f, false)
        }

        // Feature 1: Relative height & width change ratios
        val relHeightChange = (avgHeightNew - avgHeightOld) / avgHeightOld
        val relWidthChange = (avgWidthNew - avgWidthOld) / avgWidthOld

        // Feature 2: Relative lateral X displacement ratio
        val relXDisplacement = abs(avgXNew - avgXOld) / imageWidth.toFloat()

        // Feature 3: Speed of height growth per second
        val timeDeltaSec = timeSpanMs / 1000f
        val heightGrowthRatePerSec = if (timeDeltaSec > 0f) relHeightChange / timeDeltaSec else 0f
        val isFastApproach = heightGrowthRatePerSec >= fastApproachSpeedRatioPerSec

        // Feature 4: Detect posture changes (arm raising increases height without expanding width;
        // crouching shrinks height without shrinking width)
        val isProportionalScaling = relHeightChange > 0f && relWidthChange >= widthChangeThresholdRatio
        val isProportionalShrinking = relHeightChange < 0f && relWidthChange <= -widthChangeThresholdRatio

        val candidate: MovementType
        val confidence: Float

        when {
            // PASSING_BY: Horizontal displacement across screen >= 6%, height relatively constant
            (relXDisplacement >= passingByDisplacementRatio) && (abs(relHeightChange) < heightChangeThresholdRatio) -> {
                candidate = MovementType.PERSON_PASSING_BY
                confidence = (relXDisplacement / passingByDisplacementRatio).coerceIn(0.75f, 0.99f)
            }

            // APPROACHING: Requires BOTH height expansion AND width expansion (proportional 2D scale)
            relHeightChange >= heightChangeThresholdRatio && isProportionalScaling -> {
                candidate = MovementType.PERSON_APPROACHING
                confidence = (relHeightChange / heightChangeThresholdRatio).coerceIn(0.75f, 0.99f)
            }

            // MOVING_AWAY: Requires BOTH height shrinkage AND width shrinkage
            relHeightChange <= -heightChangeThresholdRatio && isProportionalShrinking -> {
                candidate = MovementType.PERSON_MOVING_AWAY
                confidence = (abs(relHeightChange) / heightChangeThresholdRatio).coerceIn(0.75f, 0.99f)
            }

            // POSTURE CHANGE or STATIONARY: Height change without matching width change (e.g. raising arms, crouching)
            // or small overall displacement -> Classify as STATIONARY
            (abs(relHeightChange) < heightChangeThresholdRatio * 0.8f || !isProportionalScaling) &&
                    (relXDisplacement < passingByDisplacementRatio * 0.80f) -> {
                candidate = MovementType.STATIONARY
                confidence = 0.90f
            }

            else -> {
                candidate = MovementType.UNKNOWN
                confidence = 0.5f
            }
        }

        return Triple(candidate, confidence, isFastApproach)
    }
}
