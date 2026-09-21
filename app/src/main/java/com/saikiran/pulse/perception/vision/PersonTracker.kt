package com.saikiran.pulse.perception.vision

import android.graphics.RectF
import kotlin.math.hypot

/**
 * A single tracked person state across consecutive frames.
 */
data class TrackedPerson(
    val id: String,
    var boundingBox: RectF,
    var confidence: Float,
    var missedFrames: Int = 0,
    var lastSeenTimestampMs: Long = System.currentTimeMillis(),
)

/**
 * Short-term Re-ID memory for dropped tracks (3-second TTL).
 */
data class TrackMemory(
    val id: String,
    val lastKnownBox: RectF,
    val lastSeenTimestampMs: Long,
)

/**
 * Information regarding a track that was dropped by the tracker.
 *
 * @param id           Tracking identifier (e.g. "PERSON_1").
 * @param lastKnownBox Bounding box coordinates when tracking was last active.
 * @param isNearBorder True if lastKnownBox touched or was close to the camera frame border.
 */
data class TrackDropInfo(
    val id: String,
    val lastKnownBox: RectF,
    val isNearBorder: Boolean,
)

/**
 * Lightweight IoU and Centroid-distance based temporal tracker for detected persons across consecutive camera frames.
 * Includes short-term Re-ID memory across brief occlusions/exits and unified cost-matrix assignment to prevent ID swapping.
 *
 * Configuration:
 *  - [maxMissedFrames] = 25 frames (~800ms at 30 FPS). Survives temporary frame drops/occlusions.
 *  - [iouThreshold] = 0.15f minimum bounding box overlap.
 *  - [maxCentroidDistanceRatio] = 0.35f maximum normalized centroid jump distance.
 *  - [reidTtlMs] = 3000ms Re-ID memory TTL to retain track identity across brief disappearances.
 */
