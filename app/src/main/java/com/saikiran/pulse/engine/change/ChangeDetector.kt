package com.saikiran.pulse.engine.change

import com.saikiran.pulse.engine.events.EventType
import com.saikiran.pulse.engine.events.PulseEvent
import com.saikiran.pulse.engine.summary.SummaryResult
import java.util.ArrayDeque

/**
 * Thread-safe Change Detector that tracks semantic state transitions and identifies unconsumed changes
 * ("What Changed?").
 *
 * Responsibilities:
 *  1. Receives [PulseEvent]s emitted on state transitions.
 *  2. Maps perception event types into immutable [ChangeEvent]s with conservative wording.
 *  3. Prevents duplicate change spam by recording state snapshot history.
 *  4. Maintains unconsumed change queue until explicitly queried by "WHAT CHANGED?".
 *  5. Marks changes as consumed upon user query so subsequent clicks avoid repeating identical changes.
 */
class ChangeDetector(
    private val maxHistorySize: Int = 30,
) {
    private val lock = Any()
    private val unconsumedChanges = mutableListOf<ChangeEvent>()
    private val changeHistory = ArrayDeque<ChangeEvent>()

    /**
     * Process a new semantic event and detect if it constitutes a valid state change.
     */
    fun processEvent(event: PulseEvent) {
        synchronized(lock) {
            val changeType = mapEventTypeToChangeType(event.eventType)

            val description = generateConservativeDescription(changeType)

            val changeEvent = ChangeEvent(
                timestamp = event.timestamp,
                trackId = event.trackId,
                changeType = changeType,
                confidence = event.confidence,
                description = description,
                source = event.source,
            )

            // Add to unconsumed list for "WHAT CHANGED?" queries
            unconsumedChanges.add(changeEvent)

            // Add to rolling history queue
            changeHistory.addLast(changeEvent)
            while (changeHistory.size > maxHistorySize) {
                changeHistory.removeFirst()
            }
        }
    }

    /**
     * Retrieve recent unconsumed changes and mark them as consumed/acknowledged.
     * Summarizes changes via [ChangeSummarizer].
     */
    fun getRecentChangesAndConsume(): SummaryResult {
        synchronized(lock) {
            if (unconsumedChanges.isEmpty()) {
                return SummaryResult(
                    text = "No new changes since your last check.",
                    averageConfidence = 1.0f,
                    isLowConfidence = false,
                    eventCount = 0,
                )
            }

            val changesToSummarize = unconsumedChanges.toList()
            unconsumedChanges.clear()

            return ChangeSummarizer.summarizeChanges(changesToSummarize)
        }
    }

    /** Reset state snapshot and unconsumed queues. */
    fun reset() {
        synchronized(lock) {
            unconsumedChanges.clear()
            changeHistory.clear()
        }
    }

    private fun mapEventTypeToChangeType(eventType: EventType): ChangeType {
        return when (eventType) {
            EventType.PERSON_ENTERED_VIEW -> ChangeType.PERSON_ENTERED_VIEW
            EventType.PERSON_APPROACHING  -> ChangeType.PERSON_APPROACHING
            EventType.PERSON_MOVING_AWAY  -> ChangeType.PERSON_MOVING_AWAY
            EventType.PERSON_STOPPED      -> ChangeType.PERSON_STOPPED
            EventType.PERSON_PASSING_BY   -> ChangeType.PERSON_PASSING_BY
            EventType.PERSON_TRACK_LOST   -> ChangeType.PERSON_TRACK_LOST
            EventType.PERSON_LEFT_VIEW    -> ChangeType.PERSON_LEFT_VIEW
        }
    }

    private fun generateConservativeDescription(changeType: ChangeType): String {
        return when (changeType) {
            ChangeType.PERSON_ENTERED_VIEW -> "A person entered your field of view."
            ChangeType.PERSON_APPROACHING  -> "A person moved closer to you."
            ChangeType.PERSON_MOVING_AWAY  -> "A person moved away from you."
            ChangeType.PERSON_STOPPED      -> "A person stopped moving."
            ChangeType.PERSON_PASSING_BY   -> "A person passed by."
            ChangeType.PERSON_TRACK_LOST   -> "I lost visual contact with a person."
            ChangeType.PERSON_LEFT_VIEW    -> "A person walked out of view."
        }
    }
}
