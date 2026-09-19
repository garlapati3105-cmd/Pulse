package com.saikiran.pulse.perception.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.saikiran.pulse.perception.sensors.PhoneMotionState
import com.saikiran.pulse.perception.vision.movement.MovementType

/**
 * Transparent overlay [View] drawn on top of the camera [PreviewView].
 * Draws bounding boxes and confidence labels for each detected person.
 *
 * Thread-safety: [updateDetections] is safe to call from any thread.
 */
class PersonOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Snapshot of the last result — written from any thread, read on UI thread
    @Volatile private var detections: List<PersonBox> = emptyList()
    @Volatile private var sourceWidth: Int = 1
    @Volatile private var sourceHeight: Int = 1

    // ── Paints ──────────────────────────────────────────────────────────────

    private val boxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")   // vivid green
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val boxFillPaint = Paint().apply {
        color = Color.argb(50, 0, 230, 118)  // semi-transparent fill
        style = Paint.Style.FILL
    }

    private val labelBgPaint = Paint().apply {
        color = Color.argb(180, 0, 80, 40)
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
    }

    init {
        // CRITICAL: must be transparent so the camera preview shows through
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** Update detections safely from any thread. */
    fun updateDetections(result: PersonDetectionResult) {
        detections = result.detections
        sourceWidth = result.imageWidth
        sourceHeight = result.imageHeight
        postInvalidate()
    }

    fun clearDetections() {
        detections = emptyList()
        postInvalidate()
    }

    private val transformedRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val currentDetections = detections   // local snapshot for thread safety
        if (currentDetections.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        if ((sourceWidth <= 0) || (sourceHeight <= 0) || (viewW <= 0f) || (viewH <= 0f)) return

        // Scale from model output space (rotated frame) to view space using FILL_CENTER (aspect fill)
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val viewAspect = viewW / viewH

        val scale: Float
        val offsetX: Float
        val offsetY: Float

        if (sourceAspect > viewAspect) {
            // Source frame is wider than View relative to height -> scale by height, crop left/right
            scale = viewH / sourceHeight.toFloat()
            offsetX = (viewW - sourceWidth * scale) / 2f
            offsetY = 0f
        } else {
            // Source frame is taller than View relative to width -> scale by width, crop top/bottom
            scale = viewW / sourceWidth.toFloat()
            offsetX = 0f
            offsetY = (viewH - sourceHeight * scale) / 2f
        }

        for (box in currentDetections) {
            transformedRect.set(
                box.boundingBox.left   * scale + offsetX,
                box.boundingBox.top    * scale + offsetY,
                box.boundingBox.right  * scale + offsetX,
                box.boundingBox.bottom * scale + offsetY,
            )

            // Draw fill then border
            canvas.drawRect(transformedRect, boxFillPaint)
            canvas.drawRect(transformedRect, boxStrokePaint)

            // Draw multi-line debug info:
            // Phone: STABLE/MOVING
            // PERSON_1
            // APPROACHING
            // 82%
            val line1 = when (box.cameraMotionState) {
                PhoneMotionState.CAMERA_STABLE -> "Phone: STABLE"
                PhoneMotionState.CAMERA_MOVING -> "Phone: MOVING"
            }
            val line2 = box.trackId
            val line3 = when (box.movementType) {
                MovementType.PERSON_APPROACHING -> "APPROACHING"
                MovementType.PERSON_MOVING_AWAY -> "MOVING AWAY"
                MovementType.PERSON_PASSING_BY  -> "PASSING BY"
                MovementType.STATIONARY         -> "STATIONARY"
                MovementType.UNKNOWN            -> "UNKNOWN"
            }
            val line4 = "${(box.confidence * 100).toInt()}%"

            val textH = labelTextPaint.textSize
            val lineSpacing = 6f
            val totalTextH = (textH * 4) + (lineSpacing * 3)

            val maxTextW = maxOf(
                labelTextPaint.measureText(line1),
                maxOf(
                    labelTextPaint.measureText(line2),
                    labelTextPaint.measureText(line3),
                    labelTextPaint.measureText(line4),
                ),
            )

            // Place label box above the bounding box, or inside top if near top edge
            val labelBottom = if (transformedRect.top > totalTextH + 12f) transformedRect.top - 6f else transformedRect.top + totalTextH + 6f
            val labelTop = labelBottom - totalTextH - 8f
            val labelLeft = transformedRect.left.coerceAtLeast(0f).coerceAtMost(viewW - maxTextW - 20f)

            canvas.drawRoundRect(
                labelLeft, labelTop,
                labelLeft + maxTextW + 20f, labelBottom,
                8f, 8f, labelBgPaint,
            )

            var currentY = labelTop + textH + 4f
            canvas.drawText(line1, labelLeft + 10f, currentY, labelTextPaint)
            currentY += textH + lineSpacing
            canvas.drawText(line2, labelLeft + 10f, currentY, labelTextPaint)
            currentY += textH + lineSpacing
            canvas.drawText(line3, labelLeft + 10f, currentY, labelTextPaint)
            currentY += textH + lineSpacing
            canvas.drawText(line4, labelLeft + 10f, currentY, labelTextPaint)
        }
    }
}
