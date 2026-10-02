package com.example.data.model

import android.graphics.Bitmap
import android.net.Uri

enum class CameraLens {
    BACK,
    FRONT;

    fun toggle(): CameraLens = if (this == BACK) FRONT else BACK
}

enum class ZoomLevel(val label: String, val factor: Float) {
    WIDE("0.5", 0.0f),
    STANDARD("1", 0.25f);

    fun toggle(): ZoomLevel = if (this == WIDE) STANDARD else WIDE
}

enum class ZoomOption(val label: String, val ratio: Float) {
    ZOOM_0_5X("0.5x", 0.5f),
    ZOOM_1X("1x", 1.0f),
    ZOOM_1_5X("1.5x", 1.5f),
    ZOOM_2X("2x", 2.0f),
    ZOOM_2_5X("2.5x", 2.5f),
    ZOOM_3X("3x", 3.0f);

    fun toZoomLevel(): ZoomLevel = if (this == ZOOM_0_5X) ZoomLevel.WIDE else ZoomLevel.STANDARD

    companion object {
        val DEFAULT = ZOOM_1X

        fun fromRatio(ratio: Float): ZoomOption {
            return entries.minByOrNull { kotlin.math.abs(it.ratio - ratio) } ?: ZOOM_1X
        }
    }
}

enum class PreviewLayout {
    SPLIT,
    PORTRAIT_FOCUS,
    LANDSCAPE_FOCUS;

    fun next(): PreviewLayout {
        val values = entries.toTypedArray()
        return values[(ordinal + 1) % values.size]
    }
}

enum class VideoQuality(val title: String) {
    FOUR_K("4K (Full Resolution)"),
    TWO_K("2K (1440p)"),
    TEN_EIGHTY("1080p")
}

enum class FrameRate(val fps: Int) {
    FPS_24(24),
    FPS_30(30),
    FPS_60(60)
}

sealed class RecordingStatus {
    data object Idle : RecordingStatus()
    data class Recording(val durationMillis: Long) : RecordingStatus()
    data object Exporting : RecordingStatus()
}

data class RecordedVideo(
    val id: Long,
    val uri: Uri,
    val filePath: String,
    val name: String,
    val durationMillis: Long,
    val sizeBytes: Long,
    val dateAdded: Long,
    val isLandscape: Boolean = false,
    val sessionId: String = "",
    val aspectRatioLabel: String = if (isLandscape) "16:9" else "9:16",
    val resolution: String = "",
    val thumbnailBitmap: Bitmap? = null
)
