package com.flowfetch.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.flowfetch.app.data.model.Platform
import com.flowfetch.app.data.model.VideoFormat
import com.flowfetch.app.data.model.VideoMetadata
import com.flowfetch.app.ui.theme.ElectricIndigo
import com.flowfetch.app.ui.theme.NeonCyan
import com.flowfetch.app.ui.theme.SurfaceCard
import com.flowfetch.app.ui.theme.SurfaceDark
import com.flowfetch.app.ui.theme.SurfaceTintedDark
import com.flowfetch.app.ui.theme.SurfaceVariantDark
import com.flowfetch.app.ui.theme.TextPrimary
import com.flowfetch.app.ui.theme.TextSecondary
import com.flowfetch.app.ui.theme.TextTertiary
import com.flowfetch.app.ui.utils.Formatters

enum class MediaTab {
    VIDEO,
    AUDIO
}

/**
 * Enhanced Video Card for S Download featuring:
 * - Separate Video Resolutions and Audio Formats tabs
 * - Multiple resolution chips (4K, 2K, 1080p, 720p, 480p, 360p, 240p)
 * - Multiple audio quality chips (MP3 320k, M4A 256k, FLAC)
 * - Large gradient Start Download button
 */
@Composable
fun VideoPreviewCard(
    metadata: VideoMetadata?,
    selectedFormat: VideoFormat?,
    onFormatSelect: (VideoFormat) -> Unit,
    onStartDownload: () -> Unit,
    onDismissCard: () -> Unit = {},
    isDownloading: Boolean = false,
    modifier: Modifier = Modifier
) {
    var activeTab by remember(metadata?.id) { mutableStateOf(MediaTab.VIDEO) }

    AnimatedVisibility(
        visible = metadata != null,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut()
    ) {
        if (metadata == null) return@AnimatedVisibility

        val videoFormats = metadata.formats.filter { !it.isAudioOnly }
        val audioFormats = metadata.formats.filter { it.isAudioOnly }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp))
                .padding(16.dp)
        ) {
            Column {
                // Top: Thumbnail with 18dp rounded corners, platform overlay, and duration
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(SurfaceDark)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(metadata.thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = metadata.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )

                    // Gradient overlay
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0xCC000000))
                                )
                            )
                    )

                    // Platform Badge
                    PlatformBadge(
                        platform = metadata.platform,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                    )

                    // Duration Badge
                    if (metadata.durationFormatted.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xCC000000))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = metadata.durationFormatted,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Title and Channel/Author
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = metadata.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 22.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = metadata.author,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Card Dismiss 'X' Button
                    IconButton(
                        onClick = onDismissCard,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss Preview",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === SEPARATE VIDEO & AUDIO TAB SELECTOR ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceVariantDark)
                        .padding(4.dp)
                ) {
                    // Video Tab Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (activeTab == MediaTab.VIDEO) ElectricIndigo else Color.Transparent)
                            .clickable {
                                activeTab = MediaTab.VIDEO
                                // Auto-select first video format if currently audio
                                if (selectedFormat?.isAudioOnly == true) {
                                    videoFormats.firstOrNull()?.let { onFormatSelect(it) }
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.VideoLibrary,
                                contentDescription = null,
                                tint = if (activeTab == MediaTab.VIDEO) Color.White else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Video (${videoFormats.size} Qualities)",
                                color = if (activeTab == MediaTab.VIDEO) Color.White else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (activeTab == MediaTab.VIDEO) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }

                    // Audio Tab Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (activeTab == MediaTab.AUDIO) NeonCyan else Color.Transparent)
                            .clickable {
                                activeTab = MediaTab.AUDIO
                                // Auto-select first audio format if currently video
                                if (selectedFormat?.isAudioOnly != true) {
                                    audioFormats.firstOrNull()?.let { onFormatSelect(it) }
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = if (activeTab == MediaTab.AUDIO) Color(0xFF0F1117) else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Audio (${audioFormats.size} Formats)",
                                color = if (activeTab == MediaTab.AUDIO) Color(0xFF0F1117) else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (activeTab == MediaTab.AUDIO) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quality Chips Horizontal Scroll
                val currentFormats = if (activeTab == MediaTab.VIDEO) videoFormats else audioFormats
                val scrollState = rememberScrollState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    currentFormats.forEach { format ->
                        val isSelected = selectedFormat?.id == format.id
                        QualityChip(
                            format = format,
                            isSelected = isSelected,
                            accentColor = if (activeTab == MediaTab.VIDEO) ElectricIndigo else NeonCyan,
                            onClick = { onFormatSelect(format) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Large Gradient "Start Download" Button
                GradientButton(
                    text = if (selectedFormat?.isAudioOnly == true) "Download Audio MP3" else "Start Video Download",
                    onClick = onStartDownload,
                    isLoading = isDownloading,
                    enabled = selectedFormat != null && !isDownloading
                )
            }
        }
    }
}

@Composable
fun QualityChip(
    format: VideoFormat,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) accentColor else SurfaceTintedDark
    val bgColor = if (isSelected) SurfaceTintedDark else SurfaceVariantDark

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (format.isAudioOnly) Icons.Rounded.Audiotrack else Icons.Rounded.Videocam,
                contentDescription = null,
                tint = if (isSelected) accentColor else TextSecondary,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    text = format.qualityLabel,
                    color = if (isSelected) TextPrimary else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
                Text(
                    text = "${Formatters.formatBytes(format.estimatedBytes)} • ${format.fileExtension.uppercase()}",
                    color = TextTertiary,
                    fontSize = 10.sp
                )
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun PlatformBadge(platform: Platform, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE60F1117))
            .border(1.dp, platform.brandColor.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = platform.iconSymbol,
                color = platform.brandColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = platform.displayName,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

