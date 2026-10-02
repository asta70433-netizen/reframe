package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.camera.video.VideoRecordEvent
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraService
import com.example.camera.VideoCropProcessor
import com.example.data.model.CameraLens
import com.example.data.model.FrameRate
import com.example.data.model.PreviewLayout
import com.example.data.model.RecordedVideo
import com.example.data.model.RecordingStatus
import com.example.data.model.SmartCropKeyframe
import com.example.data.model.SmartCropRect
import com.example.data.model.VideoQuality
import com.example.data.model.ZoomLevel
import com.example.data.model.ZoomOption
import com.example.data.repository.VideoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CameraUiState(
    val lens: CameraLens = CameraLens.BACK,
    val zoomLevel: ZoomLevel = ZoomLevel.STANDARD,
    val selectedZoomOption: ZoomOption = ZoomOption.ZOOM_1X,
    val isFlashOn: Boolean = false,
    val previewLayout: PreviewLayout = PreviewLayout.SPLIT,
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val recordingDurationMillis: Long = 0L,
    val isExportingTwinVideos: Boolean = false,
    val exportStepTitle: String = "Exporting Twin Videos…",
    val exportProgressText: String = "Creating portrait & landscape versions",
    val isSavedToastVisible: Boolean = false,
    val savedToastMessage: String = "Both videos saved to Photos",
    val isToastError: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val isGalleryOpen: Boolean = false,
    val selectedVideoForPlayback: RecordedVideo? = null,
    val recordedVideos: List<RecordedVideo> = emptyList(),
    val hasCameraPermission: Boolean = false,
    val hasAudioPermission: Boolean = false,
    val focusPosition: Offset? = null,
    // Smart Crop State
    val isSmartCropEnabled: Boolean = true,
    val isSmartCropAnalyzing: Boolean = false,
    val smartCropSubject: String = "AI Smart Crop Active",
    val smartCropBoundingBox: SmartCropRect? = null,
    val smartCropFocus: Offset = Offset(0.5f, 0.45f),
    // Settings parameters
    val defaultCamera: CameraLens = CameraLens.BACK,
    val defaultPreview: String = "Portrait First (9:16)",
    val quality: VideoQuality = VideoQuality.FOUR_K,
    val frameRate: FrameRate = FrameRate.FPS_30,
    val autoSaveToPhotos: Boolean = true
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository(application.applicationContext)
    val cameraService = CameraService(application.applicationContext)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState = _uiState.asStateFlow()

    private var durationTimerJob: Job? = null
    private var recordingStartTime = 0L
    private var currentRecordingTimestamp = ""
    private var activeRecordingKeyframes: List<SmartCropKeyframe>? = null

    init {
        loadVideos()
        viewModelScope.launch {
            cameraService.smartCropTracker.status.collect { status ->
                _uiState.update {
                    it.copy(
                        isSmartCropEnabled = status.isEnabled,
                        isSmartCropAnalyzing = status.isAnalyzing,
                        smartCropSubject = status.detectedSubject,
                        smartCropBoundingBox = status.boundingBox,
                        smartCropFocus = Offset(status.currentFocusX, status.currentFocusY)
                    )
                }
            }
        }
    }

    fun toggleSmartCrop() {
        val next = !_uiState.value.isSmartCropEnabled
        cameraService.smartCropTracker.setEnabled(next)
    }

    fun setSmartCropEnabled(enabled: Boolean) {
        cameraService.smartCropTracker.setEnabled(enabled)
    }

    fun setPermissions(camera: Boolean, audio: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = camera, hasAudioPermission = audio) }
        if (camera) loadVideos()
    }

    fun loadVideos() {
        viewModelScope.launch {
            val list = repository.getRecordedVideos()
            _uiState.update { it.copy(recordedVideos = list) }
        }
    }

    fun toggleCameraLens() {
        if (_uiState.value.recordingStatus is RecordingStatus.Recording) return
        val newLens = _uiState.value.lens.toggle()
        _uiState.update { it.copy(lens = newLens) }
    }

    fun selectZoom(option: ZoomOption) {
        val nextLevel = option.toZoomLevel()
        _uiState.update {
            it.copy(
                selectedZoomOption = option,
                zoomLevel = nextLevel
            )
        }
        cameraService.setZoomRatio(option.ratio)
    }

    fun toggleZoom() {
        val current = _uiState.value.selectedZoomOption
        val next = when (current) {
            ZoomOption.ZOOM_0_5X -> ZoomOption.ZOOM_1X
            ZoomOption.ZOOM_1X -> ZoomOption.ZOOM_1_5X
            ZoomOption.ZOOM_1_5X -> ZoomOption.ZOOM_2X
            ZoomOption.ZOOM_2X -> ZoomOption.ZOOM_2_5X
            ZoomOption.ZOOM_2_5X -> ZoomOption.ZOOM_3X
            ZoomOption.ZOOM_3X -> ZoomOption.ZOOM_0_5X
        }
        selectZoom(next)
    }

    fun toggleFlash() {
        val newState = cameraService.toggleFlash()
        _uiState.update { it.copy(isFlashOn = newState) }
    }

    fun cyclePreviewLayout() {
        val next = _uiState.value.previewLayout.next()
        _uiState.update { it.copy(previewLayout = next) }
    }

    fun toggleSettings() {
        _uiState.update { it.copy(isSettingsOpen = !it.isSettingsOpen) }
    }

    fun setQuality(q: VideoQuality) {
        _uiState.update { it.copy(quality = q) }
    }

    fun setFrameRate(fps: FrameRate) {
        _uiState.update { it.copy(frameRate = fps) }
    }

    fun setAutoSave(enabled: Boolean) {
        _uiState.update { it.copy(autoSaveToPhotos = enabled) }
    }

    fun triggerFocus(x: Float, y: Float, width: Float, height: Float) {
        _uiState.update { it.copy(focusPosition = Offset(x, y)) }
        cameraService.triggerFocus(x, y, width, height)
        viewModelScope.launch {
            delay(1200)
            _uiState.update { it.copy(focusPosition = null) }
        }
    }

    fun onShutterClicked() {
        when (_uiState.value.recordingStatus) {
            is RecordingStatus.Idle -> {
                startRecording()
            }
            is RecordingStatus.Recording -> {
                stopRecordingAndExport()
            }
            is RecordingStatus.Exporting -> {
                // Ignore while exporting
            }
        }
    }

    private fun startRecording() {
        recordingStartTime = SystemClock.elapsedRealtime()
        currentRecordingTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val startResult = cameraService.startRecording(
            hasAudioPermission = _uiState.value.hasAudioPermission,
            timestamp = currentRecordingTimestamp,
            autoSave = _uiState.value.autoSaveToPhotos
        ) { event ->
            when (event) {
                is VideoRecordEvent.Status -> {
                    val dur = event.recordingStats.recordedDurationNanos / 1_000_000
                    _uiState.update { it.copy(recordingDurationMillis = dur) }
                }
                is VideoRecordEvent.Finalize -> {
                    onRecordingFinalized(event, currentRecordingTimestamp)
                }
            }
        }

        if (startResult.isFailure) {
            val error = startResult.exceptionOrNull()
            val errorMessage = when (error) {
                is SecurityException -> error.localizedMessage ?: "Permission error: Camera or Audio access denied"
                is java.io.IOException -> "Storage error: ${error.localizedMessage ?: error.message}"
                is IllegalStateException -> error.localizedMessage ?: "Camera session not ready"
                else -> "Failed to start recording: ${error?.localizedMessage ?: error?.message ?: "Unknown error"}"
            }
            _uiState.update {
                it.copy(
                    recordingStatus = RecordingStatus.Idle,
                    recordingDurationMillis = 0L,
                    isExportingTwinVideos = false,
                    isSavedToastVisible = true,
                    savedToastMessage = errorMessage,
                    isToastError = true
                )
            }
            viewModelScope.launch {
                delay(4000)
                _uiState.update { it.copy(isSavedToastVisible = false) }
            }
            return
        }

        _uiState.update {
            it.copy(
                recordingStatus = RecordingStatus.Recording(0L),
                recordingDurationMillis = 0L
            )
        }

        durationTimerJob?.cancel()
        durationTimerJob = viewModelScope.launch {
            while (true) {
                delay(200)
                if (_uiState.value.recordingStatus is RecordingStatus.Recording) {
                    val elapsed = SystemClock.elapsedRealtime() - recordingStartTime
                    _uiState.update { it.copy(recordingDurationMillis = elapsed) }
                }
            }
        }
    }

    private fun stopRecordingAndExport() {
        durationTimerJob?.cancel()
        durationTimerJob = null

        activeRecordingKeyframes = cameraService.smartCropTracker.stopRecording()

        // Show "Exporting Twin Videos... Creating portrait & landscape versions" dialog modal
        _uiState.update {
            it.copy(
                recordingStatus = RecordingStatus.Exporting,
                isExportingTwinVideos = true,
                exportStepTitle = "Exporting Twin Videos…",
                exportProgressText = "Finalizing 9:16 portrait stream…"
            )
        }

        cameraService.stopRecording()
    }

    private fun formatRecordingErrorMessage(event: VideoRecordEvent.Finalize): String {
        val causeMsg = event.cause?.localizedMessage ?: event.cause?.message
        return when (event.error) {
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
                "Insufficient storage space to complete video recording"
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ->
                "Recording stopped: maximum video file size limit reached"
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
                "Camera source became inactive or disconnected during recording"
            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS ->
                if (causeMsg != null) "Storage error: $causeMsg" else "Invalid recording storage destination"
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ->
                if (causeMsg != null) "Hardware video encoder error: $causeMsg" else "Hardware video encoder failed"
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ->
                if (causeMsg != null) "Camera recorder session error: $causeMsg" else "Camera recorder encountered an internal error"
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ->
                "Recording failed: No valid video data was received from camera"
            else ->
                if (causeMsg != null) "Recording failed: $causeMsg" else "Recording failed (Error code ${event.error})"
        }
    }

    private fun onRecordingFinalized(event: VideoRecordEvent.Finalize, timestamp: String) {
        viewModelScope.launch {
            if (event.hasError()) {
                val errorMsg = formatRecordingErrorMessage(event)
                _uiState.update {
                    it.copy(
                        recordingStatus = RecordingStatus.Idle,
                        recordingDurationMillis = 0L,
                        isExportingTwinVideos = false,
                        isSavedToastVisible = true,
                        savedToastMessage = errorMsg,
                        isToastError = true
                    )
                }
                delay(4000)
                _uiState.update { it.copy(isSavedToastVisible = false) }
                return@launch
            }

            val sourceUri = event.outputResults.outputUri
            val output16x9Name = "ReFrame_${timestamp}_16x9.mp4"

            _uiState.update {
                it.copy(
                    exportStepTitle = "Encoding 16:9 Video…",
                    exportProgressText = "Hardware transcoding synchronized landscape version…"
                )
            }

            val isAutoSave = _uiState.value.autoSaveToPhotos
            val cropResult = VideoCropProcessor.cropAndSave16x9(
                context = getApplication(),
                sourceUri = sourceUri,
                outputFileName = output16x9Name,
                preferredFps = _uiState.value.frameRate.fps,
                autoSave = isAutoSave,
                keyframes = activeRecordingKeyframes,
                onProgress = { stepText ->
                    _uiState.update { it.copy(exportProgressText = stepText) }
                }
            )
            activeRecordingKeyframes = null

            loadVideos()

            if (cropResult.isSuccess) {
                _uiState.update {
                    it.copy(
                        recordingStatus = RecordingStatus.Idle,
                        recordingDurationMillis = 0L,
                        isExportingTwinVideos = false,
                        isSavedToastVisible = true,
                        savedToastMessage = if (isAutoSave) "Both videos saved to Photos" else "Twin videos recorded",
                        isToastError = false
                    )
                }
            } else {
                val failureReason = cropResult.exceptionOrNull()?.localizedMessage
                    ?: cropResult.exceptionOrNull()?.message
                    ?: "Transcode failed"
                _uiState.update {
                    it.copy(
                        recordingStatus = RecordingStatus.Idle,
                        recordingDurationMillis = 0L,
                        isExportingTwinVideos = false,
                        isSavedToastVisible = true,
                        savedToastMessage = "9:16 saved, but 16:9 failed: $failureReason",
                        isToastError = true
                    )
                }
            }

            delay(4000)
            _uiState.update { it.copy(isSavedToastVisible = false) }
        }
    }

    fun openGallery() {
        loadVideos()
        _uiState.update { it.copy(isGalleryOpen = true) }
    }

    fun closeGallery() {
        _uiState.update { it.copy(isGalleryOpen = false) }
    }

    fun selectVideoForPlayback(video: RecordedVideo?) {
        _uiState.update { it.copy(selectedVideoForPlayback = video) }
    }

    fun deleteVideo(video: RecordedVideo) {
        viewModelScope.launch {
            val deleted = repository.deleteVideo(video)
            if (deleted) {
                if (_uiState.value.selectedVideoForPlayback?.id == video.id) {
                    _uiState.update { it.copy(selectedVideoForPlayback = null) }
                }
                loadVideos()
            }
        }
    }

    fun deleteSessionPair(sessionId: String) {
        viewModelScope.launch {
            val deleted = repository.deleteSessionPair(sessionId, _uiState.value.recordedVideos)
            if (deleted) {
                if (_uiState.value.selectedVideoForPlayback?.sessionId == sessionId) {
                    _uiState.update { it.copy(selectedVideoForPlayback = null) }
                }
                loadVideos()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        durationTimerJob?.cancel()
        cameraService.unbind()
    }
}
