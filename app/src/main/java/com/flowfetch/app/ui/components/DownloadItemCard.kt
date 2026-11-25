package com.flowfetch.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.flowfetch.app.data.model.DownloadItem
import com.flowfetch.app.data.model.DownloadStatus
import com.flowfetch.app.ui.theme.ElectricIndigo
import com.flowfetch.app.ui.theme.NeonCyan
import com.flowfetch.app.ui.theme.StatusError
import com.flowfetch.app.ui.theme.StatusSuccess
import com.flowfetch.app.ui.theme.StatusWarning
import com.flowfetch.app.ui.utils.Formatters

/**
 * Modern Download Item Card with real-time percentage progress bar,
 * direct gallery open action, and permanent delete option with confirmation.
 */
@Composable
fun DownloadItemCard(
    item: DownloadItem,
    onOpenClick: (DownloadItem) -> Unit,
    onDeleteClick: (DownloadItem) -> Unit,
    onCancelClick: (Long) -> Unit,
    onRetryClick: (DownloadItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (item.progress / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "dlProgress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )

                    // Platform micro-badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(Color(0xD9000000))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.platform.iconSymbol,
                            color = item.platform.brandColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title and details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = item.fileExtension.uppercase(),
                                color = NeonCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = item.formatLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Status Indicator Row
                    StatusIndicator(item = item)
                }

                // Delete / Cancel button while downloading
                if (item.status != DownloadStatus.COMPLETED) {
                    IconButton(
                        onClick = { onCancelClick(item.id) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancel Download",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Real-Time Progress Bar and Percentage
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.ENQUEUED) {
                Spacer(modifier = Modifier.height(12.dp))

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${item.progress.toInt()}%",
                            color = NeonCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${Formatters.formatBytes(item.downloadedBytes)} / ${Formatters.formatBytes(item.totalBytes)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = NeonCyan,
                        trackColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
            }

            // Completed Actions: "Open Video" and "Delete" with Warning
            if (item.status == DownloadStatus.COMPLETED) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Delete Button
                    OutlinedButton(
                        onClick = { onDeleteClick(item) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = StatusError
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 6.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete",
                            tint = StatusError,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Delete",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusError
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Open Video Button
                    Button(
                        onClick = { onOpenClick(item) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricIndigo,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 14.dp,
                            vertical = 6.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Open Video",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Failed Reason + Retry / Remove Actions
            if (item.status == DownloadStatus.FAILED) {
                if (!item.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = item.errorMessage,
                        color = StatusError.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onCancelClick(item.id) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(text = "Remove", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onRetryClick(item) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusError.copy(alpha = 0.2f),
                            contentColor = StatusError
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 14.dp,
                            vertical = 6.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Retry",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusIndicator(item: DownloadItem) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseDot"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            DownloadStatus.DOWNLOADING -> {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NeonCyan.copy(alpha = pulseAlpha))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Downloading...",
                    color = NeonCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            DownloadStatus.ENQUEUED -> {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusWarning)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Starting...",
                    color = StatusWarning,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            DownloadStatus.COMPLETED -> {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Completed",
                    tint = StatusSuccess,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Ready in Gallery",
                    color = StatusSuccess,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            DownloadStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = "Failed",
                    tint = StatusError,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Failed",
                    color = StatusError,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            DownloadStatus.PAUSED -> {
                Icon(
                    imageVector = Icons.Rounded.PauseCircleOutline,
                    contentDescription = "Paused",
                    tint = StatusWarning,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Paused",
                    color = StatusWarning,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
