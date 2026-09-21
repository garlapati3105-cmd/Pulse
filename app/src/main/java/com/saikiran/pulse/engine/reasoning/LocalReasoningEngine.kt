package com.saikiran.pulse.engine.reasoning

import com.saikiran.pulse.engine.change.ChangeEvent
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.engine.evidence.SituationState

/**
 * Local Reasoning Engine interface for Pulse situational awareness queries.
 * Decouples Pulse perception pipeline from specific local AI / deterministic implementations.
 */
interface LocalReasoningEngine {

    /** Summarize the active situation state ("What's happening?"). */
    fun summarizeSituation(situation: SituationState): ReasoningResult

    /** Summarize recent chronological events ("What just happened?"). */
    fun summarizeRecentEvents(events: List<PulseEvent>): ReasoningResult

    /** Explain recent state changes ("What changed?"). */
    fun explainChange(changes: List<ChangeEvent>): ReasoningResult

    /** Answer a specific contextual question about the current situation state. */
    fun answerQuestion(question: String, situation: SituationState): ReasoningResult

    /** Reset conversation context / memory if applicable. */
    fun resetContext()

    /** Returns true if local reasoning engine is available on this device. */
    fun isAvailable(): Boolean
}
