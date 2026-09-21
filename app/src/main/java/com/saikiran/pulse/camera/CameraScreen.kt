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
import com.saikiran.pulse.engine.alerts.ProactiveAlertCoordinator
import com.saikiran.pulse.engine.priority.PriorityLevel
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

    // Proactive Alert Coordinator (Milestone 6B) - Defaults to ON for testing
    val proactiveAlertCoordinator = remember { ProactiveAlertCoordinator(ttsManager) }
    var isProactiveVoiceEnabled by remember { mutableStateOf(true) } // Default ON for testing
    var isMuted by remember { mutableStateOf(false) }

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
        // NOTE: .also { it.setup() } deliberately removed — see PersonAnalyzer
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

    // Register volume hardware key handlers for hands-free queries (Priority P2)
    LaunchedEffect(events) {
        onRegisterVolumeKeyHandlers?.invoke(
            {
                // Volume Up -> "WHAT JUST HAPPENED?"
                val summary = EventSummarizer.summarize(events)
                currentSummaryTitle = "What Just Happened?"
                currentSummaryResult = summary
                showSummaryDialog = true
                ttsManager.speak(summary.text)
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

        // ── Priority Engine & Proactive Alert Debug Panel (Milestones 6A & 6B) ─
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 40.dp, start = 16.dp)
                .fillMaxWidth(0.55f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

            // ── Environmental Audio Perception Debug Panel (Milestone 7A) ───
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
            
            // ── Sensor Fusion Engine Debug Panel (Milestone 7B) ─────────────
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
                            color = Color(0xFFE040FB), // Purple
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Event: ${fused.eventType.name}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Vision Conf: ${fused.visionConfidence?.let { "${(it * 100).toInt()}%" } ?: "N/A"}",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = "Audio: ${fused.audioType ?: "NONE"} ${fused.audioConfidence?.let { "(${(it * 100).toInt()}%)" } ?: ""}",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = "IMU State: ${fused.imuState ?: "N/A"}",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = "Fused Confidence: ${(fused.fusedConfidence * 100).toInt()}%",
                            color = if (fused.fusedConfidence >= 0.7f) Color(0xFF00E676) else if (fused.fusedConfidence >= 0.45f) Color(0xFFFFB74D) else Color.Red,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
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

        // ── Top Header Controls (Proactive Voice & Mute Toggles) ────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            Button(
                onClick = {
                    isMuted = !isMuted
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMuted) Color(0xFFFFB74D) else Color(0xFF424242),
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = if (isMuted) "MUTE: ON" else "MUTE: OFF",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Button(
                onClick = {
                    isProactiveVoiceEnabled = !isProactiveVoiceEnabled
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isProactiveVoiceEnabled) Color(0xFFFF5252) else Color(0xFF424242),
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = if (isProactiveVoiceEnabled) "PROACTIVE VOICE: ON" else "PROACTIVE VOICE: OFF",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // ── Action Buttons ("WHAT JUST HAPPENED?" & "WHAT CHANGED?") ──────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Button(
                onClick = {
                    val summary = EventSummarizer.summarize(events)
                    currentSummaryTitle = "What Just Happened?"
                    currentSummaryResult = summary
                    showSummaryDialog = true
                    ttsManager.speak(summary.text)
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

        // ── Developer Debug Event History Panel ─────────────────────────────
        if (events.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "── Event History (Rolling 20s) ──",
                        color = Color.Green,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    events.takeLast(4).forEach { event ->
                        Text(
                            text = "${event.formattedTime()}  ${event.description}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E1E1E),
                    ),
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
                                onClick = {
                                    currentSummaryResult?.let { ttsManager.speak(it.text) }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF424242),
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Text("REPEAT")
                            }

                            Button(
                                onClick = {
                                    ttsManager.stop()
                                    showSummaryDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00E676),
                                    contentColor = Color.Black,
                                ),
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
