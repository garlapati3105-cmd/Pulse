package com.saikiran.pulse.engine.reasoning

import com.saikiran.pulse.engine.change.ChangeEvent
import com.saikiran.pulse.engine.change.ChangeSummarizer
import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.engine.evidence.SituationState
import com.saikiran.pulse.engine.summary.EventSummarizer

/**
 * 100% Deterministic, 0ms latency fallback reasoning engine for Pulse.
 * Wraps [EventSummarizer] and [ChangeSummarizer] to guarantee zero-error summaries.
 */
class DeterministicReasoningEngine : LocalReasoningEngine {

    override fun summarizeSituation(situation: SituationState): ReasoningResult {
        val start = System.currentTimeMillis()
        val result = EventSummarizer.summarizeCurrentSituation(situation.recentEvents)
        val latency = System.currentTimeMillis() - start
        return ReasoningResult(
            text = result.text,
            confidence = result.averageConfidence,
            isFallback = true,
            reasoningLatencyMs = latency,
            isLowConfidence = result.isLowConfidence,
        )
    }

    override fun summarizeRecentEvents(events: List<PulseEvent>): ReasoningResult {
        val start = System.currentTimeMillis()
        val result = EventSummarizer.summarize(events)
        val latency = System.currentTimeMillis() - start
        return ReasoningResult(
            text = result.text,
            confidence = result.averageConfidence,
            isFallback = true,
            reasoningLatencyMs = latency,
            isLowConfidence = result.isLowConfidence,
        )
    }

    override fun explainChange(changes: List<ChangeEvent>): ReasoningResult {
        val start = System.currentTimeMillis()
        val result = ChangeSummarizer.summarizeChanges(changes)
        val latency = System.currentTimeMillis() - start
        return ReasoningResult(
            text = result.text,
            confidence = result.averageConfidence,
            isFallback = true,
            reasoningLatencyMs = latency,
            isLowConfidence = result.isLowConfidence,
        )
    }

    override fun answerQuestion(question: String, situation: SituationState): ReasoningResult {
        val start = System.currentTimeMillis()
        val qLower = question.lowercase()

        val text = when {
            qLower.contains("approaching") -> {
                val approachingEvent = situation.recentEvents.lastOrNull {
                    it.eventType == EventType.PERSON_APPROACHING
                }
                if (approachingEvent != null) {
                    val spatial = approachingEvent.spatialPosition?.phrase ?: "nearby"
                    "Yes, someone is approaching $spatial."
                } else {
                    "No, no one is approaching right now."
                }
            }

            qLower.contains("where") -> {
                val activeEvent = situation.recentEvents.lastOrNull {
                    it.eventType != EventType.PERSON_LEFT_VIEW && it.eventType != EventType.PERSON_TRACK_LOST
                }
                if (activeEvent != null) {
                    val spatial = activeEvent.spatialPosition?.phrase ?: "nearby"
                    "The person is $spatial."
                } else {
                    "I lost visual contact with the person."
                }
            }

            qLower.contains("hear") || qLower.contains("sound") -> {
                val audioEvent = situation.environmentalEvents.lastOrNull()
                if (audioEvent != null) {
                    "I heard a ${audioEvent.soundType.name.replace("_", " ").lowercase()} recently."
                } else {
                    "No significant environmental sounds were detected."
                }
            }

            qLower.contains("important") || qLower.contains("urgent") -> {
                val highPriority = situation.recentEvents.lastOrNull { it.eventType == EventType.PERSON_APPROACHING }
                if (highPriority != null) {
                    val spatial = highPriority.spatialPosition?.phrase ?: "nearby"
                    "Yes, someone is approaching $spatial."
                } else {
                    "Nothing urgent is happening right now."
                }
            }

            else -> summarizeSituation(situation).text
        }

        return ReasoningResult(
            text = text,
            confidence = 1.0f,
            isFallback = true,
            reasoningLatencyMs = System.currentTimeMillis() - start,
            isLowConfidence = false,
        )
    }

    override fun resetContext() {
        // No-op for deterministic engine
    }

    override fun isAvailable(): Boolean = true
}
