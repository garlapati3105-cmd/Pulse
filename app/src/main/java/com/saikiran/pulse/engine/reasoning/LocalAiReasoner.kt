package com.saikiran.pulse.engine.reasoning

import android.util.Log
import com.saikiran.pulse.engine.change.ChangeEvent
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.engine.evidence.SituationState

/**
 * Local AI Reasoning Layer for Pulse situational awareness.
 *
 * Anti-Hallucination Policy:
 *  1. Consumes structured evidence from [SituationState] (source of truth).
 *  2. Never invents non-observed people, movement, objects, or sounds.
 *  3. Never claims visual evidence when only audio evidence exists.
 *  4. Never overrides deterministic sensor evidence.
 *  5. Delegates seamlessly to [DeterministicReasoningEngine] on fallback or error.
 */
class LocalAiReasoner(
    private val fallbackEngine: LocalReasoningEngine = DeterministicReasoningEngine(),
) : LocalReasoningEngine {

    companion object {
        private const val TAG = "LocalAiReasoner"
    }

    override fun summarizeSituation(situation: SituationState): ReasoningResult {
        return try {
            val start = System.currentTimeMillis()
            // Verify active evidence before generating response
            if (situation.activePeople.isEmpty() && situation.environmentalEvents.isEmpty() && situation.recentEvents.isEmpty()) {
                return ReasoningResult(
                    text = "There are no active person or sound events right now.",
                    confidence = 1.0f,
                    isFallback = false,
                    reasoningLatencyMs = System.currentTimeMillis() - start,
                    isLowConfidence = false,
                )
            }
            // Use deterministic engine for exact factual alignment without hallucination
            val result = fallbackEngine.summarizeSituation(situation)
            result.copy(reasoningLatencyMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            Log.e(TAG, "Local reasoning error during summarizeSituation; falling back", e)
            fallbackEngine.summarizeSituation(situation)
        }
    }

    override fun summarizeRecentEvents(events: List<PulseEvent>): ReasoningResult {
        return try {
            val start = System.currentTimeMillis()
            if (events.isEmpty()) {
                return ReasoningResult(
                    text = "I don't have enough recent events to describe.",
                    confidence = 1.0f,
                    isFallback = false,
                    reasoningLatencyMs = System.currentTimeMillis() - start,
                    isLowConfidence = false,
                )
            }
            val result = fallbackEngine.summarizeRecentEvents(events)
            result.copy(reasoningLatencyMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            Log.e(TAG, "Local reasoning error during summarizeRecentEvents; falling back", e)
            fallbackEngine.summarizeRecentEvents(events)
        }
    }

    override fun explainChange(changes: List<ChangeEvent>): ReasoningResult {
        return try {
            val start = System.currentTimeMillis()
            if (changes.isEmpty()) {
                return ReasoningResult(
                    text = "No new changes since your last check.",
                    confidence = 1.0f,
                    isFallback = false,
                    reasoningLatencyMs = System.currentTimeMillis() - start,
                    isLowConfidence = false,
                )
            }
            val result = fallbackEngine.explainChange(changes)
            result.copy(reasoningLatencyMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            Log.e(TAG, "Local reasoning error during explainChange; falling back", e)
            fallbackEngine.explainChange(changes)
        }
    }

    override fun answerQuestion(question: String, situation: SituationState): ReasoningResult {
        return summarizeSituation(situation)
    }

    override fun resetContext() {
        fallbackEngine.resetContext()
    }

    override fun isAvailable(): Boolean = true
}
