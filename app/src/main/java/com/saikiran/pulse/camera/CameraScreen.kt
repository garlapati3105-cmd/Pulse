package com.saikiran.pulse.camera

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saikiran.pulse.audio.TtsManager
import com.saikiran.pulse.audio.haptics.HapticFeedbackManager
import com.saikiran.pulse.audio.voice.VoiceCommand
import com.saikiran.pulse.audio.voice.VoiceCommandManager
import com.saikiran.pulse.audio.voice.VoiceCommandState
import com.saikiran.pulse.engine.alerts.ProactiveAlertCoordinator
import com.saikiran.pulse.engine.evidence.AudioEventState
import com.saikiran.pulse.engine.evidence.SituationState
import com.saikiran.pulse.engine.priority.PriorityLevel
import com.saikiran.pulse.engine.reasoning.LocalAiReasoner
import com.saikiran.pulse.engine.summary.EventSummarizer
import com.saikiran.pulse.engine.summary.SummaryResult
import com.saikiran.pulse.perception.audio.AudioPerceptionManager
import com.saikiran.pulse.perception.audio.AudioPerceptionState
import com.saikiran.pulse.perception.sensors.SensorMotionMonitor
import com.saikiran.pulse.perception.vision.PersonAnalyzer
import com.saikiran.pulse.perception.vision.PersonDetectionListener
import com.saikiran.pulse.perception.vision.PersonDetectionResult
import com.saikiran.pulse.perception.vision.PersonDetector
import com.saikiran.pulse.perception.vision.PersonOverlayView
import com.saikiran.pulse.ui.PulseOnboardingCard
import java.util.concurrent.Executors

