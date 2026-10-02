package com.example.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.CameraControlsBar
import com.example.ui.components.DualCameraFrames
import com.example.ui.components.ExportingTwinVideosDialog
import com.example.ui.components.PermissionRationaleView
import com.example.ui.components.SavedToPhotosNotification
import com.example.ui.components.SettingsScreenView
import com.example.ui.components.VideoGalleryView
import com.example.ui.components.VideoPlayerDialog
import com.example.ui.viewmodel.CameraViewModel

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        viewModel.setPermissions(camera = cameraGranted, audio = audioGranted)
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Dark sleek background matching reference video
    ) {
        if (!uiState.hasCameraPermission) {
            PermissionRationaleView(
                onRequestPermissions = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                    )
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 44.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top & Bottom Dual Frames: 9:16 Portrait Box & 16:9 Landscape Box
                DualCameraFrames(
                    cameraService = viewModel.cameraService,
                    lifecycleOwner = lifecycleOwner,
                    lens = uiState.lens,
                    zoomLevel = uiState.zoomLevel,
                    selectedZoomOption = uiState.selectedZoomOption,
                    quality = uiState.quality,
                    frameRate = uiState.frameRate,
                    previewLayout = uiState.previewLayout,
                    recordingStatus = uiState.recordingStatus,
                    recordingDurationMillis = uiState.recordingDurationMillis,
                    focusPosition = uiState.focusPosition,
                    isSmartCropEnabled = uiState.isSmartCropEnabled,
                    isSmartCropAnalyzing = uiState.isSmartCropAnalyzing,
                    smartCropSubject = uiState.smartCropSubject,
                    smartCropBoundingBox = uiState.smartCropBoundingBox,
                    onToggleSmartCrop = { viewModel.toggleSmartCrop() },
                    onFocusRequested = { x, y, w, h ->
                        viewModel.triggerFocus(x, y, w, h)
                    },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Control Bar
                CameraControlsBar(
                    isFlashOn = uiState.isFlashOn,
                    zoomLevel = uiState.zoomLevel,
                    selectedZoomOption = uiState.selectedZoomOption,
                    recordingStatus = uiState.recordingStatus,
                    isSmartCropEnabled = uiState.isSmartCropEnabled,
                    isSmartCropAnalyzing = uiState.isSmartCropAnalyzing,
                    smartCropSubject = uiState.smartCropSubject,
                    onSmartCropToggle = { viewModel.toggleSmartCrop() },
                    onFlashToggle = { viewModel.toggleFlash() },
                    onZoomToggle = { viewModel.toggleZoom() },
                    onZoomSelect = { viewModel.selectZoom(it) },
                    onShutterClick = { viewModel.onShutterClicked() },
                    onCameraSwitch = { viewModel.toggleCameraLens() },
                    onGalleryClick = { viewModel.openGallery() },
                    onLayoutSwitch = { viewModel.cyclePreviewLayout() },
                    onSettingsClick = { viewModel.toggleSettings() }
                )
            }

            // "Exporting Twin Videos..." modal dialog (shown when recording finishes)
            ExportingTwinVideosDialog(
                visible = uiState.isExportingTwinVideos,
                stepTitle = uiState.exportStepTitle,
                stepDescription = uiState.exportProgressText,
                modifier = Modifier.align(Alignment.Center)
            )

            // "Both videos saved to Photos" pill toast
            SavedToPhotosNotification(
                visible = uiState.isSavedToastVisible,
                message = uiState.savedToastMessage,
                isError = uiState.isToastError,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            )
        }

        // Settings View (iOS-style replica)
        AnimatedVisibility(
            visible = uiState.isSettingsOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            SettingsScreenView(
                quality = uiState.quality,
                frameRate = uiState.frameRate,
                autoSave = uiState.autoSaveToPhotos,
                smartCropEnabled = uiState.isSmartCropEnabled,
                onQualityChange = { viewModel.setQuality(it) },
                onFrameRateChange = { viewModel.setFrameRate(it) },
                onAutoSaveChange = { viewModel.setAutoSave(it) },
                onSmartCropChange = { viewModel.setSmartCropEnabled(it) },
                onClose = { viewModel.toggleSettings() }
            )
        }

        // Video Gallery View
        AnimatedVisibility(
            visible = uiState.isGalleryOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
        ) {
            VideoGalleryView(
                videos = uiState.recordedVideos,
                onVideoClick = { viewModel.selectVideoForPlayback(it) },
                onClose = { viewModel.closeGallery() }
            )
        }

        // Playback Dialog
        uiState.selectedVideoForPlayback?.let { video ->
            VideoPlayerDialog(
                video = video,
                onDismiss = { viewModel.selectVideoForPlayback(null) },
                onDelete = { viewModel.deleteVideo(video) }
            )
        }
    }
}
