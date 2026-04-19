package dev.phoneassistant.ui.chat

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.phoneassistant.data.model.ModelInfo
import dev.phoneassistant.data.model.isAudio
import dev.phoneassistant.data.model.isVisual
import dev.phoneassistant.data.model.supportsThinkingSwitch

/**
 * Rich chat input bar inspired by MnnLlmChat's layout.
 *
 * Structure:
 *  ┌───────────────────────────────────────┐
 *  │ [Attachment previews]                 │
 *  │ TextField (1-5 lines)                 │
 *  │ [+] [mic]           [Thinking] [Send] │
 *  │ [Attachment menu - expandable]        │
 *  └───────────────────────────────────────┘
 */
@Composable
fun ChatInputBar(
    draft: String,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    // Attachment state
    attachedImages: List<Uri>,
    attachedAudio: Uri?,
    attachedVideo: Uri?,
    onRemoveImage: (Uri) -> Unit,
    onRemoveAudio: () -> Unit,
    onRemoveVideo: () -> Unit,
    // Attachment menu
    isAttachmentMenuOpen: Boolean,
    onToggleAttachmentMenu: () -> Unit,
    onPickCamera: () -> Unit,
    onPickImage: () -> Unit,
    onPickAudio: () -> Unit,
    onPickVideo: () -> Unit,
    // Thinking toggle
    isThinkingEnabled: Boolean,
    onToggleThinking: () -> Unit,
    // Voice
    onSwitchToVoice: () -> Unit,
    // Model capability
    activeModel: ModelInfo?,
    // Generation state
    isGenerating: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val hasContent = draft.isNotBlank()
    val hasAttachments = attachedImages.isNotEmpty() || attachedAudio != null || attachedVideo != null
    val canSend = (hasContent || hasAttachments) && !isGenerating

    val rotationAngle by animateFloatAsState(
        targetValue = if (isAttachmentMenuOpen) 45f else 0f,
        label = "plus_rotation"
    )

    val showThinking = activeModel?.supportsThinkingSwitch() == true
    val showVisualOptions = activeModel?.isVisual() == true
    val showAudioOptions = activeModel?.isAudio() == true
    val showVoiceButton = !hasContent && !hasAttachments && !isGenerating

    Surface(shadowElevation = 8.dp, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Attachment previews
            AnimatedVisibility(
                visible = hasAttachments,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                AttachmentPreview(
                    images = attachedImages,
                    audioUri = attachedAudio,
                    videoUri = attachedVideo,
                    onRemoveImage = onRemoveImage,
                    onRemoveAudio = onRemoveAudio,
                    onRemoveVideo = onRemoveVideo,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // Text input
            TextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 150.dp),
                placeholder = { Text("输入消息...") },
                enabled = enabled && !isGenerating,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge
            )

            // Bottom button row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // [+] Attachment button
                IconButton(
                    onClick = onToggleAttachmentMenu,
                    enabled = !isGenerating
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = if (isAttachmentMenuOpen) "关闭" else "添加附件",
                        modifier = Modifier.rotate(rotationAngle),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // [mic] Voice button (shown when no text input)
                if (showVoiceButton) {
                    IconButton(onClick = onSwitchToVoice) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "语音输入",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // [Thinking] toggle
                if (showThinking) {
                    FilterChip(
                        selected = isThinkingEnabled,
                        onClick = onToggleThinking,
                        label = { Text("Thinking") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                // [Send / Stop] button
                when {
                    isGenerating -> {
                        // Stop button
                        IconButton(
                            onClick = onStopGeneration,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0xFFE53935),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "停止生成",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    canSend -> {
                        // Active send button
                        IconButton(
                            onClick = onSend,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = "发送",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    else -> {
                        // Disabled send button
                        IconButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = "发送",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }

            // Expandable attachment menu
            AnimatedVisibility(
                visible = isAttachmentMenuOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                AttachmentMenu(
                    showVisualOptions = showVisualOptions || true, // Always show camera/image for all models
                    showAudioOptions = showAudioOptions,
                    showVideoOptions = showVisualOptions,
                    onPickCamera = {
                        onPickCamera()
                        onToggleAttachmentMenu()
                    },
                    onPickImage = {
                        onPickImage()
                        onToggleAttachmentMenu()
                    },
                    onPickAudio = {
                        onPickAudio()
                        onToggleAttachmentMenu()
                    },
                    onPickVideo = {
                        onPickVideo()
                        onToggleAttachmentMenu()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}
