package com.saikiran.pulse.engine.summary

import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.events.PulseEvent

/**
 * Deterministic natural-language event summarizer converting recent temporal events into concise,
 * human-readable descriptions ("What Just Happened?").
 *
 * Design Guidelines & Behavior:
 *  1. Reads recent events from [PulseEvent] stream (rolling 20-second window).
 *  2. Converts event state transitions into idiomatic English sentences (1–2 sentences).
 *  3. Intelligently filters out transient false positives (< 2 events or zero movement).
 *  4. Evaluates CONCURRENT tracks (people present simultaneously) vs SEQUENTIAL tracks (same subject re-entering).
 *  5. Confidence-Aware: Evaluates average event confidence. If below [LOW_CONFIDENCE_THRESHOLD] (0.45),
 *     returns a cautious statement rather than a definitive claim.
 *  6. Track-Loss Uncertainty Wording: If a person's trajectory ends in [EventType.PERSON_TRACK_LOST], uses
 *     uncertainty-aware wording ("I lost visual contact with the person") rather than claiming they left.
 *  7. Left View Wording: Uses "walked out of view" or "left the area" ONLY when [EventType.PERSON_LEFT_VIEW] is confirmed.
 */
object EventSummarizer {

    private const val LOW_CONFIDENCE_THRESHOLD = 0.45f

    /**
     * Summarize recent events from rolling event store.
     *
     * @param events List of recent chronological events.
     * @return [SummaryResult] containing formatted natural language summary.
     */
    fun summarize(events: List<PulseEvent>): SummaryResult {
        if (events.isEmpty()) {
            return SummaryResult(
                text = "I don't have enough recent events to describe.",
                averageConfidence = 1.0f,
                isLowConfidence = false,
                eventCount = 0,
            )
        }

        val avgConfidence = events.map { it.confidence.toDouble() }.average().toFloat()
        val isLowConfidence = avgConfidence < LOW_CONFIDENCE_THRESHOLD

        // Handle low confidence scenario
        if (isLowConfidence) {
            val lowConfText = generateLowConfidenceSummary(events)
            return SummaryResult(
                text = lowConfText,
                averageConfidence = avgConfidence,
                isLowConfidence = true,
                eventCount = events.size,
            )
        }

        // Group events by trackId
        val rawEventsByTrack = events.filter { it.trackId != null }.groupBy { it.trackId!! }

        // Filter out transient false-positive tracks (e.g. tracks that lasted only 1-2 frames without movement)
        val meaningfulTracks = rawEventsByTrack.filter { (_, trackEvents) ->
            val hasMeaningfulEvent = trackEvents.any {
                it.eventType == EventType.PERSON_APPROACHING ||
                        it.eventType == EventType.PERSON_MOVING_AWAY ||
                        it.eventType == EventType.PERSON_PASSING_BY ||
                        it.eventType == EventType.PERSON_STOPPED
            }
            hasMeaningfulEvent || trackEvents.size >= 3
        }

        if (meaningfulTracks.isEmpty()) {
            return SummaryResult(
                text = "A person briefly appeared in view.",
                averageConfidence = avgConfidence,
                isLowConfidence = false,
                eventCount = events.size,
            )
        }

        // Compute MAX CONCURRENT tracks (how many people were present AT THE SAME TIME)
        val maxConcurrent = computeMaxConcurrentTracks(events, meaningfulTracks.keys)

        val summaryText = when {
            meaningfulTracks.size == 1 -> {
                summarizeSingleTrack(meaningfulTracks.values.first())
            }

            maxConcurrent <= 1 -> {
                // Sequential re-entries of a single person or main subject -> summarize the primary track!
                val primaryTrack = meaningfulTracks.values.maxByOrNull { it.size } ?: meaningfulTracks.values.first()
                summarizeSingleTrack(primaryTrack)
            }

            maxConcurrent == 2 -> {
                summarizeTwoConcurrentTracks(meaningfulTracks)
            }

            else -> {
                "Multiple people ($maxConcurrent) were detected moving nearby."
            }
        }

        return SummaryResult(
            text = summaryText,
            averageConfidence = avgConfidence,
            isLowConfidence = false,
            eventCount = events.size,
        )
    }

    private fun computeMaxConcurrentTracks(events: List<PulseEvent>, validTrackIds: Set<String>): Int {
        if (validTrackIds.isEmpty()) return 0

        data class TrackSpan(val id: String, val startMs: Long, val endMs: Long)

        val spans = validTrackIds.map { trackId ->
            val trackEvents = events.filter { it.trackId == trackId }
            val start = trackEvents.minOfOrNull { it.timestamp } ?: 0L
            val endEvent = trackEvents.firstOrNull {
                it.eventType == EventType.PERSON_LEFT_VIEW || it.eventType == EventType.PERSON_TRACK_LOST
            }
            val end = endEvent?.timestamp ?: (start + 5000L)
            TrackSpan(trackId, start, end)
        }

        var maxOverlap = 1
        for (i in spans.indices) {
            var overlapCount = 1
            for (j in spans.indices) {
                if (i != j) {
                    val a = spans[i]
                    val b = spans[j]
                    val overlap = maxOf(a.startMs, b.startMs) < minOf(a.endMs, b.endMs)
                    if (overlap) overlapCount++
                }
            }
            maxOverlap = maxOf(maxOverlap, overlapCount)
        }

        return maxOverlap
    }

