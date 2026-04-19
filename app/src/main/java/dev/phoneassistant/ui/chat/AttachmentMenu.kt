package dev.phoneassistant.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Expandable attachment picker menu with camera, image, audio, and video options.
 */
@Composable
fun AttachmentMenu(
    showVisualOptions: Boolean,
    showAudioOptions: Boolean,
    showVideoOptions: Boolean,
    onPickCamera: () -> Unit,
    onPickImage: () -> Unit,
    onPickAudio: () -> Unit,
    onPickVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        if (showVisualOptions) {
            AttachmentMenuItem(
                icon = Icons.Default.CameraAlt,
                label = "拍照",
                onClick = onPickCamera
            )
            AttachmentMenuItem(
                icon = Icons.Default.Image,
                label = "图片",
                onClick = onPickImage
            )
        }
        if (showAudioOptions) {
            AttachmentMenuItem(
                icon = Icons.Default.AudioFile,
                label = "音频",
                onClick = onPickAudio
            )
        }
        if (showVideoOptions) {
            AttachmentMenuItem(
                icon = Icons.Default.Videocam,
                label = "视频",
                onClick = onPickVideo
            )
        }
    }
}

@Composable
private fun AttachmentMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
