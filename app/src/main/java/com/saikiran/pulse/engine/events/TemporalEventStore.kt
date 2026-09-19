package com.saikiran.pulse.engine.events

import com.saikiran.pulse.engine.alerts.ProactiveAlertCoordinator
import com.saikiran.pulse.engine.alerts.ProactiveAlertEngine
import com.saikiran.pulse.engine.change.ChangeDetector
import com.saikiran.pulse.engine.priority.PriorityEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Thread-safe in-memory rolling event store.
 *
 * Requirements & Behavior:
 *  1. Rolling Window: Retains events for a configurable duration ([windowDurationMs], default 20 seconds).
 *  2. Auto-Eviction: Purges events older than [windowDurationMs] whenever new events are added or explicitly purged.
 *  3. Thread Safety: Fully synchronized lock ensures background perception threads can safely emit
 *     events while Compose UI observes [eventsFlow] on the main thread.
 *  4. Reactive Stream: Exposes chronological events via [eventsFlow] as [StateFlow<List<PulseEvent>>].
 *  5. Engine Integration: Feeds state transitions directly into [ChangeDetector], [PriorityEngine], and [ProactiveAlertCoordinator].
 */
class TemporalEventStore(
    private val windowDurationMs: Long = 20_000L, // 20-second rolling window
    val changeDetector: ChangeDetector = ChangeDetector(),
    val priorityEngine: PriorityEngine = PriorityEngine(),
    var proactiveAlertEngine: ProactiveAlertEngine? = null,
    var proactiveAlertCoordinator: ProactiveAlertCoordinator? = null,
) {
    private val lock = Any()
    private val eventQueue = ArrayDeque<PulseEvent>()

    private val _eventsFlow = MutableStateFlow<List<PulseEvent>>(emptyList())
    val eventsFlow: StateFlow<List<PulseEvent>> = _eventsFlow.asStateFlow()

    /**
     * Add a new semantic event to the store and enforce the 20-second rolling window.
     */
    fun addEvent(event: PulseEvent) {
        synchronized(lock) {
            val now = event.timestamp
            val cutoff = now - windowDurationMs

            // Add new event
            eventQueue.addLast(event)

            // Feed change detector
            changeDetector.processEvent(event)

            // Evaluate event priority decision
            val decision = priorityEngine.evaluate(event)

            // Feed proactive alert coordinator (Milestone 6B HIGH-priority proactive speech)
            proactiveAlertCoordinator?.processDecision(decision)

            // Legacy trigger proactive alert if enabled
            proactiveAlertEngine?.processEvent(event)

            // Evict events older than rolling 20-second window
            while ((eventQueue.isNotEmpty()) && (eventQueue.first.timestamp < cutoff)) {
                eventQueue.removeFirst()
            }

            // Update StateFlow snapshot
            _eventsFlow.value = eventQueue.toList()
        }
    }

    /**
     * Purge events older than 20-second rolling window.
     */
    fun purgeExpired(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val cutoff = nowMs - windowDurationMs
            var removed = false
            while ((eventQueue.isNotEmpty()) && (eventQueue.first.timestamp < cutoff)) {
                eventQueue.removeFirst()
                removed = true
            }
            if (removed) {
                _eventsFlow.value = eventQueue.toList()
            }
        }
    }

    /** Clear all stored events. */
    fun clear() {
        synchronized(lock) {
            eventQueue.clear()
            changeDetector.reset()
            priorityEngine.reset()
            proactiveAlertCoordinator?.reset()
            proactiveAlertEngine?.reset()
            _eventsFlow.value = emptyList()
        }
    }
}
