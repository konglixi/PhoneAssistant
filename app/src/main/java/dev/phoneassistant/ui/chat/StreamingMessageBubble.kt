package dev.phoneassistant.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.phoneassistant.data.model.ChatMessage
import dev.phoneassistant.data.model.ChatRole
import dev.phoneassistant.domain.chat.PerfMetrics

/**
 * A message bubble that supports:
 * - Streaming text (with blinking cursor)
 * - Collapsible thinking/reasoning section
 * - Performance metrics display
 */
@Composable
fun StreamingMessageBubble(
    message: ChatMessage,
    thinkingText: String = "",
    isStreaming: Boolean = false,
    perfMetrics: PerfMetrics? = null
) {
    val isUser = message.role == ChatRole.USER
    val background = when (message.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primary
        ChatRole.ASSISTANT -> MaterialTheme.colorScheme.secondaryContainer
        ChatRole.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSecondaryContainer

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(background)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                // Collapsible thinking section
                if (thinkingText.isNotBlank()) {
                    ThinkingSection(
                        thinkingText = thinkingText,
                        textColor = textColor
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Main content with optional streaming cursor
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = message.content,
                        color = textColor,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isStreaming) {
                        BlinkingCursor(color = textColor)
                    }
                }
            }
        }

        // Performance metrics below the bubble
        if (perfMetrics != null && !isStreaming) {
            PerformanceMetricsRow(perfMetrics = perfMetrics)
        }
    }
}

@Composable
private fun ThinkingSection(
    thinkingText: String,
    textColor: androidx.compose.ui.graphics.Color
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = !expanded }
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = textColor.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "思考过程",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
                fontStyle = FontStyle.Italic
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                text = thinkingText,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.6f),
                fontStyle = FontStyle.Italic,
                modifier = Modifier
                    .padding(start = 20.dp, top = 4.dp)
                    .background(
                        textColor.copy(alpha = 0.05f),
                        MaterialTheme.shapes.small
                    )
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun BlinkingCursor(color: androidx.compose.ui.graphics.Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )
    Box(
        modifier = Modifier
            .padding(start = 2.dp, bottom = 2.dp)
            .size(width = 2.dp, height = 16.dp)
            .alpha(alpha)
            .background(color)
    )
}
