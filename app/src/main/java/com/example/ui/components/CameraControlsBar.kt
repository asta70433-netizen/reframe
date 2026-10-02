package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RecordingStatus
import com.example.data.model.ZoomLevel
import com.example.data.model.ZoomOption

@Composable
fun CameraControlsBar(
    isFlashOn: Boolean,
    zoomLevel: ZoomLevel = ZoomLevel.STANDARD,
    selectedZoomOption: ZoomOption = ZoomOption.ZOOM_1X,
    recordingStatus: RecordingStatus,
    isSmartCropEnabled: Boolean = true,
    isSmartCropAnalyzing: Boolean = false,
    smartCropSubject: String = "AI Smart Crop Active",
    onSmartCropToggle: () -> Unit = {},
    onFlashToggle: () -> Unit,
    onZoomToggle: () -> Unit,
    onZoomSelect: (ZoomOption) -> Unit = {},
    onShutterClick: () -> Unit,
    onCameraSwitch: () -> Unit,
    onGalleryClick: () -> Unit,
    onLayoutSwitch: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRecording = recordingStatus is RecordingStatus.Recording

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Smart Crop Pill Indicator & Toggle
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (isSmartCropEnabled) Color(0xFF0F172A).copy(alpha = 0.85f) else Color(0xFF1E1E1E).copy(alpha = 0.85f))
                .border(
                    width = 1.dp,
                    color = if (isSmartCropEnabled) Color(0xFF38BDF8).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable { onSmartCropToggle() }
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("smart_crop_toggle_button")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (!isSmartCropEnabled) Color.Gray
                            else if (isSmartCropAnalyzing) Color(0xFFFFD54F)
                            else Color(0xFF38BDF8),
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isSmartCropEnabled) {
                        if (isSmartCropAnalyzing) "✨ Gemini Smart Crop: Analyzing…" else "✨ Gemini Smart Crop: ON"
                    } else "✨ Gemini Smart Crop: OFF",
                    color = if (isSmartCropEnabled) Color(0xFFBAE6FD) else Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Selectable Zoom Options Bar: 0.5x | 1x | 1.5x | 2x | 2.5x | 3x
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0xFF141416).copy(alpha = 0.85f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.15f),
                    shape = CircleShape
                )
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ZoomOption.entries.forEach { option ->
                val isSelected = option == selectedZoomOption
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .defaultMinSize(minWidth = 42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) Color(0xFF2C2C2E) else Color.Transparent
                        )
                        .border(
                            width = if (isSelected) 1.dp else 0.dp,
                            color = if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.7f) else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onZoomSelect(option) }
                        )
                        .padding(horizontal = 6.dp)
                        .testTag("zoom_button_${option.label}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.label,
                        color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.75f),
                        fontSize = if (isSelected) 13.sp else 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // ROW 1: Flash | Zoom (0.5/1) | Big Record Button | Camera Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flash toggle (Asterisk/Flash icon in reference)
            IconButton(
                onClick = onFlashToggle,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("flash_button")
            ) {
                Icon(
                    imageVector = if (isFlashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    contentDescription = "Flash Toggle",
                    tint = if (isFlashOn) Color(0xFFFFD54F) else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Zoom pill button: shows selectedZoomOption.label (circular pill)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onZoomToggle
                    )
                    .testTag("zoom_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = selectedZoomOption.label,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Central Shutter Button
            // Idle: Red circle in thick white outer ring
            // Recording: Red rounded square in thick white outer ring
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onShutterClick
                    )
                    .testTag("shutter_button"),
                contentAlignment = Alignment.Center
            ) {
                // Outer white border ring
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .padding(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                            .padding(4.dp)
                    )
                }

                // Inner dynamic circle / square
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE53935))
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE53935))
                        )
                    }
                }
            }

            // Camera switch button (right of shutter)
            IconButton(
                onClick = onCameraSwitch,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("switch_camera_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cameraswitch,
                    contentDescription = "Switch Camera",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        // ROW 2: Bottom Navigation Bar (Gallery | Layout Grid | Settings)
        Row(
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1C1C1E).copy(alpha = 0.85f))
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gallery
            IconButton(
                onClick = onGalleryClick,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("gallery_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = "Gallery",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Layout switch
            IconButton(
                onClick = onLayoutSwitch,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("layout_switch_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.GridView,
                    contentDescription = "Switch Layout",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Settings
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("settings_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Exporting Twin Videos Modal Dialog
 * Matches 00:37 - 00:41 in the reference video
 */
@Composable
fun ExportingTwinVideosDialog(
    visible: Boolean,
    stepTitle: String = "Exporting Twin Videos…",
    stepDescription: String = "Creating portrait & landscape versions",
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.95f))
                    .shadow(16.dp, RoundedCornerShape(24.dp))
                    .padding(vertical = 32.dp, horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(42.dp),
                        color = Color(0xFF616161),
                        strokeWidth = 3.5.dp
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = stepTitle,
                        color = Color.Black,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stepDescription,
                        color = Color.Black.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * Saved Notification Pill Toast
 * Matches 00:42 in reference video: "✅ Both videos saved to Photos"
 */
@Composable
fun SavedToPhotosNotification(
    visible: Boolean,
    message: String = "Both videos saved to Photos",
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Close else Icons.Default.CheckCircle,
                        contentDescription = if (isError) "Error" else "Success",
                        tint = if (isError) Color(0xFFEF5350) else Color(0xFF4CAF50),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = message,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
