package com.flowfetch.app.ui.screen

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.flowfetch.app.data.browser.DetectedStream
import com.flowfetch.app.data.browser.MediaBridge
import com.flowfetch.app.data.browser.WebVideoSniffer
import com.flowfetch.app.ui.theme.NeonCyan
import com.flowfetch.app.ui.theme.SurfaceCard
import com.flowfetch.app.ui.theme.SurfaceDark
import com.flowfetch.app.ui.theme.SurfaceTintedDark
import com.flowfetch.app.ui.theme.SurfaceVariantDark
import com.flowfetch.app.ui.theme.TextPrimary
import com.flowfetch.app.ui.theme.TextSecondary
import com.flowfetch.app.ui.theme.TextTertiary
import com.flowfetch.app.ui.utils.Formatters

data class Bookmark(val name: String, val url: String, val icon: String)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onDownloadDetected: (DetectedStream, String, String) -> Unit,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf("https://www.google.com") }
    var activeUrl by remember { mutableStateOf("https://www.google.com") }
    var pageTitle by remember { mutableStateOf("Browser") }
    var isLoading by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    // 1DM-Style Options State
    var adBlockEnabled by remember { mutableStateOf(true) }
    var captureVideoEnabled by remember { mutableStateOf(true) }
    var desktopModeEnabled by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    val detectedStreams = remember { mutableStateListOf<DetectedStream>() }
    var showDownloadDialog by remember { mutableStateOf(false) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val snifferClient = remember {
        WebVideoSniffer(
            isAdBlockEnabled = adBlockEnabled,
            isSnifferEnabled = captureVideoEnabled,
            onPageNavigated = { navigatedUrl, pageChanged ->
                mainHandler.post {
                    activeUrl = navigatedUrl
                    urlInput = navigatedUrl
                    if (pageChanged) {
                        detectedStreams.clear()
                    }
                }
            },
            onAudioTrackDetected = { assetKey, audioUrl ->
                mainHandler.post {
                    for (idx in detectedStreams.indices) {
                        val s = detectedStreams[idx]
                        if (s.audioUrl.isNullOrBlank() && (s.assetKey == assetKey || assetKey == "default_meta")) {
                            detectedStreams[idx] = s.copy(audioUrl = audioUrl)
                        }
                    }
                }
            }
        ) { stream ->
            if (captureVideoEnabled) {
                mainHandler.post {
                    val newKey = WebVideoSniffer.normalizeStreamKey(stream.url, stream.fileExtension)
                    val existingIdx = detectedStreams.indexOfFirst {
                        WebVideoSniffer.normalizeStreamKey(it.url, it.fileExtension) == newKey
                    }
                    if (existingIdx == -1) {
                        detectedStreams.add(stream)
                    }
                }
            }
        }
    }

    // Keep sniffer flags updated
    snifferClient.isAdBlockEnabled = adBlockEnabled
    snifferClient.isSnifferEnabled = captureVideoEnabled

    // Handle Android hardware back press only when Web Sniffer tab is active
    BackHandler(enabled = isVisible && canGoBack) {
        webViewInstance?.goBack()
    }

    val bookmarks = remember {
        listOf(
            Bookmark("Google", "https://www.google.com", "🔍"),
            Bookmark("Facebook", "https://m.facebook.com", "f"),
            Bookmark("Instagram", "https://www.instagram.com", "📷"),
            Bookmark("X / Twitter", "https://x.com", "𝕏"),
            Bookmark("YouTube", "https://m.youtube.com", "▶"),
            Bookmark("TikTok", "https://www.tiktok.com", "♪"),
            Bookmark("Beeg", "https://beeg.com", "⚡")
        )
    }

    val mobileUa = "Mozilla/5.0 (Linux; Android 14; 24122RKC7C Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Sleek Non-Overlapping Toolbar (Redmi Turbo 4 Optimized)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Column {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Back Button
                            IconButton(
                                onClick = { webViewInstance?.goBack() },
                                enabled = canGoBack,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = if (canGoBack) TextPrimary else TextTertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Forward Button
                            IconButton(
                                onClick = { webViewInstance?.goForward() },
                                enabled = canGoForward,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = "Forward",
                                    tint = if (canGoForward) TextPrimary else TextTertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Home Button
                            IconButton(
                                onClick = {
                                    urlInput = "https://www.google.com"
                                    activeUrl = "https://www.google.com"
                                    webViewInstance?.loadUrl("https://www.google.com")
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Home,
                                    contentDescription = "Home",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Modern Search / Address Pill (Never overlaps)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(19.dp))
                                    .background(SurfaceVariantDark)
                                    .border(1.dp, SurfaceTintedDark, RoundedCornerShape(19.dp))
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = TextTertiary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    BasicTextField(
                                        value = urlInput,
                                        onValueChange = { urlInput = it },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        cursorBrush = SolidColor(NeonCyan),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            imeAction = ImeAction.Go
                                        ),
                                        keyboardActions = KeyboardActions(onGo = {
                                            val target = sanitizeOrSearch(urlInput)
                                            urlInput = target
                                            activeUrl = target
                                            detectedStreams.clear()
                                            snifferClient.resetStreams()
                                            webViewInstance?.loadUrl(target)
                                        }),
                                        decorationBox = { innerTextField ->
                                            if (urlInput.isEmpty()) {
                                                Text(
                                                    "Search or type URL...",
                                                    color = TextTertiary,
                                                    fontSize = 13.sp
                                                )
                                            }
                                            innerTextField()
                                        }
                                    )
                                    if (urlInput.isNotEmpty()) {
                                        IconButton(
                                            onClick = { urlInput = "" },
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Clear,
                                                contentDescription = "Clear",
                                                tint = TextTertiary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Refresh / Stop Button
                            IconButton(
                                onClick = {
                                    if (isLoading) webViewInstance?.stopLoading() else webViewInstance?.reload()
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = if (isLoading) Icons.Rounded.Clear else Icons.Rounded.Refresh,
                                    contentDescription = "Refresh",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 1DM-Style Options Menu Button
                            Box {
                                IconButton(
                                    onClick = { showOptionsMenu = !showOptionsMenu },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "1DM Options",
                                        tint = if (adBlockEnabled) NeonCyan else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showOptionsMenu,
                                    onDismissRequest = { showOptionsMenu = false },
                                    modifier = Modifier.background(SurfaceCard)
                                ) {
                                    // Option 1: AdBlock Toggle
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Block,
                                                    contentDescription = null,
                                                    tint = if (adBlockEnabled) NeonCyan else TextTertiary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Block Ads", color = TextPrimary, fontSize = 13.sp)
                                                Spacer(modifier = Modifier.weight(1f))
                                                if (adBlockEnabled) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = NeonCyan,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            adBlockEnabled = !adBlockEnabled
                                            showOptionsMenu = false
                                            webViewInstance?.reload()
                                        }
                                    )

                                    // Option 2: Capture Video/Audio Toggle
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Visibility,
                                                    contentDescription = null,
                                                    tint = if (captureVideoEnabled) NeonCyan else TextTertiary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Capture audio/video", color = TextPrimary, fontSize = 13.sp)
                                                Spacer(modifier = Modifier.weight(1f))
                                                if (captureVideoEnabled) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = NeonCyan,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            captureVideoEnabled = !captureVideoEnabled
                                            showOptionsMenu = false
                                        }
                                    )

                                    // Option 3: Desktop Mode
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (desktopModeEnabled) Icons.Rounded.PhoneAndroid else Icons.Rounded.DesktopWindows,
                                                    contentDescription = null,
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    if (desktopModeEnabled) "Mobile Site" else "Desktop Site",
                                                    color = TextPrimary,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        },
                                        onClick = {
                                            desktopModeEnabled = !desktopModeEnabled
                                            webViewInstance?.settings?.userAgentString = if (desktopModeEnabled) desktopUa else mobileUa
                                            webViewInstance?.reload()
                                            showOptionsMenu = false
                                        }
                                    )

                                    // Option 4: Clear Sniffed Streams
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DeleteSweep,
                                                    contentDescription = null,
                                                    tint = TextTertiary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Clear Captured Streams", color = TextSecondary, fontSize = 13.sp)
                                            }
                                        },
                                        onClick = {
                                            detectedStreams.clear()
                                            snifferClient.resetStreams()
                                            showOptionsMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 2. Quick Bookmark Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        bookmarks.forEach { b ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceVariantDark)
                                    .clickable {
                                        urlInput = b.url
                                        activeUrl = b.url
                                        detectedStreams.clear()
                                        snifferClient.resetStreams()
                                        webViewInstance?.loadUrl(b.url)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = b.icon, fontSize = 11.sp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = b.name,
                                        fontSize = 11.sp,
                                        color = TextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Page Loading Progress Bar
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { pageProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.5.dp),
                    color = NeonCyan,
                    trackColor = Color.Transparent
                )
            }

            // 3. Android WebView with DOM Video Sniffing & MediaBridge
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadsImagesAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = mobileUa
                        }

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        // Attach MediaBridge for DOM video sniffing (progressive + cookie-authenticated MSE page extraction)
                        addJavascriptInterface(MediaBridge { streamUrl, mime, dur, w, h, domThumb, isPageLevel ->
                            if (!captureVideoEnabled) return@MediaBridge
                            // Filter 3-second previews/teasers
                            if (dur in 1..8) return@MediaBridge

                            val isHls = !isPageLevel && streamUrl.contains(".m3u8", ignoreCase = true)
                            val ext = if (isHls) "m3u8" else "mp4"

                            val res = when {
                                h >= 1080 || w >= 1920 -> "1080p"
                                h >= 720 || w >= 1280 -> "720p"
                                h >= 480 -> "480p"
                                isHls || isPageLevel -> "HD"
                                else -> "Standard"
                            }

                            val label = when {
                                isPageLevel -> "Page Video Stream (Muxed HD)"
                                isHls -> "HLS Video Stream ($res)"
                                h >= 1080 -> "Full HD Stream (1080p MP4)"
                                h >= 720 -> "High Definition Stream (720p MP4)"
                                else -> "High Quality Stream (MP4)"
                            }

                            val bytes = if (dur > 0) {
                                ((3_000_000L * dur) / 8L).coerceAtLeast(15_000_000L)
                            } else {
                                35_000_000L
                            }

                            val detected = DetectedStream(
                                url = streamUrl,
                                mimeType = mime,
                                fileExtension = ext,
                                label = label,
                                estimatedBytes = bytes,
                                durationSeconds = dur,
                                resolution = res,
                                thumbnailUrl = domThumb,
                                isPageLevel = isPageLevel
                            )

                            mainHandler.post {
                                val normKey = if (isPageLevel) "page|$streamUrl" else WebVideoSniffer.normalizeStreamKey(streamUrl, ext)
                                val existingIndex = detectedStreams.indexOfFirst {
                                    val k = if (it.isPageLevel) "page|${it.url}" else WebVideoSniffer.normalizeStreamKey(it.url, it.fileExtension)
                                    k == normKey
                                }

                                if (existingIndex >= 0) {
                                    val prev = detectedStreams[existingIndex]
                                    detectedStreams[existingIndex] = prev.copy(
                                        durationSeconds = if (dur > 0) dur else prev.durationSeconds,
                                        estimatedBytes = if (dur > 0) bytes else prev.estimatedBytes,
                                        resolution = if (h > 0) res else prev.resolution,
                                        thumbnailUrl = if (domThumb.startsWith("http")) domThumb else prev.thumbnailUrl
                                    )
                                } else {
                                    if (domThumb.startsWith("http")) {
                                        for (idx in detectedStreams.indices) {
                                            if (detectedStreams[idx].thumbnailUrl.isBlank()) {
                                                detectedStreams[idx] = detectedStreams[idx].copy(thumbnailUrl = domThumb)
                                            }
                                        }
                                    }
                                    detectedStreams.add(detected)
                                }
                            }
                        }, "SDownloadBridge")

                        webViewClient = snifferClient

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                pageProgress = newProgress
                                isLoading = newProgress < 100
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) {
                                    pageTitle = title
                                }
                            }
                        }

                        loadUrl(activeUrl)
                        webViewInstance = this
                    }
                },
                update = { view ->
                    view.visibility = if (isVisible) android.view.View.VISIBLE else android.view.View.GONE
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 4. Floating 1DM "Download Video" Pill
        AnimatedVisibility(
            visible = detectedStreams.isNotEmpty(),
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 20.dp)
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .shadow(12.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(NeonCyan)
                    .clickable { showDownloadDialog = true }
                    .padding(horizontal = 16.dp, vertical = 11.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Download Video",
                        tint = Color(0xFF0F1117),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Download Video (${detectedStreams.size})",
                        color = Color(0xFF0F1117),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // 5. 1DM-Style Clean Media Streams Dialog
    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(24.dp),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Detected Media Streams",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        detectedStreams.clear()
                        snifferClient.resetStreams()
                        showDownloadDialog = false
                    }) {
                        Text("Clear All", fontSize = 12.sp, color = TextTertiary)
                    }
                }
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                    items(detectedStreams) { stream ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(SurfaceVariantDark)
                                .border(1.dp, SurfaceTintedDark, RoundedCornerShape(14.dp))
                                .clickable {
                                    showDownloadDialog = false
                                    onDownloadDetected(stream, pageTitle, activeUrl)
                                }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(NeonCyan.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Videocam,
                                        contentDescription = null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stream.label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val durationInfo = if (stream.durationSeconds > 0) {
                                        val min = stream.durationSeconds / 60
                                        val sec = stream.durationSeconds % 60
                                        "${min}m ${sec}s • "
                                    } else ""
                                    Text(
                                        text = "$durationInfo${stream.fileExtension.uppercase()} • ${Formatters.formatBytes(stream.estimatedBytes)}",
                                        fontSize = 11.sp,
                                        color = TextTertiary
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(NeonCyan)
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Download",
                                        color = Color(0xFF0F1117),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text("Close", color = TextSecondary)
                }
            }
        )
    }
}

private fun sanitizeOrSearch(input: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed
    }
    if (trimmed.contains(".") && !trimmed.contains(" ")) {
        return "https://$trimmed"
    }
    return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
}
