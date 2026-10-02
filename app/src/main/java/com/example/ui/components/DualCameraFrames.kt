package com.example.ui.components

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.example.camera.CameraService
import com.example.data.model.CameraLens
import com.example.data.model.FrameRate
import com.example.data.model.PreviewLayout
import com.example.data.model.RecordingStatus
import com.example.data.model.VideoQuality
import com.example.data.model.ZoomLevel
import com.example.data.model.ZoomOption
import java.util.Locale

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import com.example.data.model.SmartCropRect

@Composable
fun DualCameraFrames(
    cameraService: CameraService,
    lifecycleOwner: LifecycleOwner,
    lens: CameraLens,
    zoomLevel: ZoomLevel = ZoomLevel.STANDARD,
    selectedZoomOption: ZoomOption = ZoomOption.ZOOM_1X,
    quality: VideoQuality = VideoQuality.TEN_EIGHTY,
    frameRate: FrameRate = FrameRate.FPS_30,
    previewLayout: PreviewLayout,
    recordingStatus: RecordingStatus,
    recordingDurationMillis: Long,
    focusPosition: Offset?,
    isSmartCropEnabled: Boolean = true,
    isSmartCropAnalyzing: Boolean = false,
    smartCropSubject: String = "AI Smart Crop Active",
    smartCropBoundingBox: SmartCropRect? = null,
    onToggleSmartCrop: (() -> Unit)? = null,
    onFocusRequested: (x: Float, y: Float, width: Float, height: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val portraitTextureView = remember {
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    cameraService.dualRenderer.setPortraitTarget(st, width, height)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    cameraService.dualRenderer.setPortraitTarget(st, width, height)
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    cameraService.dualRenderer.setPortraitTarget(null, 0, 0)
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    val landscapeTextureView = remember {
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    cameraService.dualRenderer.setLandscapeTarget(st, width, height)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    cameraService.dualRenderer.setLandscapeTarget(st, width, height)
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    cameraService.dualRenderer.setLandscapeTarget(null, 0, 0)
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    LaunchedEffect(lens, quality, frameRate) {
        cameraService.initializeProvider()
        cameraService.bindCameraUseCases(
            lifecycleOwner = lifecycleOwner,
            lens = lens,
            zoomLevel = zoomLevel,
            quality = quality,
            frameRate = frameRate
        )
    }

    val isRecording = recordingStatus is RecordingStatus.Recording

    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val recAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_blink"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // TOP FRAME: 9:16 Portrait Box
        val topWeight = when (previewLayout) {
            PreviewLayout.SPLIT -> 1.35f
            PreviewLayout.PORTRAIT_FOCUS -> 2.0f
            PreviewLayout.LANDSCAPE_FOCUS -> 0.8f
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .weight(topWeight)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1E1E1E))
                .border(
                    width = if (isRecording) 2.5.dp else 1.dp,
                    color = if (isRecording) Color(0xFFEF5350) else Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(22.dp)
                )
                .testTag("portrait_frame_9_16")
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onFocusRequested(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                    }
                }
        ) {
            // Live camera view (9:16 Portrait)
            AndroidView(
                factory = { portraitTextureView },
                modifier = Modifier.fillMaxSize()
            )

            // Top-left: Active recording pill "[ 🔴 00:01 ]" (exact match to video 00:00 - 00:04)
            if (isRecording) {
                val totalSec = recordingDurationMillis / 1000
                val min = totalSec / 60
                val sec = totalSec % 60
                val timeStr = String.format(Locale.US, "%02d:%02d", min, sec)

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 12.dp, start = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .alpha(recAlpha)
                                .background(Color(0xFFE53935), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = timeStr,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Top-right: "9:16" Pill badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "9:16",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Focus reticle
            focusPosition?.let { pos ->
                FocusIndicator(focusPosition = pos)
            }
        }

        // BOTTOM FRAME: 16:9 Landscape Box
        val bottomWeight = when (previewLayout) {
            PreviewLayout.SPLIT -> 1.0f
            PreviewLayout.PORTRAIT_FOCUS -> 0.7f
            PreviewLayout.LANDSCAPE_FOCUS -> 1.8f
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .weight(bottomWeight)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1E1E1E))
                .border(
                    width = if (isRecording) 2.5.dp else 1.dp,
                    color = if (isRecording) Color(0xFFEF5350) else Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(22.dp)
                )
                .testTag("landscape_frame_16_9")
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onFocusRequested(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                    }
                }
        ) {
            // Live camera view (16:9 Landscape - synchronized live feed)
            AndroidView(
                factory = { landscapeTextureView },
                modifier = Modifier.fillMaxSize()
            )

            // Top-left: Smart Crop Status Pill badge
            if (isSmartCropEnabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 12.dp, start = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .border(1.dp, Color(0xFF42A5F5).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable(enabled = onToggleSmartCrop != null) { onToggleSmartCrop?.invoke() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(if (isSmartCropAnalyzing) Color(0xFFFFD54F) else Color(0xFF42A5F5), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isSmartCropAnalyzing) "✨ Tracking…" else "✨ Smart Crop",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Top-right: "16:9" Pill badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "16:9",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Focus reticle
            focusPosition?.let { pos ->
                FocusIndicator(focusPosition = pos)
            }
        }
    }
}