    private fun generateLowConfidenceSummary(events: List<PulseEvent>): String {
        val hasMovement = events.any {
            it.eventType == EventType.PERSON_APPROACHING ||
                    it.eventType == EventType.PERSON_MOVING_AWAY ||
                    it.eventType == EventType.PERSON_PASSING_BY
        }

        return if (hasMovement) {
            "A person may have moved nearby, but the detection confidence was low."
        } else {
            "Uncertain movement detected nearby with low confidence."
        }
    }

    private fun summarizeSingleTrack(events: List<PulseEvent>): String {
        // Extract deduplicated ordered event types for this person
        val types = mutableListOf<EventType>()
        for (event in events) {
            if (types.isEmpty() || types.last() != event.eventType) {
                types.add(event.eventType)
            }
        }

        val containsEntered = types.contains(EventType.PERSON_ENTERED_VIEW)
        val containsApproaching = types.contains(EventType.PERSON_APPROACHING)
        val containsStopped = types.contains(EventType.PERSON_STOPPED)
        val containsMovingAway = types.contains(EventType.PERSON_MOVING_AWAY)
        val containsPassingBy = types.contains(EventType.PERSON_PASSING_BY)
        val containsLeftView = types.contains(EventType.PERSON_LEFT_VIEW)
        val containsTrackLost = types.contains(EventType.PERSON_TRACK_LOST)

        return when {
            // Pattern ending in TRACK_LOST (Uncertainty-aware wording)
            containsEntered && containsApproaching && containsStopped && containsTrackLost -> {
                "A person entered, approached you, stopped, and I lost visual contact."
            }

            containsEntered && containsApproaching && containsTrackLost -> {
                "A person entered, approached you, and I lost visual contact."
            }

            containsApproaching && containsTrackLost -> {
                "A person approached you, and I lost visual contact."
            }

            containsEntered && containsTrackLost -> {
                "A person appeared in view, but I lost visual contact."
            }

            containsTrackLost && types.last() == EventType.PERSON_TRACK_LOST -> {
                "I lost visual contact with the person."
            }

            // Pattern ending in LEFT_VIEW (Strong evidence person exited frame)
            containsEntered && containsApproaching && containsStopped && containsMovingAway && containsLeftView -> {
                "A person entered, approached you, stopped briefly, and then walked out of view."
            }

            containsEntered && containsApproaching && containsStopped && containsLeftView -> {
                "A person entered, approached you, stopped, and walked out of view."
            }

            containsEntered && containsApproaching && containsLeftView -> {
                "A person entered, approached you, and walked out of view."
            }

            containsEntered && containsPassingBy && containsLeftView -> {
                "A person entered, passed by, and walked out of view."
            }

            containsApproaching && containsMovingAway && containsLeftView -> {
                "A person approached, moved away, and walked out of view."
            }

            containsLeftView -> {
                "A person walked out of view."
            }

            // Active ongoing trajectories
            containsEntered && containsApproaching && containsStopped && containsMovingAway -> {
                "A person entered, approached you, stopped, and is now walking away."
            }

            containsEntered && containsApproaching && containsStopped -> {
                "A person entered, approached you, and stopped in front of you."
            }

            containsEntered && containsApproaching -> {
                "A person entered and approached you."
            }

            containsEntered && containsPassingBy -> {
                "A person entered and is passing by."
            }

            containsApproaching && containsStopped && containsMovingAway -> {
                "A person approached you, stopped briefly, and then walked away."
            }

            containsApproaching && containsStopped -> {
                "A person approached you and stopped in front of you."
            }

            containsApproaching && containsMovingAway -> {
                "A person approached and then moved away."
            }

            // Single states
            containsApproaching -> "A person is approaching you."
            containsMovingAway -> "A person is moving away."
            containsPassingBy -> "A person is passing by."
            containsStopped -> "A person is standing stationary in front of you."
            containsEntered -> "A person appeared in view."

            else -> "A person was active nearby."
        }
    }

    private fun summarizeTwoConcurrentTracks(eventsByTrack: Map<String, List<PulseEvent>>): String {
        val trackSummaries = eventsByTrack.values.map { trackEvents ->
            summarizeSingleTrack(trackEvents)
        }

        val person1Summary = trackSummaries[0].removePrefix("A person ").removePrefix("A person")
        val person2Summary = trackSummaries[1].removePrefix("A person ").removePrefix("A person")
        return "Two people were detected nearby: one $person1Summary, while another $person2Summary"
    }
}
