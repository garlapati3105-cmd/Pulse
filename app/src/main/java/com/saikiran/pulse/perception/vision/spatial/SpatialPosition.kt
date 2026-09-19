package com.saikiran.pulse.perception.vision.spatial

import android.graphics.RectF

/**
 * Immutable spatial position model representing a person's location in 2D camera space.
 *
 * @param zone      Horizontal zone (LEFT, CENTER, RIGHT).
 * @param distance  Estimated relative distance (NEAR, MID, FAR).
 * @param phrase    Natural language spatial phrase (e.g., "on your left", "in front of you nearby").
 * @param shortDescription Short label (e.g., "left", "center near").
 */
data class SpatialPosition(
    val zone: SpatialZone,
    val distance: SpatialDistance,
    val phrase: String,
    val shortDescription: String,
) {
    companion object {
        /**
         * Compute spatial zone and distance from a person's bounding box and frame dimensions.
         */
        fun fromBoundingBox(box: RectF, imageWidth: Int, imageHeight: Int): SpatialPosition {
            if (imageWidth <= 0 || imageHeight <= 0) {
                return SpatialPosition(
                    zone = SpatialZone.CENTER,
                    distance = SpatialDistance.MID,
                    phrase = "in front of you",
                    shortDescription = "center",
                )
            }

            val centerX = box.centerX()
            val height = box.height()

            val relX = centerX / imageWidth.toFloat()
            val relH = height / imageHeight.toFloat()

            val zone = when {
                relX < 0.35f -> SpatialZone.LEFT
                relX > 0.65f -> SpatialZone.RIGHT
                else -> SpatialZone.CENTER
            }

            val distance = when {
                relH >= 0.40f -> SpatialDistance.NEAR
                relH >= 0.20f -> SpatialDistance.MID
                else -> SpatialDistance.FAR
            }

            val phrase = when (zone) {
                SpatialZone.LEFT -> if (distance == SpatialDistance.NEAR) "on your left nearby" else "on your left"
                SpatialZone.RIGHT -> if (distance == SpatialDistance.NEAR) "on your right nearby" else "on your right"
                SpatialZone.CENTER -> if (distance == SpatialDistance.NEAR) "in front of you nearby" else "in front of you"
            }

            val shortDescription = when (zone) {
                SpatialZone.LEFT -> if (distance == SpatialDistance.NEAR) "left near" else "left"
                SpatialZone.RIGHT -> if (distance == SpatialDistance.NEAR) "right near" else "right"
                SpatialZone.CENTER -> if (distance == SpatialDistance.NEAR) "center near" else "center"
            }

            return SpatialPosition(
                zone = zone,
                distance = distance,
                phrase = phrase,
                shortDescription = shortDescription,
            )
        }
    }
}
