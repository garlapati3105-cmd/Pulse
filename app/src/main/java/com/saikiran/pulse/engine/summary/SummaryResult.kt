package com.saikiran.pulse.engine.summary

/**
 * Result returned by EventSummarizer.
 *
 * @param text               The generated human-readable natural language summary.
 * @param averageConfidence  Average confidence score of the summarized events.
 * @param isLowConfidence    True if confidence was below threshold requiring cautious phrasing.
 * @param eventCount         Number of events included in the summary.
 */
data class SummaryResult(
    val text: String,
    val averageConfidence: Float,
    val isLowConfidence: Boolean,
    val eventCount: Int,
)
