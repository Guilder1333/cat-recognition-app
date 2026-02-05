package com.example.catrecognitionsystem.ml

import android.graphics.RectF
import com.example.catrecognitionsystem.tracking.TrackedCat
import com.example.catrecognitionsystem.tracking.TrackingMode
import com.example.catrecognitionsystem.utils.CatColorAnalyzer

data class DetectionResult(
    val boundingBox: RectF,      // Normalized coordinates [0, 1]
    val label: String,
    val confidence: Float,
    val color: CatColorAnalyzer.CatColor = CatColorAnalyzer.CatColor.UNKNOWN,
    val trackingId: String? = null  // Optional tracking ID for matching detections to trackers
)

data class CatDetectionState(
    val detections: List<DetectionResult> = emptyList(),
    val hasCats: Boolean = false,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val trackingMode: TrackingMode = TrackingMode.Idle,
    val trackedCats: List<TrackedCat> = emptyList(),
    val debugInfo: String? = null  // Debug info for model output inspection
)
