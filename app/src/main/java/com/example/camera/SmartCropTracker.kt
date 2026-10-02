package com.example.camera

import android.os.SystemClock
import com.example.data.model.SmartCropAnalysisResult
import com.example.data.model.SmartCropKeyframe
import com.example.data.model.SmartCropRect
import com.example.data.model.SmartCropStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

class SmartCropTracker {

    private val _status = MutableStateFlow(SmartCropStatus())
    val status = _status.asStateFlow()

    private var targetFocusX = 0.5f
    private var targetFocusY = 0.45f
    private var currentFocusX = 0.5f
    private var currentFocusY = 0.45f

    private var targetBox: SmartCropRect? = null
    private var currentBox: SmartCropRect? = null

    private var isRecordingActive = false
    private var recordingStartTimestampUs = 0L
    private val recordedKeyframes = CopyOnWriteArrayList<SmartCropKeyframe>()

    // Smoothing factor (0.05f = very gentle cinematic drift, 0.15f = responsive follow)
    private val smoothingFactor = 0.12f

    fun setEnabled(enabled: Boolean) {
        _status.update {
            it.copy(
                isEnabled = enabled,
                detectedSubject = if (enabled) "Smart Crop Active" else "Manual Center Crop"
            )
        }
        if (!enabled) {
            targetFocusX = 0.5f
            targetFocusY = 0.5f
        }
    }

    fun setAnalyzing(analyzing: Boolean) {
        _status.update { it.copy(isAnalyzing = analyzing) }
    }

    fun onNewAnalysisResult(result: SmartCropAnalysisResult) {
        if (!_status.value.isEnabled) return

        targetFocusX = result.focusX.coerceIn(0.1f, 0.9f)
        targetFocusY = result.focusY.coerceIn(0.15f, 0.85f)
        targetBox = result.boundingBox

        val subjectLabel = when {
            result.detectedCount > 1 -> "${result.detectedCount} people tracked"
            result.detectedSubject.isNotBlank() -> result.detectedSubject
            else -> "Subject auto-framed"
        }

        _status.update {
            it.copy(
                isAnalyzing = false,
                detectedSubject = subjectLabel
            )
        }
    }

    /**
     * Called on each render/preview frame tick to advance smooth continuous interpolation.
     */
    fun tickSmoothMovement(frameTimestampUs: Long = SystemClock.elapsedRealtimeNanos() / 1000): Pair<Float, Float> {
        if (_status.value.isEnabled) {
            currentFocusX += (targetFocusX - currentFocusX) * smoothingFactor
            currentFocusY += (targetFocusY - currentFocusY) * smoothingFactor

            targetBox?.let { target ->
                val prev = currentBox ?: target
                currentBox = SmartCropRect(
                    left = prev.left + (target.left - prev.left) * smoothingFactor,
                    top = prev.top + (target.top - prev.top) * smoothingFactor,
                    right = prev.right + (target.right - prev.right) * smoothingFactor,
                    bottom = prev.bottom + (target.bottom - prev.bottom) * smoothingFactor
                )
            }
        } else {
            // Smoothly ease back to exact center (0.5, 0.5)
            currentFocusX += (0.5f - currentFocusX) * smoothingFactor
            currentFocusY += (0.5f - currentFocusY) * smoothingFactor
            currentBox = null
        }

        _status.update {
            it.copy(
                currentFocusX = currentFocusX,
                currentFocusY = currentFocusY,
                boundingBox = currentBox
            )
        }

        if (isRecordingActive) {
            val relativeTimeUs = frameTimestampUs - recordingStartTimestampUs
            recordedKeyframes.add(
                SmartCropKeyframe(
                    timestampUs = relativeTimeUs,
                    focusX = currentFocusX,
                    focusY = currentFocusY
                )
            )
        }

        return Pair(currentFocusX, currentFocusY)
    }

    fun startRecording(startTimestampUs: Long = SystemClock.elapsedRealtimeNanos() / 1000) {
        recordedKeyframes.clear()
        recordingStartTimestampUs = startTimestampUs
        isRecordingActive = true
        // Add initial keyframe
        recordedKeyframes.add(
            SmartCropKeyframe(
                timestampUs = 0L,
                focusX = currentFocusX,
                focusY = currentFocusY
            )
        )
    }

    fun stopRecording(): List<SmartCropKeyframe> {
        isRecordingActive = false
        return ArrayList(recordedKeyframes)
    }

    fun getKeyframes(): List<SmartCropKeyframe> = ArrayList(recordedKeyframes)

    fun getCurrentFocus(): Pair<Float, Float> = Pair(currentFocusX, currentFocusY)
}
