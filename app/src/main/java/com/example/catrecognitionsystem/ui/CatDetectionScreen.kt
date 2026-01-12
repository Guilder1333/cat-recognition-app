package com.example.catrecognitionsystem.ui

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.example.catrecognitionsystem.camera.CameraManager
import com.example.catrecognitionsystem.ml.CatDetectionState
import com.example.catrecognitionsystem.ml.CatDetector
import com.example.catrecognitionsystem.ml.DetectionResult
import com.example.catrecognitionsystem.tracking.MultiCatTrackingManager
import com.example.catrecognitionsystem.tracking.TrackedCat
import com.example.catrecognitionsystem.tracking.TrackingMode
import com.example.catrecognitionsystem.utils.CatColorAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CatDetectionScreen(
    cameraManager: CameraManager,
    catDetector: CatDetector
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var detectionState by remember { mutableStateOf(CatDetectionState()) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // Tracking manager and state
    val trackingManager = remember { MultiCatTrackingManager() }
    var isTrackingActive by remember { mutableStateOf(false) }
    var selectedCatColor by remember { mutableStateOf<CatColorAnalyzer.CatColor?>(null) }
    var isWaitingForFirstFrame by remember { mutableStateOf(false) }
    var firstFrameForTracking by remember { mutableStateOf<Bitmap?>(null) }

    // Initialize camera when previewView is available or tracking state changes
    LaunchedEffect(previewView, isTrackingActive, isWaitingForFirstFrame) {
        android.util.Log.d("CatDetectionScreen", "LaunchedEffect triggered - previewView: ${previewView != null}, isTracking: $isTrackingActive, isWaiting: $isWaitingForFirstFrame")
        previewView?.let { view ->
            try {
                android.util.Log.d("CatDetectionScreen", "Binding camera with analysis: ${isTrackingActive || isWaitingForFirstFrame}")
                cameraManager.bindCamera(
                    lifecycleOwner = lifecycleOwner,
                    previewView = view,
                    enableAnalysis = isTrackingActive || isWaitingForFirstFrame,
                    onFrameCallback = if (isTrackingActive || isWaitingForFirstFrame) { bitmap ->
                        android.util.Log.d("CatDetectionScreen", "Frame callback invoked - isWaiting: $isWaitingForFirstFrame, firstFrame: ${firstFrameForTracking == null}, isTracking: $isTrackingActive")
                        if (isWaitingForFirstFrame && firstFrameForTracking == null) {
                            // Capture first frame for initialization
                            android.util.Log.d("CatDetectionScreen", "Captured first frame for tracking: ${bitmap.width}x${bitmap.height}")
                            firstFrameForTracking = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        } else if (isTrackingActive) {
                            // Process tracking frames
                            scope.launch(Dispatchers.Default) {
                                processTrackingFrame(
                                    bitmap = bitmap,
                                    trackingManager = trackingManager,
                                    catDetector = catDetector,
                                    currentState = detectionState,
                                    onStateUpdate = { detectionState = it }
                                )
                            }
                        }
                    } else null
                )
                android.util.Log.d("CatDetectionScreen", "Camera bound successfully")
            } catch (e: Exception) {
                android.util.Log.e("CatDetectionScreen", "Camera binding failed", e)
                detectionState = detectionState.copy(
                    errorMessage = "Camera initialization failed: ${e.message}"
                )
            }
        }
    }

    // Process first frame when it becomes available
    LaunchedEffect(firstFrameForTracking) {
        firstFrameForTracking?.let { bitmap ->
            android.util.Log.d("CatDetectionScreen", "Processing first frame for tracking initialization")

            // Run detection on first frame
            val detections = withContext(Dispatchers.Default) {
                catDetector.detectCats(bitmap)
            }

            android.util.Log.d("CatDetectionScreen", "First frame detection found ${detections.size} cats")

            if (detections.isEmpty()) {
                detectionState = CatDetectionState(
                    isProcessing = false,
                    errorMessage = "No cats detected. Please ensure a cat is visible."
                )
                isWaitingForFirstFrame = false
                firstFrameForTracking = null
                return@LaunchedEffect
            }

            // Initialize tracking with first frame
            val trackedCats = withContext(Dispatchers.Default) {
                trackingManager.initializeTracking(bitmap, detections)
            }

            if (trackedCats.isEmpty()) {
                detectionState = CatDetectionState(
                    isProcessing = false,
                    errorMessage = "No cats of the selected color detected. Try changing the color filter."
                )
                isWaitingForFirstFrame = false
                firstFrameForTracking = null
                return@LaunchedEffect
            }

            // Successfully initialized - switch to active tracking
            detectionState = CatDetectionState(
                capturedImage = null,
                detections = detections,
                hasCats = true,
                isProcessing = false,
                errorMessage = null,
                trackingMode = TrackingMode.ActiveTracking(trackedCats, 0),
                trackedCats = trackedCats
            )

            isWaitingForFirstFrame = false
            isTrackingActive = true
            firstFrameForTracking = null

            android.util.Log.d("CatDetectionScreen", "Tracking initialized successfully")
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

        // Camera preview or captured image
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isTrackingActive) {
                // Display camera preview with live tracking overlay
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            previewView = view
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Tracking overlay on top of camera preview
                if (detectionState.trackedCats.isNotEmpty()) {
                    TrackingOverlay(
                        trackedCats = detectionState.trackedCats,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else if (detectionState.capturedImage != null) {
                // Display captured image with bounding boxes (single-shot mode)
                ImageWithBoundingBoxes(
                    bitmap = detectionState.capturedImage!!,
                    detections = detectionState.detections
                )
            } else {
                // Display camera preview
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            previewView = view
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Loading indicator
            if (detectionState.isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Detection results
        DetectionResultsCard(
            state = detectionState,
            trackingColor = selectedCatColor,
            isTracking = isTrackingActive
        )

        // Cat color filter selection
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

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (!isTrackingActive && !isWaitingForFirstFrame) {
                        // Set target color on tracking manager
                        trackingManager.setTargetColor(selectedCatColor)

                        // Start waiting for first frame
                        detectionState = CatDetectionState(isProcessing = true)
                        isWaitingForFirstFrame = true
                        firstFrameForTracking = null
                        android.util.Log.d("CatDetectionScreen", "Starting tracking - waiting for first frame")
                    } else if (isTrackingActive) {
                        // Stop tracking
                        scope.launch {
                            stopTracking(
                                trackingManager = trackingManager,
                                onStateUpdate = { detectionState = it },
                                onTrackingStopped = {
                                    isTrackingActive = false
                                    isWaitingForFirstFrame = false
                                    firstFrameForTracking = null
                                }
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !detectionState.isProcessing
            ) {
                Text(if (isTrackingActive) "Stop Tracking" else "Start Tracking")
            }

            Button(
                onClick = {
                    scope.launch {
                        if (!isTrackingActive) {
                            // Single-shot mode
                            captureAndDetect(
                                cameraManager = cameraManager,
                                catDetector = catDetector,
                                onStateUpdate = { detectionState = it }
                            )
                        } else {
                            // Clear while tracking mode active
                            detectionState = CatDetectionState(
                                trackingMode = detectionState.trackingMode,
                                trackedCats = detectionState.trackedCats
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !detectionState.isProcessing
            ) {
                Text(if (isTrackingActive) "Capture" else "Clear")
            }
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
fun ImageWithBoundingBoxes(
    bitmap: Bitmap,
    detections: List<DetectionResult>
) {
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured image",
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { imageSize = it },
            contentScale = ContentScale.Fit
        )

        // Draw bounding boxes
        if (imageSize.width > 0 && imageSize.height > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Calculate the actual image display size
                val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val canvasAspectRatio = size.width / size.height

                val displayWidth: Float
                val displayHeight: Float
                val offsetX: Float
                val offsetY: Float

                if (canvasAspectRatio > imageAspectRatio) {
                    // Canvas is wider than image
                    displayHeight = size.height
                    displayWidth = displayHeight * imageAspectRatio
                    offsetX = (size.width - displayWidth) / 2
                    offsetY = 0f
                } else {
                    // Canvas is taller than image
                    displayWidth = size.width
                    displayHeight = displayWidth / imageAspectRatio
                    offsetX = 0f
                    offsetY = (size.height - displayHeight) / 2
                }

                // Draw each detection
                detections.forEach { detection ->
                    // Convert normalized coordinates to pixel coordinates
                    val left = offsetX + detection.boundingBox.left * displayWidth
                    val top = offsetY + detection.boundingBox.top * displayHeight
                    val right = offsetX + detection.boundingBox.right * displayWidth
                    val bottom = offsetY + detection.boundingBox.bottom * displayHeight

                    // Draw rectangle
                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 4f)
                    )

                    // Draw label with color
                    val colorName = CatColorAnalyzer.getColorName(detection.color)
                    val label = "$colorName Cat ${(detection.confidence * 100).toInt()}%"
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GREEN
                        textSize = 48f
                        isAntiAlias = true
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
    }
}

@Composable
fun DetectionResultsCard(state: CatDetectionState, trackingColor: CatColorAnalyzer.CatColor? = null, isTracking: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = if (isTracking) "Tracking Status" else "Detection Results",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Show tracking color filter if set
            if (trackingColor != null && isTracking) {
                Text(
                    text = "Tracking: ${CatColorAnalyzer.getColorName(trackingColor)} cats only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (isTracking && state.trackedCats.isNotEmpty()) {
                val cat = state.trackedCats.first()
                val colorName = CatColorAnalyzer.getColorName(cat.catColor)
                val statusText = if (cat.isLost) "Lost tracking" else "Tracking active"
                val statusColor = if (cat.isLost) Color.Red else Color.Green

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = statusColor
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$colorName Cat: ${(cat.confidence * 100).toInt()}% confidence",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (state.capturedImage == null && !isTracking) {
                Text(
                    text = "No image captured yet. Press 'Start Tracking' to begin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.isProcessing) {
                Text(
                    text = "Processing image...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.hasCats && !isTracking) {
                Text(
                    text = "Cat detected! Found ${state.detections.size} cat(s)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Green
                )

                Spacer(modifier = Modifier.height(8.dp))

                state.detections.forEachIndexed { index, detection ->
                    val colorName = CatColorAnalyzer.getColorName(detection.color)
                    Text(
                        text = "$colorName Cat ${index + 1}: ${(detection.confidence * 100).toInt()}% confidence",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (!isTracking) {
                Text(
                    text = "No cats detected in the image",
                    style = MaterialTheme.typography.bodyLarge,
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
            // Convert normalized coordinates to canvas coordinates
            val left = cat.boundingBox.left * size.width
            val top = cat.boundingBox.top * size.height
            val right = cat.boundingBox.right * size.width
            val bottom = cat.boundingBox.bottom * size.height

            // Choose color based on tracking state
            val color = if (cat.isLost) Color.Red else cat.displayColor
            val strokeWidth = if (cat.isLost) 6f else 4f

            // Draw bounding box
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = strokeWidth)
            )

            // Draw label
            val colorName = CatColorAnalyzer.getColorName(cat.catColor)
            val confidenceText = "${(cat.confidence * 100).toInt()}%"
            val label = if (cat.isLost) "LOST - $colorName" else "$colorName $confidenceText"

            val paint = android.graphics.Paint().apply {
                setColor(android.graphics.Color.WHITE)
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

private suspend fun startTracking(
    cameraManager: CameraManager,
    catDetector: CatDetector,
    trackingManager: MultiCatTrackingManager,
    onStateUpdate: (CatDetectionState) -> Unit,
    onTrackingStarted: () -> Unit
) {
    // Set processing state
    onStateUpdate(CatDetectionState(isProcessing = true))

    try {
        android.util.Log.d("CatDetectionScreen", "Starting tracking - capturing initial frame")

        // Capture initial frame
        val bitmap = withContext(Dispatchers.Main) {
            cameraManager.captureImage()
        }

        if (bitmap == null) {
            android.util.Log.e("CatDetectionScreen", "Failed to capture initial frame")
            onStateUpdate(CatDetectionState(
                isProcessing = false,
                errorMessage = "Failed to capture image"
            ))
            return
        }

        android.util.Log.d("CatDetectionScreen", "Captured frame: ${bitmap.width}x${bitmap.height}")

        // Run initial detection
        val detections = withContext(Dispatchers.Default) {
            catDetector.detectCats(bitmap)
        }

        android.util.Log.d("CatDetectionScreen", "Initial detection found ${detections.size} cats")

        if (detections.isEmpty()) {
            onStateUpdate(CatDetectionState(
                isProcessing = false,
                errorMessage = "No cats detected. Please ensure a cat is visible."
            ))
            return
        }

        // Initialize tracking (this will filter by color internally)
        val trackedCats = withContext(Dispatchers.Default) {
            trackingManager.initializeTracking(bitmap, detections)
        }

        if (trackedCats.isEmpty()) {
            onStateUpdate(CatDetectionState(
                isProcessing = false,
                errorMessage = "No cats of the selected color detected. Try changing the color filter."
            ))
            return
        }

        // Update state
        onStateUpdate(CatDetectionState(
            capturedImage = null,
            detections = detections,
            hasCats = true,
            isProcessing = false,
            errorMessage = null,
            trackingMode = TrackingMode.ActiveTracking(trackedCats, 0),
            trackedCats = trackedCats
        ))

        // Enable tracking
        onTrackingStarted()

    } catch (e: Exception) {
        onStateUpdate(CatDetectionState(
            isProcessing = false,
            errorMessage = "Error starting tracking: ${e.message}"
        ))
    }
}

private suspend fun stopTracking(
    trackingManager: MultiCatTrackingManager,
    onStateUpdate: (CatDetectionState) -> Unit,
    onTrackingStopped: () -> Unit
) {
    withContext(Dispatchers.Default) {
        trackingManager.clearAllTrackers()
    }

    onStateUpdate(CatDetectionState(
        trackingMode = TrackingMode.Idle,
        trackedCats = emptyList()
    ))

    onTrackingStopped()
}

private suspend fun processTrackingFrame(
    bitmap: Bitmap,
    trackingManager: MultiCatTrackingManager,
    catDetector: CatDetector,
    currentState: CatDetectionState,
    onStateUpdate: (CatDetectionState) -> Unit
) {
    try {
        val currentCats = currentState.trackedCats

        android.util.Log.d("CatDetectionScreen", "Processing tracking frame: ${bitmap.width}x${bitmap.height}, tracked cats: ${currentCats.size}")

        // Check if we should run detection
        if (trackingManager.shouldRunDetection()) {
            android.util.Log.d("CatDetectionScreen", "Running periodic detection")

            // Run detection
            val detections = catDetector.detectCats(bitmap)
            android.util.Log.d("CatDetectionScreen", "Periodic detection found ${detections.size} cats")

            // Merge with tracking
            val updatedCats = trackingManager.mergeDetectionsWithTracking(
                bitmap, detections, currentCats
            )

            android.util.Log.d("CatDetectionScreen", "After merge: ${updatedCats.size} tracked cats")

            // Update state
            withContext(Dispatchers.Main) {
                onStateUpdate(currentState.copy(
                    trackedCats = updatedCats,
                    hasCats = updatedCats.isNotEmpty()
                ))
            }
        } else {
            // Just update tracking
            val updatedCats = trackingManager.processFrame(bitmap, currentCats)

            if (updatedCats.size != currentCats.size || updatedCats.firstOrNull()?.isLost != currentCats.firstOrNull()?.isLost) {
                android.util.Log.d("CatDetectionScreen", "Tracking update: ${updatedCats.size} cats, lost: ${updatedCats.firstOrNull()?.isLost}")
            }

            // Update state
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

private suspend fun captureAndDetect(
    cameraManager: CameraManager,
    catDetector: CatDetector,
    onStateUpdate: (CatDetectionState) -> Unit
) {
    // Set processing state
    onStateUpdate(CatDetectionState(isProcessing = true))

    try {
        // Capture image
        val bitmap = withContext(Dispatchers.Main) {
            cameraManager.captureImage()
        }

        if (bitmap == null) {
            onStateUpdate(
                CatDetectionState(
                    isProcessing = false,
                    errorMessage = "Failed to capture image"
                )
            )
            return
        }

        // Run detection on background thread
        val detections = withContext(Dispatchers.Default) {
            catDetector.detectCats(bitmap)
        }

        // Update state with results
        onStateUpdate(
            CatDetectionState(
                capturedImage = bitmap,
                detections = detections,
                hasCats = detections.isNotEmpty(),
                isProcessing = false,
                errorMessage = null
            )
        )
    } catch (e: Exception) {
        onStateUpdate(
            CatDetectionState(
                isProcessing = false,
                errorMessage = "Error: ${e.message}"
            )
        )
    }
}
