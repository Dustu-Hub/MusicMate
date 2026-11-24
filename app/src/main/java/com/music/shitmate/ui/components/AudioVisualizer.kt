package com.music.shitmate.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * Dynamic Neon Audio Visualizer for ShitMate.
 *
 * Renders animated reactive frequency bars with gradient styling,
 * rounded caps, and ambient glow that animates smoothly while playback is active.
 */
@Composable
fun AudioVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 32,
    barHeight: Dp = 48.dp,
    barSpacing: Dp = 3.dp,
    barColor: Color = MaterialTheme.colorScheme.primary,
    accentColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    val barScales = remember(barCount) {
        List(barCount) { Animatable(0.15f) }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            barScales.forEachIndexed { index, animatable ->
                val baseDuration = 350 + (index % 5) * 80
                val target = 0.35f + Random.nextFloat() * 0.65f
                animatable.animateTo(
                    targetValue = target,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = baseDuration, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                )
            }
        } else {
            barScales.forEach { animatable ->
                animatable.animateTo(
                    targetValue = 0.12f,
                    animationSpec = tween(durationMillis = 300),
                )
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight),
    ) {
        val totalWidth = size.width
        val canvasHeight = size.height
        val spacingPx = barSpacing.toPx()
        val totalSpacing = spacingPx * (barCount - 1)
        val barWidth = ((totalWidth - totalSpacing) / barCount).coerceAtLeast(2f)

        for (i in 0 until barCount) {
            val scale = barScales[i].value
            val currentBarH = (canvasHeight * scale).coerceAtLeast(4f)
            val left = i * (barWidth + spacingPx)
            val top = (canvasHeight - currentBarH) / 2f

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(accentColor.copy(alpha = 0.85f), barColor),
                    startY = top,
                    endY = top + currentBarH,
                ),
                topLeft = Offset(left, top),
                size = Size(barWidth, currentBarH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