@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    hasAudioPermission: Boolean = false,
    onRegisterVolumeKeyHandlers: ((onUp: () -> Unit, onDown: () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Single background thread for ImageAnalysis.
    // MediaPipe LIVE_STREAM requires setup() and detectAsync() on the same thread —
    // PersonAnalyzer handles lazy setup on first frame from this executor.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // Sensor monitor for tracking phone camera motion state
    val sensorMotionMonitor = remember { SensorMotionMonitor(context) }

    // On-device Environmental Audio Perception Manager (Milestone 7A)
    val audioPerceptionManager = remember { AudioPerceptionManager(context) }

    // Native TextToSpeech manager for spoken natural language summaries
    val ttsManager = remember { TtsManager(context) }

    // Tactile Haptic Feedback Channel (Phase 11)
    val hapticFeedbackManager = remember { HapticFeedbackManager(context) }

    // On-Device Voice Command Manager (Milestone 7C)
    val voiceCommandManager = remember { VoiceCommandManager(context) }

    // Local AI Reasoning Layer (Phase 9)
    val localAiReasoner = remember { LocalAiReasoner() }

    // Proactive Alert Coordinator (Milestone 6B & Phase 11 Haptics)
    val proactiveAlertCoordinator = remember { ProactiveAlertCoordinator(ttsManager, hapticFeedbackManager) }
    var isProactiveVoiceEnabled by remember { mutableStateOf(true) } // Default ON for testing
    var isMuted by remember { mutableStateOf(false) }

    // Developer Telemetry Mode Toggle (Phase 12 UX Polish)
    var isDeveloperModeEnabled by remember { mutableStateOf(false) }

    // Demo Mode Guide State (Phase 12 Demo Polish)
    var showDemoGuideDialog by remember { mutableStateOf(false) }

    // First Launch Onboarding State (Phase 12 Onboarding)
    var showOnboardingCard by remember { mutableStateOf(true) }

    // Reference to the overlay view so the detector callback can update it
    val overlayRef = remember { mutableStateOf<PersonOverlayView?>(null) }

    // State for natural language summarization dialogs
    var showSummaryDialog by remember { mutableStateOf(false) }
    var currentSummaryTitle by remember { mutableStateOf("What Just Happened?") }
    var currentSummaryResult by remember { mutableStateOf<SummaryResult?>(null) }

    // Create PersonDetector — do NOT call setup() here (main thread).
    // setup() is called lazily inside PersonAnalyzer on the first analysed frame.
    val personDetector = remember {
        PersonDetector(
            context = context,
            sensorMotionMonitor = sensorMotionMonitor,
            listener = object : PersonDetectionListener {
                override fun onPersonDetected(result: PersonDetectionResult) {
                    overlayRef.value?.updateDetections(result)
                }
                override fun onError(message: String) {
                    Log.e("CameraScreen", "Detection error: $message")
                }
            }
        )
    }

    // Connect proactive alert coordinator to temporal event store
    LaunchedEffect(isProactiveVoiceEnabled, isMuted) {
        proactiveAlertCoordinator.isProactiveVoiceEnabled = isProactiveVoiceEnabled
        proactiveAlertCoordinator.isMuted = isMuted
        personDetector.eventStore.proactiveAlertCoordinator = proactiveAlertCoordinator
    }

    // Start / Stop Environmental Audio Perception after ObjectDetector initializes first
    DisposableEffect(hasAudioPermission) {
        if (hasAudioPermission) {
            analysisExecutor.execute {
                try {
                    Thread.sleep(800L) // Wait 800ms to guarantee ObjectDetector JNI graph initializes first
                } catch (ignored: Exception) {}
                audioPerceptionManager.start()
            }
        }
        onDispose {
            audioPerceptionManager.stop()
        }
    }

    // Lifecycle-aware collection of chronological events from TemporalEventStore
    val events by personDetector.eventStore.eventsFlow.collectAsStateWithLifecycle()

    // Lifecycle-aware collection of latest decision from PriorityEngine
    val latestPriorityDecision by personDetector.eventStore.priorityEngine.latestDecisionFlow.collectAsStateWithLifecycle()

    // Lifecycle-aware collection of latest proactive alert audit log
    val latestAuditLog by proactiveAlertCoordinator.latestAuditLog.collectAsStateWithLifecycle()

    // Lifecycle-aware collection of Audio Perception State & Events (Milestone 7A)
    val audioState by audioPerceptionManager.stateFlow.collectAsStateWithLifecycle()
    val latestAudioEvent by audioPerceptionManager.latestAudioEventFlow.collectAsStateWithLifecycle()

    // Pass detected environmental sounds to SensorFusionEngine (Milestone 7B)
    LaunchedEffect(latestAudioEvent) {
        latestAudioEvent?.let { audioEvent ->
            personDetector.fusionEngine.onAudioEvent(audioEvent)
        }
    }

    // Lifecycle-aware collection of Fused Events (Milestone 7B)
    val latestFusedEvent by personDetector.fusionEngine.latestFusedEventFlow.collectAsStateWithLifecycle()

    // Lifecycle-aware collection of Explicit Voice Command States (Milestone 7C)
    val voiceCommandState by voiceCommandManager.stateFlow.collectAsStateWithLifecycle()
    val recognizedText by voiceCommandManager.recognizedTextFlow.collectAsStateWithLifecycle()
    val voiceStatusMessage by voiceCommandManager.statusMessageFlow.collectAsStateWithLifecycle()
    val lastCommand by voiceCommandManager.lastCommandFlow.collectAsStateWithLifecycle()

    // Helper to build structured SituationState for Local AI Reasoning Layer
    val buildSituationState: () -> SituationState = {
        val audioList = latestAudioEvent?.let { audio ->
            listOf(AudioEventState(audio.soundType, audio.label, audio.confidence, audio.timestamp))
        } ?: emptyList()

        SituationState(
            timestampMs = System.currentTimeMillis(),
            activePeople = emptyList(), // Filled dynamically if tracks are active
            environmentalEvents = audioList,
            recentEvents = events,
            sensorState = sensorMotionMonitor.currentMotionState,
            overallConfidence = 1.0f,
        )
    }

    // Deterministic Voice Command Execution Function
    val executeVoiceCommand: (String, VoiceCommand) -> Unit = remember(events) {
        { text, command ->
            when (command) {
                VoiceCommand.SHOW_CURRENT_SITUATION -> {
                    val situation = buildSituationState()
                    val result = localAiReasoner.summarizeSituation(situation)
                    currentSummaryTitle = "Current Situation"
                    currentSummaryResult = SummaryResult(
                        text = result.text,
                        averageConfidence = result.confidence,
                        isLowConfidence = result.isLowConfidence,
                        eventCount = events.size,
                    )
                    showSummaryDialog = true
                    ttsManager.speak(result.text)
                }

                VoiceCommand.SHOW_RECENT_EVENT_SUMMARY -> {
                    val result = localAiReasoner.summarizeRecentEvents(events)
                    currentSummaryTitle = "What Just Happened?"
                    currentSummaryResult = SummaryResult(
                        text = result.text,
                        averageConfidence = result.confidence,
                        isLowConfidence = result.isLowConfidence,
                        eventCount = events.size,
                    )
                    showSummaryDialog = true
                    ttsManager.speak(result.text)
                }

                VoiceCommand.SHOW_CHANGES -> {
                    val changeSummary = personDetector.eventStore.changeDetector.getRecentChangesAndConsume()
                    currentSummaryTitle = "What Changed?"
                    currentSummaryResult = changeSummary
                    showSummaryDialog = true
                    ttsManager.speak(changeSummary.text)
                }

                VoiceCommand.REPEAT_LAST_RESPONSE -> {
                    currentSummaryResult?.let {
                        ttsManager.speak(it.text)
                    } ?: run {
                        ttsManager.speak("No recent response to repeat.")
                    }
                }

                VoiceCommand.MUTE_PROACTIVE_VOICE -> {
                    isMuted = true
                    proactiveAlertCoordinator.isMuted = true
                    ttsManager.speak("Proactive voice muted.")
                }

                VoiceCommand.UNMUTE_PROACTIVE_VOICE -> {
                    isMuted = false
                    proactiveAlertCoordinator.isMuted = false
                    ttsManager.speak("Proactive voice unmuted.")
                }

                VoiceCommand.IS_ANYONE_APPROACHING -> {
                    val situation = buildSituationState()
                    val result = localAiReasoner.answerQuestion("is someone approaching", situation)
                    currentSummaryTitle = "Is Someone Approaching?"
                    currentSummaryResult = SummaryResult(
                        text = result.text,
                        averageConfidence = result.confidence,
                        isLowConfidence = result.isLowConfidence,
                        eventCount = events.size,
                    )
                    showSummaryDialog = true
                    ttsManager.speak(result.text)
                }

                VoiceCommand.WHERE_IS_PERSON -> {
                    val situation = buildSituationState()
                    val result = localAiReasoner.answerQuestion("where is person", situation)
                    currentSummaryTitle = "Where is the Person?"
                    currentSummaryResult = SummaryResult(
                        text = result.text,
                        averageConfidence = result.confidence,
                        isLowConfidence = result.isLowConfidence,
                        eventCount = events.size,
                    )
                    showSummaryDialog = true
                    ttsManager.speak(result.text)
                }

                VoiceCommand.WHAT_DID_YOU_HEAR -> {
                    val situation = buildSituationState()
                    val result = localAiReasoner.answerQuestion("what did you hear", situation)
                    currentSummaryTitle = "What Did You Hear?"
                    currentSummaryResult = SummaryResult(
                        text = result.text,
                        averageConfidence = result.confidence,
                        isLowConfidence = result.isLowConfidence,
                        eventCount = events.size,
                    )
                    showSummaryDialog = true
                    ttsManager.speak(result.text)
                }

                VoiceCommand.ANYTHING_IMPORTANT -> {
                    val situation = buildSituationState()
                    val result = localAiReasoner.answerQuestion("anything important", situation)
                    currentSummaryTitle = "Anything Important?"
                    currentSummaryResult = SummaryResult(
                        text = result.text,
                        averageConfidence = result.confidence,
                        isLowConfidence = result.isLowConfidence,
                        eventCount = events.size,
                    )
                    showSummaryDialog = true
                    ttsManager.speak(result.text)
                }

                VoiceCommand.UNKNOWN -> {
                    val errorMsg = "I didn't understand that command."
                    currentSummaryTitle = "Voice Command"
                    currentSummaryResult = SummaryResult(
                        text = "$errorMsg (Recognized: \"$text\")",
                        averageConfidence = 0.0f,
                        isLowConfidence = true,
                        eventCount = 0,
                    )
                    showSummaryDialog = true
                    ttsManager.speak(errorMsg)
                }
            }
        }
    }

    // Helper to start Voice Input with Microphone Access Coordination
    val triggerVoiceInput: () -> Unit = {
        voiceCommandManager.startListening(
            onListeningStarted = {
                // Pause environmental audio perception while voice input is listening
                audioPerceptionManager.stop()
            },
            onListeningEnded = {
                // Restore environmental audio perception
                if (hasAudioPermission) {
                    audioPerceptionManager.start()
                }
            },
            onCommandExecuted = { text, command ->
                executeVoiceCommand(text, command)
            }
        )
    }

    // Register volume hardware key handlers for hands-free queries (Priority P2)
    LaunchedEffect(events) {
        onRegisterVolumeKeyHandlers?.invoke(
            {
                // Volume Up -> "WHAT JUST HAPPENED?"
                val result = localAiReasoner.summarizeRecentEvents(events)
                currentSummaryTitle = "What Just Happened?"
                currentSummaryResult = SummaryResult(
                    text = result.text,
                    averageConfidence = result.confidence,
                    isLowConfidence = result.isLowConfidence,
                    eventCount = events.size,
                )
                showSummaryDialog = true
                ttsManager.speak(result.text)
            },
            {
                // Volume Down -> "WHAT CHANGED?"
                val changeSummary = personDetector.eventStore.changeDetector.getRecentChangesAndConsume()
                currentSummaryTitle = "What Changed?"
                currentSummaryResult = changeSummary
                showSummaryDialog = true
                ttsManager.speak(changeSummary.text)
            }
        )
    }

    DisposableEffect(Unit) {
        sensorMotionMonitor.start()
        onDispose {
            sensorMotionMonitor.stop()
            personDetector.close()
            ttsManager.shutdown()
            voiceCommandManager.destroy()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // ── Camera Preview ──────────────────────────────────────────────────
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    // KEEP_ONLY_LATEST: CameraX drops frames that arrive while the
                    // analyser is still processing a previous one. This prevents backlog.
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(analysisExecutor, PersonAnalyzer(personDetector))
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis,
                        )
                    } catch (exc: Exception) {
                        Log.e("CameraScreen", "Camera bind failed", exc)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        // ── Bounding Box Overlay ────────────────────────────────────────────
        // PersonOverlayView has a transparent background so the preview shows through
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PersonOverlayView(ctx).also { overlayRef.value = it }
            }
        )

        // ── Clean Operational Status Bar or Developer Telemetry Panels ───────────
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 40.dp, start = 16.dp)
                .fillMaxWidth(0.55f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isDeveloperModeEnabled) {
                // ── Clean Product Status Card for Judges & Users ─────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "●", color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                            Text(
                                text = "PULSE ACTIVE",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Camera: READY (30 FPS)",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "Audio: LISTENING (16kHz)",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "Reasoning: LOCAL ON-DEVICE",
                            color = Color(0xFF29B6F6),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                // ── Developer Telemetry Panels ──────────────────────────────────
                latestPriorityDecision?.let { decision ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "── Priority Decision (6A) ──",
                                color = Color(0xFF00E676),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Event: ${decision.event.description}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Priority: ${decision.priority} (Score: ${decision.score})",
                                color = when (decision.priority) {
                                    PriorityLevel.HIGH -> Color(0xFFFF5252)
                                    PriorityLevel.MEDIUM -> Color(0xFFFFB74D)
                                    PriorityLevel.LOW -> Color.Gray
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Action: ${if (decision.speakNow) "SPEAK NOW" else "IGNORE / COOLDOWN"}",
                                color = if (decision.speakNow) Color(0xFF00E676) else Color.LightGray,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                latestAuditLog?.let { audit ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "── Proactive Audit (6B) ──",
                                color = Color(0xFF29B6F6),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Event: ${audit.eventDescription}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Spoken?: ${if (audit.isSpoken) "YES" else "NO"} (${audit.formattedTime()})",
                                color = if (audit.isSpoken) Color(0xFF00E676) else Color(0xFFFFB74D),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = audit.reason,
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                // ── Environmental Audio Perception Debug Panel ───────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(10.dp)
                ) {
                    Column {
                        Text(
                            text = "── Audio Perception (7A) ──",
                            color = Color(0xFFFF4081),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "State: $audioState",
                            color = when (audioState) {
                                AudioPerceptionState.LISTENING -> Color(0xFF00E676)
                                AudioPerceptionState.NO_PERMISSION -> Color(0xFFFFB74D)
                                AudioPerceptionState.ERROR -> Color(0xFFFF5252)
                                else -> Color.Gray
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                        )
                        latestAudioEvent?.let { audioEvent ->
                            Text(
                                text = "Sound: ${audioEvent.soundType.name} (${audioEvent.label})",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Conf: ${(audioEvent.confidence * 100).toInt()}% (${audioEvent.formattedTime()})",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } ?: Text(
                            text = "Sound: None detected",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // ── Sensor Fusion Engine Debug Panel ─────────────────────
                latestFusedEvent?.let { fused ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "── Sensor Fusion Engine (7B) ──",
                                color = Color(0xFFE040FB),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Event: ${fused.eventType.name}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Sources: ${fused.supportingSources.joinToString(" + ") { it.name }}",
                                color = Color(0xFF29B6F6),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                text = "Reason: ${fused.reason}",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        // ── Top Header Controls (Mute, Proactive, Dev Mode & Demo Buttons) ────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            Button(
                onClick = { isDeveloperModeEnabled = !isDeveloperModeEnabled },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDeveloperModeEnabled) Color(0xFFFFD54F) else Color(0xFF333333),
                    contentColor = if (isDeveloperModeEnabled) Color.Black else Color.White,
                ),
            ) {
                Text(
                    text = if (isDeveloperModeEnabled) "DEV: ON" else "DEV: OFF",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Button(
                onClick = { isMuted = !isMuted },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMuted) Color(0xFFFFB74D) else Color(0xFF424242),
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = if (isMuted) "MUTE: ON" else "MUTE: OFF",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Button(
                onClick = { isProactiveVoiceEnabled = !isProactiveVoiceEnabled },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isProactiveVoiceEnabled) Color(0xFFFF5252) else Color(0xFF424242),
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = if (isProactiveVoiceEnabled) "VOICE: ON" else "VOICE: OFF",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // ── Action Buttons ("WHAT JUST HAPPENED?", "WHAT CHANGED?", 🎙️ VOICE) ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Button(
                onClick = { showDemoGuideDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFD54F),
                    contentColor = Color.Black,
                ),
            ) {
                Text(
                    text = "🎥 DEMO GUIDE",
                    fontWeight = FontWeight.Bold,
                )
            }

            Button(
                onClick = { triggerVoiceInput() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (voiceCommandState) {
                        VoiceCommandState.LISTENING -> Color(0xFFFF5252)
                        VoiceCommandState.PROCESSING -> Color(0xFFFFB74D)
                        else -> Color(0xFFAB47BC)
                    },
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = when (voiceCommandState) {
                        VoiceCommandState.LISTENING -> "🎙️ LISTENING..."
                        VoiceCommandState.PROCESSING -> "🎙️ PROCESSING..."
                        else -> "🎙️ VOICE COMMAND"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }

            Button(
                onClick = {
                    val result = localAiReasoner.summarizeRecentEvents(events)
                    currentSummaryTitle = "What Just Happened?"
                    currentSummaryResult = SummaryResult(
                        text = result.text,
                        averageConfidence = result.confidence,
                        isLowConfidence = result.isLowConfidence,
                        eventCount = events.size,
                    )
                    showSummaryDialog = true
                    ttsManager.speak(result.text)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color.Black,
                ),
            ) {
                Text(
                    text = "WHAT JUST HAPPENED?",
                    fontWeight = FontWeight.Bold,
                )
            }

            Button(
                onClick = {
                    val changeSummary = personDetector.eventStore.changeDetector.getRecentChangesAndConsume()
                    currentSummaryTitle = "What Changed?"
                    currentSummaryResult = changeSummary
                    showSummaryDialog = true
                    ttsManager.speak(changeSummary.text)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF29B6F6),
                    contentColor = Color.Black,
                ),
            ) {
                Text(
                    text = "WHAT CHANGED?",
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ── First-Launch Onboarding Card ─────────────────────────────────────
        if (showOnboardingCard) {
            PulseOnboardingCard(
                onDismiss = { showOnboardingCard = false }
            )
        }

        // ── Demo Guide Dialog ────────────────────────────────────────────────
        if (showDemoGuideDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.80f)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                    ) {
                        Text(
                            text = "🎥 Pulse Demo Walkthrough",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFFFFD54F),
                            fontWeight = FontWeight.Bold,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "1. OBSERVE: Direct camera at space around you.\n" +
                                    "2. DETECT: Person & audio perception run locally.\n" +
                                    "3. UNDERSTAND: Motion & FOV zones evaluated.\n" +
                                    "4. ALERT: Proactive voice speaks high-urgency alerts.\n" +
                                    "5. QUERY: Tap 'Voice Command' & ask 'What's happening?'",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = { showDemoGuideDialog = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black)
                            ) {
                                Text("CLOSE GUIDE")
                            }
                        }
                    }
                }
            }
        }

        // ── Summary Output Dialog / Card ─────────────────────────────────────
        if (showSummaryDialog && currentSummaryResult != null) {
            val result = currentSummaryResult!!
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.60f)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.90f)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                    ) {
                        Text(
                            text = currentSummaryTitle,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFF00E676),
                            fontWeight = FontWeight.Bold,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = result.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (result.isLowConfidence) {
                                "Caution: Low Confidence (${(result.averageConfidence * 100).toInt()}%)"
                            } else {
                                "Confidence: ${(result.averageConfidence * 100).toInt()}% (${result.eventCount} events analyzed)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.isLowConfidence) Color(0xFFFFB74D) else Color.Gray,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = { currentSummaryResult?.let { ttsManager.speak(it.text) } },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242), contentColor = Color.White),
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Text("REPEAT")
                            }

                            Button(
                                onClick = {
                                    ttsManager.stop()
                                    showSummaryDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                            ) {
                                Text("DISMISS")
                            }
                        }
                    }
                }
            }
        }
    }
}
