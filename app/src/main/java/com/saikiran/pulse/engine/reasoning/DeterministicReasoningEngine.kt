package com.saikiran.pulse.engine.reasoning

import com.saikiran.pulse.engine.change.ChangeEvent
import com.saikiran.pulse.engine.change.ChangeSummarizer
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
        return summarizeSituation(situation)
    }

    override fun resetContext() {
        // No-op for deterministic engine
    }

    override fun isAvailable(): Boolean = true
}
