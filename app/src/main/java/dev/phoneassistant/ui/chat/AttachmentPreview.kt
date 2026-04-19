package dev.phoneassistant.ui.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Preview row for attached images/audio/video before sending.
 */
@Composable
fun AttachmentPreview(
    images: List<Uri>,
    audioUri: Uri?,
    videoUri: Uri?,
    onRemoveImage: (Uri) -> Unit,
    onRemoveAudio: () -> Unit,
    onRemoveVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Image thumbnails
        items(images) { uri ->
            ImageThumbnail(uri = uri, onRemove = { onRemoveImage(uri) })
        }

        // Audio preview
        if (audioUri != null) {
            item {
                MediaPreviewCard(
                    icon = Icons.Default.AudioFile,
                    label = "音频文件",
                    onRemove = onRemoveAudio
                )
            }
        }

        // Video preview
        if (videoUri != null) {
            item {
                MediaPreviewCard(
                    icon = Icons.Default.Videocam,
                    label = "视频文件",
                    onRemove = onRemoveVideo
                )
            }
        }
    }
}

@Composable
private fun ImageThumbnail(uri: Uri, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(72.dp)) {
        Card(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(72.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(uri)
                    .crossfade(true)
                    .build(),
                contentDescription = "附件图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
        // Close button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(
                    MaterialTheme.colorScheme.error,
                    CircleShape
                )
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "删除",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onError
            )
        }
    }
}

@Composable
private fun MediaPreviewCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onRemove: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(label, style = MaterialTheme.typography.bodySmall)
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            CircleShape
                        )
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "删除",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    }
}
