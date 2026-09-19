package com.saikiran.pulse.engine.change

import com.saikiran.pulse.engine.summary.SummaryResult

/**
 * Deterministic Natural Language Summarizer for [ChangeEvent] deltas ("What Changed?").
 *
 * Responsibilities:
 *  1. Accepts unconsumed [ChangeEvent]s that occurred since the last "WHAT CHANGED?" query.
 *  2. Converts single or grouped change event sequences into a concise 1–2 sentence English summary.
 *  3. Applies conservative phrasing and preserves track-loss uncertainty wording.
 *  4. Evaluates confidence score averages.
 */
object ChangeSummarizer {

    private const val LOW_CONFIDENCE_THRESHOLD = 0.45f

    /**
     * Summarize recent unconsumed change events.
     *
     * @param changes Unconsumed list of [ChangeEvent]s.
     * @return [SummaryResult] containing natural language summary.
     */
    fun summarizeChanges(changes: List<ChangeEvent>): SummaryResult {
        if (changes.isEmpty()) {
            return SummaryResult(
                text = "No new changes since your last check.",
                averageConfidence = 1.0f,
                isLowConfidence = false,
                eventCount = 0,
            )
        }

        val avgConfidence = changes.map { it.confidence.toDouble() }.average().toFloat()
        val isLowConfidence = avgConfidence < LOW_CONFIDENCE_THRESHOLD

        if (isLowConfidence) {
            return SummaryResult(
                text = "Possible changes occurred nearby, but confidence was low.",
                averageConfidence = avgConfidence,
                isLowConfidence = true,
                eventCount = changes.size,
            )
        }

        // Group changes by trackId
        val changesByTrack = changes.filter { it.trackId != null }.groupBy { it.trackId!! }

        val summaryText = when {
            changesByTrack.size == 1 -> {
                summarizeSingleTrackChanges(changesByTrack.values.first())
            }

            changesByTrack.size == 2 -> {
                val trackList = changesByTrack.values.toList()
                val sum1 = summarizeSingleTrackChanges(trackList[0]).removePrefix("A person ").removePrefix("A person")
                val sum2 = summarizeSingleTrackChanges(trackList[1]).removePrefix("A person ").removePrefix("A person")
                "Two changes occurred: one person $sum1, while another person $sum2"
            }

            changesByTrack.isNotEmpty() -> {
                "Multiple changes (${changesByTrack.size} people) occurred nearby."
            }

            else -> {
                "No active person changes detected."
            }
        }

        return SummaryResult(
            text = summaryText,
            averageConfidence = avgConfidence,
            isLowConfidence = false,
            eventCount = changes.size,
        )
    }

    private fun summarizeSingleTrackChanges(changes: List<ChangeEvent>): String {
        val types = mutableListOf<ChangeType>()
        for (c in changes) {
            if (types.isEmpty() || types.last() != c.changeType) {
                types.add(c.changeType)
            }
        }

        val hasEntered = types.contains(ChangeType.PERSON_ENTERED_VIEW)
        val hasApproaching = types.contains(ChangeType.PERSON_APPROACHING)
        val hasStopped = types.contains(ChangeType.PERSON_STOPPED)
        val hasMovingAway = types.contains(ChangeType.PERSON_MOVING_AWAY)
        val hasPassingBy = types.contains(ChangeType.PERSON_PASSING_BY)
        val hasTrackLost = types.contains(ChangeType.PERSON_TRACK_LOST)
        val hasLeftView = types.contains(ChangeType.PERSON_LEFT_VIEW)

        return when {
            // Pattern: APPROACHING + STOPPED
            hasApproaching && hasStopped -> {
                "A person approached you and stopped in front of you."
            }

            // Pattern: ENTERED + APPROACHING
            hasEntered && hasApproaching -> {
                "A person entered your view and moved closer to you."
            }

            // Pattern: APPROACHING + MOVING_AWAY
            hasApproaching && hasMovingAway -> {
                "A person moved closer and then moved away from you."
            }

            // Pattern: STOPPED + MOVING_AWAY
            hasStopped && hasMovingAway -> {
                "A person stopped and then moved away from you."
            }

            // Pattern: ENTERED + TRACK_LOST
            hasEntered && hasTrackLost -> {
                "A person appeared in view, but I lost visual contact."
            }

            // Pattern: APPROACHING + TRACK_LOST
            hasApproaching && hasTrackLost -> {
                "A person moved closer, but I lost visual contact."
            }

            // Pattern: ENTERED + LEFT_VIEW
            hasEntered && hasLeftView -> {
                "A person entered your view and walked out of view."
            }

            // Single change types
            hasEntered -> "A person entered your field of view."
            hasApproaching -> "A person moved closer to you."
            hasMovingAway -> "A person moved away from you."
            hasStopped -> "A person stopped moving."
            hasPassingBy -> "A person passed by."
            hasTrackLost -> "I lost visual contact with a person."
            hasLeftView -> "A person walked out of view."

            else -> "A person changed movement state."
        }
    }
}
