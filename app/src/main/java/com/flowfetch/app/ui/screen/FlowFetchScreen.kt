package com.flowfetch.app.ui.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowfetch.app.ui.components.AnimatedUrlInput
import com.flowfetch.app.ui.components.ClipboardDetectionCard
import com.flowfetch.app.ui.components.FlowFetchTopBar
import com.flowfetch.app.ui.components.VideoPreviewCard
import com.flowfetch.app.ui.components.downloadListSection
import com.flowfetch.app.ui.theme.ElectricIndigo
import com.flowfetch.app.ui.theme.NeonCyan
import com.flowfetch.app.ui.theme.PrimaryGradient
import com.flowfetch.app.ui.theme.StatusError
import com.flowfetch.app.ui.theme.SurfaceDark
import com.flowfetch.app.ui.theme.SurfaceTintedDark
import com.flowfetch.app.ui.theme.SurfaceVariantDark
import com.flowfetch.app.ui.theme.TextSecondary

@Composable
fun FlowFetchScreen(
    viewModel: FlowFetchViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Android 13+ Notification Permission Launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Lifecycle Observer for Auto-detecting clipboard on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkClipboardOnResume(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (uiState.selectedAppTab == AppTab.DOWNLOADER) {
                FlowFetchTopBar(
                    isDarkTheme = uiState.isDarkTheme,
                    onToggleTheme = { viewModel.toggleTheme() },
                    onInfoClick = { viewModel.setInfoDialogVisible(true) }
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SurfaceTintedDark)
            ) {
                NavigationBarItem(
                    selected = uiState.selectedAppTab == AppTab.DOWNLOADER,
                    onClick = { viewModel.selectAppTab(AppTab.DOWNLOADER) },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Downloader",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text("Downloader", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricIndigo,
                        selectedTextColor = ElectricIndigo,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = SurfaceVariantDark
                    )
                )

                NavigationBarItem(
                    selected = uiState.selectedAppTab == AppTab.BROWSER,
                    onClick = { viewModel.selectAppTab(AppTab.BROWSER) },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Language,
                            contentDescription = "Web Sniffer",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text("Web Sniffer", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                        indicatorColor = SurfaceVariantDark
                    )
                )
            }
        }
    ) { innerPadding ->
        var hasVisitedBrowser by remember { mutableStateOf(false) }
        if (uiState.selectedAppTab == AppTab.BROWSER) {
            hasVisitedBrowser = true
        }

        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.selectedAppTab == AppTab.DOWNLOADER) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 1. Quick-Action Clipboard Auto-Detection Card
                    item {
                        ClipboardDetectionCard(
                            detectedUrl = uiState.detectedClipboardUrl,
                            visible = uiState.showClipboardCard,
                            onAnalyzeClick = { url ->
                                viewModel.analyzeUrl(url)
                            },
                            onDismiss = {
                                viewModel.dismissClipboardCard()
                            }
                        )
                    }

                    // 2. Animated URL Input Box (Non-overlapping buttons)
                    item {
                        AnimatedUrlInput(
                            urlText = uiState.urlInput,
                            onUrlChange = { viewModel.onUrlChanged(it) },
                            onClear = { viewModel.onClearInput() },
                            onPasteClipboard = { viewModel.onPasteFromClipboard(context) },
                            onAnalyze = { viewModel.analyzeUrl() },
                            isLoading = uiState.isLoading
                        )
                    }

                    // 3. Extracted Video Preview Card (Separate Video & Audio tabs)
                    item {
                        VideoPreviewCard(
                            metadata = uiState.extractedVideo,
                            selectedFormat = uiState.selectedFormat,
                            onFormatSelect = { viewModel.selectFormat(it) },
                            onStartDownload = { viewModel.startDownload() },
                            onDismissCard = { viewModel.onClearInput() },
                            isDownloading = uiState.isDownloading
                        )
                    }

                    // 4. Download Queue / Completed List with Delete and Open actions
                    downloadListSection(
                        downloads = uiState.downloads,
                        selectedFilter = uiState.selectedFilter,
                        onFilterChange = { viewModel.setFilter(it) },
                        onOpenClick = { viewModel.openDownloadedFile(it) },
                        onDeleteClick = { viewModel.requestDelete(it) },
                        onCancelClick = { viewModel.cancelDownload(it) },
                        onRetryClick = { viewModel.retryDownload(it) }
                    )

                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }

            if (hasVisitedBrowser) {
                val isBrowserVisible = uiState.selectedAppTab == AppTab.BROWSER
                Box(
                    modifier = if (isBrowserVisible) {
                        Modifier.fillMaxSize().zIndex(1f)
                    } else {
                        Modifier.size(0.dp).zIndex(-1f)
                    }
                ) {
                    BrowserScreen(
                        onDownloadDetected = { stream, title, url ->
                            viewModel.downloadDetectedFromBrowser(stream, title, url)
                        },
                        isVisible = isBrowserVisible,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // === DELETE CONFIRMATION DIALOG WITH WARNING ===
    uiState.itemPendingDeletion?.let { item ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(StatusError.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = "Warning",
                        tint = StatusError,
                        modifier = Modifier.size(26.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Delete Download?",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Are you sure you want to permanently delete \"${item.title}\"?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠️ This file will be completely removed from your phone storage and Gallery.",
                        color = StatusError,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete from Device", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.cancelDelete() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // S Download Info / Developer Dialog
    if (uiState.showInfoDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setInfoDialogVisible(false) },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PrimaryGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "S",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "S Download",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column {
                    // Developer Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Lead Developer",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Md. Sifat",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Features:",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Real high-speed direct downloads\n" +
                                "• Instant indexing into Android Gallery\n" +
                                "• Separate Video & Audio options\n" +
                                "• Light & Dark modern theme switcher\n" +
                                "• Delete files with confirmation warning\n" +
                                "• Auto clipboard link detection",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.setInfoDialogVisible(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricIndigo),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Close", color = Color.White)
                }
            }
        )
    }
}
