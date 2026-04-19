package dev.phoneassistant.ui.chat

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Displays image/video content within a message bubble.
 */
@Composable
fun ImageMessageContent(
    imageUris: List<Uri>,
    modifier: Modifier = Modifier
) {
    if (imageUris.isEmpty()) return

    if (imageUris.size == 1) {
        // Single image: display larger
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imageUris.first())
                .crossfade(true)
                .build(),
            contentDescription = "附件图片",
            contentScale = ContentScale.FillWidth,
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .padding(bottom = 8.dp)
        )
    } else {
        // Multiple images: horizontal scroll
        LazyRow(
            modifier = modifier.padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(imageUris) { uri ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "附件图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

@Composable
fun VideoMessageContent(
    videoUri: Uri,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(bottom = 8.dp)) {
        Text(
            "视频附件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
