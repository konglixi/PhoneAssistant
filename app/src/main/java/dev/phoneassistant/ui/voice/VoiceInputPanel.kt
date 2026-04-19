package dev.phoneassistant.ui.voice

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * Animated waveform bar visualizer driven by audio amplitude.
 */
@Composable
fun WaveformBars(
    amplitude: Float,
    isActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val barWeights = floatArrayOf(0.4f, 0.65f, 0.85f, 1.0f, 0.85f, 0.65f, 0.4f)
    val barCount = barWeights.size

    val animatedHeights = barWeights.map { weight ->
        val target = if (isActive) {
            0.15f + amplitude * 0.85f * weight
        } else {
            0.1f
        }
        val animated by animateFloatAsState(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
            label = "bar_height"
        )
        animated
    }

    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2f)
        val gap = barWidth
        val totalWidth = barCount * barWidth + (barCount - 1) * gap
        val startX = (size.width - totalWidth) / 2f
        val cornerRadius = CornerRadius(barWidth / 2f)

        animatedHeights.forEachIndexed { index, heightFraction ->
            val barHeight = size.height * heightFraction
            val x = startX + index * (barWidth + gap)
            val y = (size.height - barHeight) / 2f

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRadius
            )
        }
    }
}
