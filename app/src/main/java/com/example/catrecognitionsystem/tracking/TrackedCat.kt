package com.example.catrecognitionsystem.tracking

import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import com.example.catrecognitionsystem.utils.CatColorAnalyzer
import java.util.UUID

/**
 * Represents a cat being tracked in the video stream
 *
 * @param id Unique identifier for this tracked cat
 * @param boundingBox Current position in normalized coordinates [0,1]
 * @param confidence Tracking confidence score (0.0 to 1.0)
 * @param catColor Detected color of the cat
 * @param displayColor UI color for drawing this cat's bounding box
 * @param lastSeenTimestamp When this cat was last successfully tracked
 * @param framesSinceDetection Frames elapsed since last detection verification
 * @param isLost Whether tracking has been lost for this cat
 * @param label Object class label (typically "cat")
 */
data class TrackedCat(
    val id: String = UUID.randomUUID().toString(),
    val boundingBox: RectF,
    val confidence: Float,
    val catColor: CatColorAnalyzer.CatColor,
    val displayColor: Color,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val framesSinceDetection: Int = 0,
    val isLost: Boolean = false,
    val label: String = "cat"
)

/**
 * Provides unique colors for each tracked cat's bounding box
 */
object CatDisplayColors {
    private val colors = listOf(
        Color.Green,
        Color.Cyan,
        Color.Yellow,
        Color.Magenta,
        Color(0xFFFF6B35) // Orange
    )

    /**
     * Returns a unique color for the given cat index
     * Colors wrap around after 5 cats
     */
    fun getColorForIndex(index: Int): Color {
        return colors[index % colors.size]
    }
}