class PersonTracker(
    private val maxMissedFrames: Int = 25, // Exactly 25 frames (~800ms at 30 FPS)
    private val iouThreshold: Float = 0.15f,
    private val maxCentroidDistanceRatio: Float = 0.35f,
    private val reidTtlMs: Long = 3000L,
) {
    private val activeTracks = mutableListOf<TrackedPerson>()
    private val reidMemory = mutableListOf<TrackMemory>()
    private var nextTrackId = 1
    private val droppedTracksInLastUpdate = mutableListOf<TrackDropInfo>()

    /** Reset tracking state (e.g., when camera session restarts). */
    @Synchronized
    fun reset() {
        activeTracks.clear()
        reidMemory.clear()
        droppedTracksInLastUpdate.clear()
        nextTrackId = 1
    }

    /** Returns current set of active track IDs. */
    @Synchronized
    fun getActiveTrackIds(): Set<String> {
        return activeTracks.map { it.id }.toSet()
    }

    /** Returns list of tracks dropped in the most recent update pass. */
    @Synchronized
    fun getDroppedTracks(): List<TrackDropInfo> {
        return droppedTracksInLastUpdate.toList()
    }

    /**
     * Process raw frame detections and return active tracked persons with assigned IDs.
     *
     * @param rawDetections Detections from object detector (without IDs).
     * @param imageWidth    Width of frame (px) for centroid distance normalization.
     * @param imageHeight   Height of frame (px) for centroid distance normalization.
     * @return List of tracked [PersonBox] with assigned `trackId`s.
     */
    @Synchronized
    fun update(
        rawDetections: List<PersonBox>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<PersonBox> {
        val now = System.currentTimeMillis()
        val diag = hypot(imageWidth.toDouble(), imageHeight.toDouble()).toFloat().coerceAtLeast(1f)

        droppedTracksInLastUpdate.clear()

        // Evict expired Re-ID memories older than 3 seconds
        reidMemory.removeAll { now - it.lastSeenTimestampMs > reidTtlMs }

        val matchedTracks = BooleanArray(activeTracks.size)
        val matchedDetections = BooleanArray(rawDetections.size)

        // Build list of candidate associations with unified tracking cost
        data class MatchCandidate(
            val trackIdx: Int,
            val detIdx: Int,
            val iou: Float,
            val distRatio: Float,
            val cost: Float, // Unified tracking cost combining IoU error and normalized centroid distance
        )

        val candidates = mutableListOf<MatchCandidate>()

        for (tIdx in activeTracks.indices) {
            val track = activeTracks[tIdx]
            for (dIdx in rawDetections.indices) {
                val det = rawDetections[dIdx]
                val iou = calculateIoU(track.boundingBox, det.boundingBox)
                val distRatio = calculateCentroidDistance(track.boundingBox, det.boundingBox) / diag

                if ((iou >= iouThreshold) || (distRatio <= maxCentroidDistanceRatio)) {
                    // Cost formula: 60% IoU error + 40% Centroid Distance ratio (prevents ID swapping when tracks cross)
                    val cost = (1f - iou) * 0.6f + distRatio * 0.4f
                    candidates.add(MatchCandidate(tIdx, dIdx, iou, distRatio, cost))
                }
            }
        }

        // Sort candidate matches by lowest tracking cost
        candidates.sortBy { it.cost }

        // Greedily assign matches
        for (candidate in candidates) {
            if (!matchedTracks[candidate.trackIdx] && !matchedDetections[candidate.detIdx]) {
                matchedTracks[candidate.trackIdx] = true
                matchedDetections[candidate.detIdx] = true

                val track = activeTracks[candidate.trackIdx]
                val det = rawDetections[candidate.detIdx]

                // Smooth bounding box (exponential moving average: 70% new, 30% old) for visual stability
                track.boundingBox = smoothRect(track.boundingBox, det.boundingBox, alpha = 0.7f)
                track.confidence = det.confidence
                track.missedFrames = 0
                track.lastSeenTimestampMs = now
            }
        }

        // Handle unmatched detections -> attempt Re-ID match before creating NEW tracks
        for (dIdx in rawDetections.indices) {
            if (!matchedDetections[dIdx]) {
                val det = rawDetections[dIdx]

                // Search Re-ID memory for matching candidate within 3 seconds
                val reidCandidate = reidMemory.firstOrNull { mem ->
                    val distRatio = calculateCentroidDistance(mem.lastKnownBox, det.boundingBox) / diag
                    distRatio <= maxCentroidDistanceRatio
                }

                val assignedId = if (reidCandidate != null) {
                    reidMemory.remove(reidCandidate)
                    reidCandidate.id
                } else {
                    val newId = "PERSON_$nextTrackId"
                    nextTrackId++
                    newId
                }

                val newTrack = TrackedPerson(
                    id = assignedId,
                    boundingBox = RectF(det.boundingBox),
                    confidence = det.confidence,
                    missedFrames = 0,
                    lastSeenTimestampMs = now,
                )
                activeTracks.add(newTrack)
            }
        }

        // Handle unmatched existing tracks -> increment missedFrames
        val marginX = imageWidth * 0.08f
        val marginY = imageHeight * 0.08f

        val iterator = activeTracks.iterator()
        var trackIdx = 0
        while (iterator.hasNext()) {
            val track = iterator.next()
            val wasMatched = if (trackIdx < matchedTracks.size) matchedTracks[trackIdx] else false
            if (!wasMatched) {
                track.missedFrames += 1
                if (track.missedFrames > maxMissedFrames) {
                    val box = track.boundingBox
                    val isNearBorder = (box.left <= marginX) ||
                            (box.right >= (imageWidth - marginX)) ||
                            (box.top <= marginY) ||
                            (box.bottom >= (imageHeight - marginY))

                    droppedTracksInLastUpdate.add(
                        TrackDropInfo(
                            id = track.id,
                            lastKnownBox = RectF(box),
                            isNearBorder = isNearBorder,
                        )
                    )

                    // Add to Re-ID memory before removing from activeTracks
                    reidMemory.add(
                        TrackMemory(
                            id = track.id,
                            lastKnownBox = RectF(box),
                            lastSeenTimestampMs = now,
                        )
                    )

                    iterator.remove()
                }
            }
            trackIdx++
        }

        // Return current active tracks that are currently visible or recently active
        return activeTracks.map { track ->
            PersonBox(
                trackId = track.id,
                boundingBox = RectF(track.boundingBox),
                confidence = track.confidence,
            )
        }
    }

    private fun calculateIoU(rectA: RectF, rectB: RectF): Float {
        val intersectionLeft = maxOf(rectA.left, rectB.left)
        val intersectionTop = maxOf(rectA.top, rectB.top)
        val intersectionRight = minOf(rectA.right, rectB.right)
        val intersectionBottom = minOf(rectA.bottom, rectB.bottom)

        val intersectionW = maxOf(0f, intersectionRight - intersectionLeft)
        val intersectionH = maxOf(0f, intersectionBottom - intersectionTop)
        val intersectionArea = intersectionW * intersectionH

        if (intersectionArea <= 0f) return 0f

        val areaA = rectA.width() * rectA.height()
        val areaB = rectB.width() * rectB.height()
        val unionArea = areaA + areaB - intersectionArea

        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }

    private fun calculateCentroidDistance(rectA: RectF, rectB: RectF): Float {
        val cAx = rectA.centerX()
        val cAy = rectA.centerY()
        val cBx = rectB.centerX()
        val cBy = rectB.centerY()
        return hypot((cAx - cBx).toDouble(), (cAy - cBy).toDouble()).toFloat()
    }

    private fun smoothRect(oldRect: RectF, newRect: RectF, alpha: Float): RectF {
        return RectF(
            oldRect.left * (1f - alpha) + newRect.left * alpha,
            oldRect.top * (1f - alpha) + newRect.top * alpha,
            oldRect.right * (1f - alpha) + newRect.right * alpha,
            oldRect.bottom * (1f - alpha) + newRect.bottom * alpha,
        )
    }
}
