package com.example.data.model

data class SmartCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

data class SmartCropAnalysisResult(
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val boundingBox: SmartCropRect? = null,
    val detectedSubject: String = "Subject centered",
    val detectedCount: Int = 1,
    val confidence: Float = 0.9f,
    val timestampMs: Long = System.currentTimeMillis()
)

data class SmartCropKeyframe(
    val timestampUs: Long,
    val focusX: Float,
    val focusY: Float
)

data class SmartCropStatus(
    val isEnabled: Boolean = true,
    val isAnalyzing: Boolean = false,
    val currentFocusX: Float = 0.5f,
    val currentFocusY: Float = 0.5f,
    val detectedSubject: String = "AI Smart Crop Active",
    val boundingBox: SmartCropRect? = null
)
