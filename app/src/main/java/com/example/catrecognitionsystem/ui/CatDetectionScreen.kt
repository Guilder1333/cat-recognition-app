package com.example.catrecognitionsystem.ui

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.catrecognitionsystem.camera.CameraManager
import com.example.catrecognitionsystem.ml.CatDetectionState
import com.example.catrecognitionsystem.ml.CatDetector
import com.example.catrecognitionsystem.tracking.MultiCatTrackingManager
import com.example.catrecognitionsystem.tracking.TrackedCat
import com.example.catrecognitionsystem.tracking.TrackingMode
import com.example.catrecognitionsystem.tracking.ValidationStatus
import com.example.catrecognitionsystem.utils.CatColorAnalyzer
import com.example.catrecognitionsystem.utils.MotionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CatDetectionScreen(
    cameraManager: CameraManager,
    catDetector: CatDetector
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var detectionState by remember { mutableStateOf(CatDetectionState()) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val trackingManager = remember { MultiCatTrackingManager() }
    val motionDetector = remember { MotionDetector() }

    // Master on/off for the detection loop
    var isRunning by remember { mutableStateOf(false) }
    // True while OpenCV is actively tracking a cat; false while scanning for motion
    var isTrackingActive by remember { mutableStateOf(false) }
    // Gate: true while ML detection is in flight — prevents re-entry
    var isDetectionInProgress by remember { mutableStateOf(false) }
    // Color filter chosen by the user before / during monitoring
    var selectedCatColor by remember { mutableStateOf<CatColorAnalyzer.CatColor?>(null) }

    // Bind camera.  Re-runs only when isRunning or previewView changes.
    // isTrackingActive / isDetectionInProgress are Compose state vars; the callback
    // reads their current value at each invocation without needing a rebind.
    LaunchedEffect(previewView, isRunning) {
        android.util.Log.d("CatDetectionScreen", "LaunchedEffect triggered - previewView: ${previewView != null}, isRunning: $isRunning")
        previewView?.let { view ->
            try {
                cameraManager.bindCamera(
                    lifecycleOwner = lifecycleOwner,
                    previewView = view,
                    enableAnalysis = isRunning,
                    onFrameCallback = if (isRunning) { bitmap ->
                        if (isTrackingActive) {
                            // ── TRACKING MODE ──────────────────────────────
                            scope.launch(Dispatchers.Default) {
                                processTrackingFrame(
                                    bitmap = bitmap,
                                    trackingManager = trackingManager,
                                    catDetector = catDetector,
                                    currentState = detectionState,
                                    onStateUpdate = { detectionState = it },
                                    onTrackingLost = {
                                        // Back to monitoring — do NOT stop isRunning
                                        isTrackingActive = false
                                        motionDetector.reset()
                                        detectionState = CatDetectionState(
                                            errorMessage = "Cat lost, resuming motion monitoring"
                                        )
                                        scope.launch(Dispatchers.Default) {
                                            trackingManager.clearAllTrackers()
                                        }
                                        android.util.Log.d("CatDetectionScreen", "Tracking lost, back to monitoring")
                                    }
                                )
                            }
                        } else if (!isDetectionInProgress) {
                            // ── MONITORING MODE ────────────────────────────
                            if (motionDetector.detectMotion(bitmap)) {
                                android.util.Log.d("CatDetectionScreen", "Motion detected, running cat detection")
                                isDetectionInProgress = true
                                // Snapshot the color choice on the main thread before dispatching
                                val colorForTracking = selectedCatColor

                                scope.launch(Dispatchers.Default) {
                                    try {
                                        trackingManager.setTargetColor(colorForTracking)
                                        val detections = catDetector.detectCats(bitmap)
                                        android.util.Log.d("CatDetectionScreen", "Detection after motion: ${detections.size} cats found")

                                        if (detections.isNotEmpty()) {
                                            val trackedCats = trackingManager.initializeTracking(bitmap, detections)
                                            if (trackedCats.isNotEmpty()) {
                                                withContext(Dispatchers.Main) {
                                                    detectionState = CatDetectionState(
                                                        detections = detections,
                                                        hasCats = true,
                                                        trackingMode = TrackingMode.ActiveTracking(trackedCats, 0),
                                                        trackedCats = trackedCats,
                                                        debugInfo = catDetector.lastDebugInfo
                                                    )
                                                    isTrackingActive = true
                                                    isDetectionInProgress = false
                                                }
                                                android.util.Log.d("CatDetectionScreen", "Cat found after motion, tracking started")
                                                return@launch
                                            }
                                        }

                                        // No cat (or filtered out) — stay in monitoring
                                        withContext(Dispatchers.Main) {
                                            detectionState = detectionState.copy(
                                                debugInfo = catDetector.lastDebugInfo,
                                                errorMessage = null
                                            )
                                            isDetectionInProgress = false
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("CatDetectionScreen", "Detection error after motion", e)
                                        withContext(Dispatchers.Main) {
                                            isDetectionInProgress = false
                                        }
                                    }
                                }
                            }
                        }
                        // else: detection in progress, skip this frame
                    } else null
                )
                android.util.Log.d("CatDetectionScreen", "Camera bound successfully")
            } catch (e: Exception) {
                android.util.Log.e("CatDetectionScreen", "Camera binding failed", e)
                detectionState = detectionState.copy(errorMessage = "Camera initialization failed: ${e.message}")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title
        Text(
            text = "Cat Recognition System",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // Camera preview (always visible) + tracking overlay + spinner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { previewView = it }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isTrackingActive && detectionState.trackedCats.isNotEmpty()) {
                TrackingOverlay(
                    trackedCats = detectionState.trackedCats,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (isDetectionInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Status card
        DetectionResultsCard(
            state = detectionState,
            trackingColor = selectedCatColor,
            isTracking = isTrackingActive,
            isRunning = isRunning,
            isDetectionInProgress = isDetectionInProgress
        )

        // Color filter — visible whenever we are not actively tracking
        if (!isTrackingActive) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Select Cat Color to Track",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedCatColor == null,
                            onClick = { selectedCatColor = null },
                            label = { Text("Any Color") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedCatColor == CatColorAnalyzer.CatColor.BLACK,
                            onClick = { selectedCatColor = CatColorAnalyzer.CatColor.BLACK },
                            label = { Text("Black") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedCatColor == CatColorAnalyzer.CatColor.TABBY,
                            onClick = { selectedCatColor = CatColorAnalyzer.CatColor.TABBY },
                            label = { Text("Tabby") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Single Start / Stop button
        Button(
            onClick = {
                if (!isRunning) {
                    trackingManager.setTargetColor(selectedCatColor)
                    motionDetector.reset()
                    isRunning = true
                    isTrackingActive = false
                    isDetectionInProgress = false
                    detectionState = CatDetectionState()
                    android.util.Log.d("CatDetectionScreen", "Detection started, monitoring for motion")
                } else {
                    // Stop everything
                    isRunning = false
                    isTrackingActive = false
                    isDetectionInProgress = false
                    motionDetector.reset()
                    detectionState = CatDetectionState()
                    scope.launch(Dispatchers.Default) {
                        trackingManager.clearAllTrackers()
                    }
                    android.util.Log.d("CatDetectionScreen", "Detection stopped")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRunning) "Stop Detection" else "Start Detection")
        }

        // Error message
        if (detectionState.errorMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = detectionState.errorMessage!!,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

    }
}

@Composable
fun DetectionResultsCard(
    state: CatDetectionState,
    trackingColor: CatColorAnalyzer.CatColor? = null,
    isTracking: Boolean = false,
    isRunning: Boolean = false,
    isDetectionInProgress: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = when {
                    isTracking -> "Tracking Status"
                    isRunning  -> "Monitoring"
                    else       -> "Detection"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (isTracking && state.trackedCats.isNotEmpty()) {
                // ── Active tracking display ─────────────────────────
                if (trackingColor != null) {
                    Text(
                        text = "Tracking: ${CatColorAnalyzer.getColorName(trackingColor)} cats only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                val cat = state.trackedCats.first()
                val colorName = CatColorAnalyzer.getColorName(cat.lockedColor)

                val statusText = when (cat.validationStatus) {
                    ValidationStatus.VALID      -> "Tracking active"
                    ValidationStatus.UNCERTAIN  -> "Tracking uncertain (${cat.consecutiveValidationFailures} checks failed)"
                    ValidationStatus.INVALID    -> "Tracking lost"
                }
                val statusColor = when (cat.validationStatus) {
                    ValidationStatus.VALID      -> Color.Green
                    ValidationStatus.UNCERTAIN  -> Color.Yellow
                    ValidationStatus.INVALID    -> Color.Red
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = statusColor
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$colorName Cat (locked): ${(cat.confidence * 100).toInt()}% confidence",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (cat.validationStatus != ValidationStatus.VALID) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Validation failures: ${cat.consecutiveValidationFailures}/3",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (isRunning) {
                // ── Monitoring / detection-in-progress display ───────
                val statusText = if (isDetectionInProgress) {
                    "Motion detected, checking for cats..."
                } else {
                    "Monitoring for motion..."
                }
                val statusColor = if (isDetectionInProgress) Color.Yellow else Color(0xFF00BCD4)

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = statusColor
                )
            } else {
                // ── Idle ─────────────────────────────────────────────
                Text(
                    text = "Press 'Start Detection' to begin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TrackingOverlay(
    trackedCats: List<TrackedCat>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        trackedCats.forEach { cat ->
            val left   = cat.boundingBox.left   * size.width
            val top    = cat.boundingBox.top    * size.height
            val right  = cat.boundingBox.right  * size.width
            val bottom = cat.boundingBox.bottom * size.height

            val color = when (cat.validationStatus) {
                ValidationStatus.VALID      -> cat.displayColor
                ValidationStatus.UNCERTAIN  -> Color.Yellow
                ValidationStatus.INVALID    -> Color.Red
            }
            val strokeWidth = when (cat.validationStatus) {
                ValidationStatus.VALID      -> 4f
                ValidationStatus.UNCERTAIN  -> 5f
                ValidationStatus.INVALID    -> 6f
            }

            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = strokeWidth)
            )

            val colorName = CatColorAnalyzer.getColorName(cat.lockedColor)
            val confidenceText = "${(cat.confidence * 100).toInt()}%"
            val statusIndicator = when (cat.validationStatus) {
                ValidationStatus.VALID      -> ""
                ValidationStatus.UNCERTAIN  -> " [?]"
                ValidationStatus.INVALID    -> " [LOST]"
            }
            val label = "$colorName $confidenceText$statusIndicator"

            val textColor = when (cat.validationStatus) {
                ValidationStatus.VALID      -> android.graphics.Color.WHITE
                ValidationStatus.UNCERTAIN  -> android.graphics.Color.YELLOW
                ValidationStatus.INVALID    -> android.graphics.Color.RED
            }

            val paint = android.graphics.Paint().apply {
                setColor(textColor)
                textSize = 40f
                isAntiAlias = true
                setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            }

            drawContext.canvas.nativeCanvas.drawText(
                label,
                left,
                maxOf(top - 10f, 50f),
                paint
            )
        }
    }
}

private suspend fun processTrackingFrame(
    bitmap: Bitmap,
    trackingManager: MultiCatTrackingManager,
    catDetector: CatDetector,
    currentState: CatDetectionState,
    onStateUpdate: (CatDetectionState) -> Unit,
    onTrackingLost: () -> Unit
) {
    try {
        val currentCats = currentState.trackedCats
        val currentCat = currentCats.firstOrNull() ?: return

        android.util.Log.d("CatDetectionScreen", "Processing tracking frame: ${bitmap.width}x${bitmap.height}, tracked cats: ${currentCats.size}")

        val shouldValidate = trackingManager.shouldRunDetection() ||
                             trackingManager.shouldRunValidation(currentCat)

        if (shouldValidate) {
            android.util.Log.d("CatDetectionScreen", "Running periodic detection/validation")

            val detections = catDetector.detectCats(bitmap)
            android.util.Log.d("CatDetectionScreen", "Periodic detection found ${detections.size} cats")

            val validationResult = trackingManager.mergeDetectionsWithTracking(
                bitmap, detections, currentCats
            )

            android.util.Log.d("CatDetectionScreen", "Validation result: passed=${validationResult.validationPassed}, shouldStop=${validationResult.shouldStopTracking}")

            if (validationResult.shouldStopTracking) {
                android.util.Log.w("CatDetectionScreen", "TRACKING LOST - returning to motion monitoring")
                withContext(Dispatchers.Main) {
                    onTrackingLost()
                }
                return
            }

            withContext(Dispatchers.Main) {
                onStateUpdate(currentState.copy(
                    trackedCats = listOf(validationResult.updatedCat),
                    hasCats = true,
                    debugInfo = catDetector.lastDebugInfo
                ))
            }
        } else {
            // Just update tracker position — no ML
            val updatedCats = trackingManager.processFrame(bitmap, currentCats)

            if (updatedCats.size != currentCats.size || updatedCats.firstOrNull()?.isLost != currentCats.firstOrNull()?.isLost) {
                android.util.Log.d("CatDetectionScreen", "Tracking update: ${updatedCats.size} cats, lost: ${updatedCats.firstOrNull()?.isLost}")
            }

            withContext(Dispatchers.Main) {
                onStateUpdate(currentState.copy(
                    trackedCats = updatedCats,
                    hasCats = updatedCats.isNotEmpty()
                ))
            }
        }

    } catch (e: Exception) {
        android.util.Log.e("CatDetectionScreen", "Error processing tracking frame", e)
        withContext(Dispatchers.Main) {
            onStateUpdate(currentState.copy(
                errorMessage = "Tracking error: ${e.message}"
            ))
        }
    }
}
