package com.flowfetch.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowfetch.app.data.model.DownloadItem
import com.flowfetch.app.data.model.DownloadStatus
import com.flowfetch.app.ui.theme.ElectricIndigo

enum class DownloadFilter(val title: String) {
    ALL("All"),
    ACTIVE("Active"),
    COMPLETED("Completed")
}

fun LazyListScope.downloadListSection(
    downloads: List<DownloadItem>,
    selectedFilter: DownloadFilter,
    onFilterChange: (DownloadFilter) -> Unit,
    onOpenClick: (DownloadItem) -> Unit,
    onDeleteClick: (DownloadItem) -> Unit,
    onCancelClick: (Long) -> Unit,
    onRetryClick: (DownloadItem) -> Unit
) {
    item {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Downloads",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                // Filter chips
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DownloadFilter.values().forEach { filter ->
                        val isSelected = selectedFilter == filter
                        val chipBg = if (isSelected) ElectricIndigo else MaterialTheme.colorScheme.surfaceVariant
                        val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(chipBg)
                                .clickable { onFilterChange(filter) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = filter.title,
                                color = textColor,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    val filtered = when (selectedFilter) {
        DownloadFilter.ALL -> downloads
        DownloadFilter.ACTIVE -> downloads.filter {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.ENQUEUED
        }
        DownloadFilter.COMPLETED -> downloads.filter {
            it.status == DownloadStatus.COMPLETED
        }
    }

    if (filtered.isEmpty()) {
        item {
            EmptyDownloadPlaceholder(
                message = when (selectedFilter) {
                    DownloadFilter.ALL -> "No downloads yet. Paste a link to get started!"
                    DownloadFilter.ACTIVE -> "No active downloads currently running."
                    DownloadFilter.COMPLETED -> "No completed downloads in this session."
                }
            )
        }
    } else {
        items(
            items = filtered,
            key = { it.id }
        ) { item ->
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                DownloadItemCard(
                    item = item,
                    onOpenClick = onOpenClick,
                    onDeleteClick = onDeleteClick,
                    onCancelClick = onCancelClick,
                    onRetryClick = onRetryClick
                )
            }
        }
    }
}

@Composable
fun EmptyDownloadPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
