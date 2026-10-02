package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FrameRate
import com.example.data.model.VideoQuality

/**
 * Exact replica of the iOS ReFrame Settings Screen (00:26 - 00:32 in reference video)
 */
@Composable
fun SettingsScreenView(
    quality: VideoQuality,
    frameRate: FrameRate,
    autoSave: Boolean,
    smartCropEnabled: Boolean = true,
    onQualityChange: (VideoQuality) -> Unit,
    onFrameRateChange: (FrameRate) -> Unit,
    onAutoSaveChange: (Boolean) -> Unit,
    onSmartCropChange: (Boolean) -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF2F2F7)) // iOS grouped background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Header: "Settings" with back arrow
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.Black
                    )
                }
                Text(
                    text = "Settings",
                    color = Color.Black,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 1: Camera
            SectionHeader(title = "Camera")
            SettingsCard {
                SettingsRow(title = "Default Camera", value = "Back ↕")
                SettingsDivider()
                SettingsRow(title = "Default Preview", value = "Portrait First (9:16) ↕")
                SettingsDivider()
                SettingsRow(title = "Preview Layout", value = "Split ↕")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Gemini AI Smart Crop
            SectionHeader(title = "AI Smart Crop (Gemini Vision)")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Auto-Frame Subjects",
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Uses Gemini Vision AI to detect people and objects, smoothly adjusting the 16:9 frame without distortion.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Switch(
                        checked = smartCropEnabled,
                        onCheckedChange = onSmartCropChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF007AFF)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 2: Appearance
            SectionHeader(title = "Appearance")
            SettingsCard {
                SettingsRow(title = "Theme", value = "System ↕")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 3: Export
            SectionHeader(title = "Export")
            SettingsCard {
                // Quality options (4K, 2K, 1080p)
                VideoQuality.entries.forEachIndexed { index, q ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onQualityChange(q) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = q.title,
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = if (quality == q) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (quality == q) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = Color(0xFF007AFF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (index < VideoQuality.entries.size - 1) {
                        SettingsDivider()
                    }
                }

                SettingsDivider()

                // Frame Rate selector: 24 FPS | 30 FPS | 60 FPS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Frame Rate",
                        color = Color.Black,
                        fontSize = 16.sp
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFE5E5EA))
                            .padding(2.dp)
                    ) {
                        FrameRate.entries.forEach { rate ->
                            val selected = frameRate == rate
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) Color.White else Color.Transparent)
                                    .clickable { onFrameRateChange(rate) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${rate.fps} FPS",
                                    color = Color.Black,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                SettingsDivider()

                // Codec
                SettingsRow(title = "Codec", value = "High Efficiency (HEVC) ↕")

                SettingsDivider()

                // Auto-Save to Photos Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Auto-Save to Photos",
                        color = Color.Black,
                        fontSize = 16.sp
                    )
                    Switch(
                        checked = autoSave,
                        onCheckedChange = onAutoSaveChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF34C759)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 4: Support
            SectionHeader(title = "Support")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.MailOutline,
                        contentDescription = "Contact",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Contact Us",
                        color = Color.Black,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 5: Legal
            SectionHeader(title = "Legal")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Privacy",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Privacy Policy & Terms",
                        color = Color.Black,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF6C6C70),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.Black,
            fontSize = 16.sp
        )
        Text(
            text = value,
            color = Color(0xFF8E8E93),
            fontSize = 15.sp
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = Color(0xFFE5E5EA),
        thickness = 0.6.dp,
        modifier = Modifier.padding(start = 16.dp)
    )
}
