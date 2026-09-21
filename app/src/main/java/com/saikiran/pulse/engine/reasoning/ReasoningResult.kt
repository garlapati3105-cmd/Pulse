package com.saikiran.pulse.engine.reasoning

/**
 * Immutable output result from a local reasoning engine query.
 */
data class ReasoningResult(
    val text: String,
    val confidence: Float,
    val isFallback: Boolean = false,
    val reasoningLatencyMs: Long = 0L,
    val isLowConfidence: Boolean = false,
)
