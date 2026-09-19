package com.saikiran.pulse.perception.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX [ImageAnalysis.Analyzer] that:
 *  1. Initialises [PersonDetector] lazily on the first frame (same executor thread — required by MediaPipe LIVE_STREAM).
 *  2. Corrects frame rotation before feeding to the detector.
 *  3. Closes every [ImageProxy] unconditionally.
 *
 * Backpressure: ImageAnalysis must be built with [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST]
 * so stale frames are dropped by CameraX automatically.
 */
class PersonAnalyzer(
    private val detector: PersonDetector,
) : ImageAnalysis.Analyzer {

    /** Set to true after the detector has been set up on this thread. */
    @Volatile
    private var isDetectorReady = false

    companion object {
        private const val TAG = "PersonAnalyzer"
    }

    override fun analyze(image: ImageProxy) {
        try {
            // ── Lazy one-time setup on the analyser thread ─────────────────
            // This guarantees setup() and detectAsync() share the same thread,
            // which is mandatory for MediaPipe LIVE_STREAM mode.
            if (!detector.isSetupReady()) {
                detector.setup()
            }

            // ── Convert ImageProxy → Bitmap and correct rotation ───────────
            val rotation = image.imageInfo.rotationDegrees
            val rawBitmap: Bitmap = image.toBitmap()

            val bitmap: Bitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                if (rotated != rawBitmap) {
                    rawBitmap.recycle()
                }
                rotated
            } else {
                rawBitmap
            }

            // ── Feed to detector ───────────────────────────────────────────
            detector.detect(bitmap)

        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis error", e)
        } finally {
            // MUST always close the proxy — otherwise CameraX stops delivering frames
            image.close()
        }
    }
}
