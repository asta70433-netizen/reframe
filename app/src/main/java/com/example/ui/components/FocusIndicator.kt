package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun FocusIndicator(
    focusPosition: Offset,
    modifier: Modifier = Modifier
) {
    val scale = remember { Animatable(1.4f) }
    val alpha = remember { Animatable(1.0f) }
    val density = LocalDensity.current

    LaunchedEffect(focusPosition) {
        scale.snapTo(1.4f)
        alpha.snapTo(1.0f)
        scale.animateTo(1.0f, animationSpec = tween(250))
    }

    val xDp = with(density) { (focusPosition.x - 32.dp.toPx()).toDp() }
    val yDp = with(density) { (focusPosition.y - 32.dp.toPx()).toDp() }

    Box(
        modifier = modifier
            .offset(x = xDp, y = yDp)
            .size(64.dp)
            .scale(scale.value)
            .alpha(alpha.value)
            .border(1.5.dp, Color(0xFFFFD54F), RoundedCornerShape(8.dp))
    )
}
