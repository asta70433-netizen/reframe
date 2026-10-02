package com.example.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.data.model.CameraLens
import com.example.data.model.FrameRate
import com.example.data.model.VideoQuality
import com.example.data.model.ZoomLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class CameraService(private val context: Context) {

    private val tag = "CameraService"

    val smartCropTracker = SmartCropTracker()
    val geminiSmartCropService = GeminiSmartCropService()
    val dualRenderer = DualCameraPreviewRenderer().apply {
        smartCropTracker = this@CameraService.smartCropTracker
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analysisExecutor: Executor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private val _isFlashOn = MutableStateFlow(false)
    val isFlashOn = _isFlashOn.asStateFlow()

    private var currentZoomRatio: Float = 1.0f
    fun getZoomRatio(): Float = currentZoomRatio

    private var orientationEventListener: OrientationEventListener? = null
    private var currentDeviceRotation: Int = Surface.ROTATION_0

    init {
        try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE)
            if (sensorManager != null) {
                orientationEventListener = object : OrientationEventListener(context) {
                    override fun onOrientationChanged(orientation: Int) {
                        if (orientation == ORIENTATION_UNKNOWN) return
                        val rotation = when (orientation) {
                            in 45 until 135 -> Surface.ROTATION_270
                            in 135 until 225 -> Surface.ROTATION_180
                            in 225 until 315 -> Surface.ROTATION_90
                            else -> Surface.ROTATION_0
                        }
                        if (rotation != currentDeviceRotation) {
                            currentDeviceRotation = rotation
                            videoCapture?.targetRotation = rotation
                        }
                    }
                }
                if (orientationEventListener?.canDetectOrientation() == true) {
                    orientationEventListener?.enable()
                }
            }
        } catch (e: Throwable) {
            Log.w(tag, "Could not initialize OrientationEventListener", e)
        }
    }

    fun getDeviceRotation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        @Suppress("DEPRECATION")
        val displayRotation = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        return if (currentDeviceRotation != Surface.ROTATION_0) currentDeviceRotation else displayRotation
    }

    fun updateVideoCaptureRotation() {
        val rotation = getDeviceRotation()
        videoCapture?.targetRotation = rotation
    }

    suspend fun initializeProvider(): ProcessCameraProvider {
        cameraProvider?.let { return it }
        val provider = ProcessCameraProvider.getInstance(context).get()
        cameraProvider = provider
        return provider
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        lens: CameraLens,
        zoomLevel: ZoomLevel,
        quality: VideoQuality = VideoQuality.TEN_EIGHTY,
        frameRate: FrameRate = FrameRate.FPS_30,
        onCameraBound: () -> Unit = {}
    ) {
        val provider = cameraProvider ?: return

        try {
            provider.unbindAll()

            val cameraSelector = if (lens == CameraLens.BACK) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            if (!provider.hasCamera(cameraSelector)) {
                Log.w(tag, "Selected camera not available on device")
                return
            }

            val previewBuilder = Preview.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                .setTargetRotation(android.view.Surface.ROTATION_0)

            val camInfo = try {
                provider.getCameraInfo(cameraSelector)
            } catch (e: Exception) {
                null
            }

            // Build supported quality list
            val preferredQualities = when (quality) {
                VideoQuality.FOUR_K -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                VideoQuality.TWO_K -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                VideoQuality.TEN_EIGHTY -> listOf(Quality.FHD, Quality.HD, Quality.SD)
            }

            val qualitySelector = if (camInfo != null) {
                val capabilities = Recorder.getVideoCapabilities(camInfo)
                val supported = capabilities.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
                val targetQuality = preferredQualities.firstOrNull { capabilities.isQualitySupported(it, androidx.camera.core.DynamicRange.SDR) }
                    ?: supported.firstOrNull()
                    ?: Quality.SD
                QualitySelector.from(targetQuality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            } else {
                QualitySelector.fromOrderedList(
                    preferredQualities,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setExecutor(mainExecutor)
                .build()

            val videoCaptureBuilder = VideoCapture.Builder(recorder)
                .setTargetRotation(getDeviceRotation())

            // Determine and apply best supported FPS range
            var selectedFpsRange: Range<Int>? = null
            try {
                val camInfo = provider.getCameraInfo(cameraSelector)
                val availableRanges = Camera2CameraInfo.from(camInfo)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                selectedFpsRange = selectBestFpsRange(availableRanges, frameRate.fps)
            } catch (e: Exception) {
                Log.w(tag, "Could not query available FPS ranges", e)
                selectedFpsRange = selectBestFpsRange(null, frameRate.fps)
            }

            if (selectedFpsRange != null) {
                try {
                    Camera2Interop.Extender(previewBuilder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            selectedFpsRange
                        )
                    Camera2Interop.Extender(videoCaptureBuilder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            selectedFpsRange
                        )
                } catch (e: Exception) {
                    Log.w(tag, "Camera2Interop FPS configuration failed", e)
                }
            }

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(dualRenderer.surfaceProvider)
            }
            videoCapture = videoCaptureBuilder.build()

            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

            val analysis = analysisBuilder.build().also {
                it.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        if (smartCropTracker.status.value.isEnabled) {
                            val bitmap = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            serviceScope.launch {
                                smartCropTracker.setAnalyzing(true)
                                val result = geminiSmartCropService.analyzeFrameForSmartCrop(bitmap, rotation)
                                if (result != null) {
                                    smartCropTracker.onNewAnalysisResult(result)
                                } else {
                                    smartCropTracker.setAnalyzing(false)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Image analyzer frame read error", e)
                        smartCropTracker.setAnalyzing(false)
                    } finally {
                        imageProxy.close()
                    }
                }
            }
            imageAnalysis = analysis

            camera = try {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    analysis,
                    videoCapture
                )
            } catch (e: Exception) {
                Log.w(tag, "Concurrent 3-use-case binding failed, falling back to preview + videoCapture", e)
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )
            }

            if (selectedFpsRange != null) {
                try {
                    val camera2Control = Camera2CameraControl.from(camera!!.cameraControl)
                    val captureRequestOptions = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            selectedFpsRange
                        )
                        .build()
                    camera2Control.setCaptureRequestOptions(captureRequestOptions)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to apply Camera2Control FPS options", e)
                }
            }

            applyCurrentZoom()
            applyFlash(_isFlashOn.value)

            onCameraBound()
        } catch (e: Exception) {
            Log.e(tag, "Use case binding failed", e)
        }
    }

    fun toggleFlash(): Boolean {
        val target = !_isFlashOn.value
        applyFlash(target)
        return _isFlashOn.value
    }

    fun applyFlash(enable: Boolean) {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            cam.cameraControl.enableTorch(enable)
            _isFlashOn.value = enable
        }
    }

    fun setZoomRatio(ratio: Float) {
        currentZoomRatio = ratio
        applyCurrentZoom()
    }

    fun applyCurrentZoom() {
        val cam = camera ?: return
        try {
            val zoomState = cam.cameraInfo.zoomState.value
            val minRatio = zoomState?.minZoomRatio ?: 0.5f
            val maxRatio = zoomState?.maxZoomRatio ?: 10.0f
            val clampedRatio = currentZoomRatio.coerceIn(minRatio, maxRatio)
            cam.cameraControl.setZoomRatio(clampedRatio)
        } catch (e: Exception) {
            Log.w(tag, "Failed to set camera zoom ratio: $currentZoomRatio", e)
            try {
                val linearFactor = when {
                    currentZoomRatio <= 0.5f -> 0.0f
                    currentZoomRatio <= 1.0f -> 0.2f
                    currentZoomRatio <= 1.5f -> 0.4f
                    currentZoomRatio <= 2.0f -> 0.6f
                    currentZoomRatio <= 2.5f -> 0.8f
                    else -> 1.0f
                }
                cam.cameraControl.setLinearZoom(linearFactor)
            } catch (fallbackEx: Exception) {
                Log.w(tag, "Fallback linear zoom also failed", fallbackEx)
            }
        }
    }

    fun setZoomLevel(zoomLevel: ZoomLevel) {
        val ratio = when (zoomLevel) {
            ZoomLevel.WIDE -> 0.5f
            ZoomLevel.STANDARD -> 1.0f
        }
        setZoomRatio(ratio)
    }

    fun triggerFocus(x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
        val cam = camera ?: return
        try {
            val factory = SurfaceOrientedMeteringPointFactory(viewWidth, viewHeight)
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(tag, "Focus action failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        hasAudioPermission: Boolean,
        timestamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()),
        autoSave: Boolean = true,
        onEvent: (VideoRecordEvent) -> Unit
    ): Result<Unit> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val err = SecurityException("Camera permission is required to record video")
            Log.e(tag, "Recording failed", err)
            return Result.failure(err)
        }

        val capture = videoCapture ?: run {
            val err = IllegalStateException("Camera is initializing. Please wait a moment and try again.")
            Log.e(tag, "VideoCapture not ready", err)
            return Result.failure(err)
        }

        updateVideoCaptureRotation()

        if (activeRecording != null) {
            return Result.failure(IllegalStateException("Recording is already in progress"))
        }

        val name = "ReFrame_${timestamp}_9x16.mp4"

        val pending = try {
            if (autoSave && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ReFrame")
                }

                val mediaStoreOutput = MediaStoreOutputOptions.Builder(
                    context.contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).setContentValues(contentValues).build()

                capture.output.prepareRecording(context, mediaStoreOutput)
            } else {
                val moviesDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir, "ReFrame")
                if (!moviesDir.exists() && !moviesDir.mkdirs()) {
                    return Result.failure(java.io.IOException("Failed to create storage directory for video recording"))
                }
                val videoFile = File(moviesDir, name)
                val fileOutput = FileOutputOptions.Builder(videoFile).build()

                capture.output.prepareRecording(context, fileOutput)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to prepare video output", e)
            return Result.failure(e)
        }

        val canRecordAudio = hasAudioPermission && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val recordingConfig = if (canRecordAudio) {
            try {
                pending.withAudioEnabled()
            } catch (e: SecurityException) {
                Log.w(tag, "Audio permission revoked or denied at start, continuing video-only", e)
                pending
            }
        } else {
            pending
        }

        return try {
            activeRecording = recordingConfig.start(mainExecutor) { recordEvent ->
                if (recordEvent is VideoRecordEvent.Finalize) {
                    activeRecording = null
                }
                onEvent(recordEvent)
            }
            smartCropTracker.startRecording()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start active video recording session", e)
            activeRecording = null
            Result.failure(e)
        }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun unbind() {
        try {
            orientationEventListener?.disable()
            stopRecording()
            cameraProvider?.unbindAll()
            camera = null
            dualRenderer.release()
        } catch (e: Exception) {
            Log.e(tag, "Error unbinding cameras", e)
        }
    }

    companion object {
        fun selectBestFpsRange(availableRanges: Array<Range<Int>>?, targetFps: Int): Range<Int> {
            if (availableRanges.isNullOrEmpty()) {
                return Range(targetFps, targetFps)
            }

            // 1. Look for exact fixed range [targetFps, targetFps]
            val exactFixed = availableRanges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
            if (exactFixed != null) return exactFixed

            // 2. Look for range where upper == targetFps
            val matchingUpper = availableRanges.filter { it.upper == targetFps }
                .maxByOrNull { it.lower }
            if (matchingUpper != null) return matchingUpper

            // 3. If targetFps is 60 or higher, find the range with the maximum upper bound
            if (targetFps >= 60) {
                val maxUpper = availableRanges.maxByOrNull { it.upper }
                if (maxUpper != null && maxUpper.upper >= 30) return maxUpper
            }

            // 4. If targetFps is 24, look for ranges covering 24
            if (targetFps <= 24) {
                val closeRange = availableRanges.filter { it.lower <= 24 && it.upper >= 24 }
                    .minByOrNull { Math.abs(it.upper - 24) }
                if (closeRange != null) return closeRange
            }

            // 5. Otherwise find range whose upper bound is closest to targetFps
            return availableRanges.minByOrNull { Math.abs(it.upper - targetFps) } ?: availableRanges[0]
        }
    }
}
