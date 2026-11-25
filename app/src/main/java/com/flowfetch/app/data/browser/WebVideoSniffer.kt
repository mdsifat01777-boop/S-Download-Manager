package com.flowfetch.app.data.browser

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DetectedStream(
    val id: String = UUID.randomUUID().toString().take(8),
    val url: String,
    val mimeType: String,
    val fileExtension: String,
    val label: String,
    val estimatedBytes: Long = 25_000_000L,
    val durationSeconds: Int = 0,
    val resolution: String = "HD",
    val thumbnailUrl: String = "",
    val isPageLevel: Boolean = false,
    val audioUrl: String? = null,
    val assetKey: String? = null
)

data class BrowserSnifferState(
    val currentUrl: String = "https://www.google.com",
    val pageTitle: String = "Browser",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val detectedStreams: List<DetectedStream> = emptyList(),
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val adBlockEnabled: Boolean = true,
    val snifferEnabled: Boolean = true
)

/**
 * Advanced 1DM-Style Media Sniffer Client with Built-in AdBlocker & Facebook/Instagram DASH Pairing.
 *
 * - Strips byte-range parameters (`bytestart=`, `byteend=`) from `fbcdn.net` / `cdninstagram.com` streams
 *   and decodes `efg=` metadata to pair HD video streams with their matching AAC audio tracks.
 * - Hooks DOM `<video>`, `fetch()`, and `XMLHttpRequest` for progressive `browser_native_hd_url` / `playable_url`.
 * - Rejects raw YouTube `/videoplayback` fragments and 3-second teasers.
 */
class WebVideoSniffer(
    var isAdBlockEnabled: Boolean = true,
    var isSnifferEnabled: Boolean = true,
    private val onPageNavigated: (url: String, hostChanged: Boolean) -> Unit = { _, _ -> },
    private val onAudioTrackDetected: (assetKey: String, audioUrl: String) -> Unit = { _, _ -> },
    private val onStreamDetected: (DetectedStream) -> Unit
) : WebViewClient() {

    private val seenUrls = mutableSetOf<String>()
    private val seenNormalizedKeys = mutableSetOf<String>()
    private val fbAudioByAsset = ConcurrentHashMap<String, String>()
    @Volatile
    private var latestFbAudioUrl: String? = null
    private var lastTopPageKey: String = ""

    fun resetStreams() {
        seenUrls.clear()
        seenNormalizedKeys.clear()
        fbAudioByAsset.clear()
        latestFbAudioUrl = null
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        handlePageNavigation(url)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (!isReload) {
            handlePageNavigation(url)
        }
    }

    private fun handlePageNavigation(url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return
        val newKey = try {
            val uri = Uri.parse(url)
            "${uri.host.orEmpty()}${uri.path.orEmpty()}".trimEnd('/')
        } catch (_: Exception) {
            url
        }
        val pageChanged = lastTopPageKey.isNotEmpty() && newKey != lastTopPageKey
        if (pageChanged) {
            resetStreams()
        }
        lastTopPageKey = newKey
        onPageNavigated(url, pageChanged)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

        if (isAdBlockEnabled && isAdUrl(reqUrl)) {
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
        }

        if (isSnifferEnabled) {
            inspectUrlForMedia(reqUrl)
        }

        return super.shouldInterceptRequest(view, request)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (!url.isNullOrBlank()) {
            if (isAdBlockEnabled && isAdUrl(url)) {
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
            }
            if (isSnifferEnabled) {
                inspectUrlForMedia(url)
            }
        }
        return super.shouldInterceptRequest(view, url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        CookieManager.getInstance().flush()
        if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) {
            onPageNavigated(url, false)
        }

        val jsMonitor = """
            (function() {
                if (window.__sDownloadHooked) {
                    scanAll();
                    return;
                }
                window.__sDownloadHooked = true;

                function isTeaserOrChunkUrl(src) {
                    if (!src) return true;
                    var s = src.toLowerCase();
                    return s.indexOf('preview') !== -1 ||
                           s.indexOf('teaser') !== -1 ||
                           s.indexOf('trailer') !== -1 ||
                           s.indexOf('thumb') !== -1 ||
                           s.indexOf('hover') !== -1 ||
                           s.indexOf('video-previews') !== -1 ||
                           s.indexOf('bytestart=') !== -1 ||
                           s.indexOf('byteend=') !== -1 ||
                           s.indexOf('&range=') !== -1 ||
                           s.indexOf('?range=') !== -1 ||
                           s.indexOf('/videoplayback') !== -1 ||
                           s.indexOf('seg-') !== -1 ||
                           s.indexOf('.ts') !== -1 ||
                           /\/\d+-\d+\.mp4/.test(s);
                }

                function getPageThumb(v) {
                    if (v && v.poster && v.poster.indexOf('http') === 0) return v.poster;
                    var og = document.querySelector('meta[property="og:image"], meta[name="og:image"], meta[name="twitter:image"]');
                    if (og && og.content && og.content.indexOf('http') === 0) return og.content;
                    return '';
                }

                function unescapeFbUrl(raw) {
                    if (!raw) return '';
                    return raw.replace(/\\\\\//g, '/').replace(/\\\//g, '/')
                              .replace(/\\\\u0026/g, '&').replace(/\\u0026/g, '&')
                              .replace(/&amp;/g, '&').replace(/\\"/g, '');
                }

                function scanTextForSocialStreams(text) {
                    try {
                        if (!text || text.length < 50) return;
                        var thumb = getPageThumb(document.querySelector('video'));
                        var hdPatterns = [
                            /"(?:browser_native_hd_url|playable_url_quality_hd|hd_src_no_ratelimit|hd_src)"\s*:\s*"([^"]+)"/g,
                            /\\"(?:browser_native_hd_url|playable_url_quality_hd|hd_src_no_ratelimit|hd_src)\\"\s*:\s*\\"([^"\\]+(?:\\.[^"\\]+)*)\\"/g
                        ];
                        var sdPatterns = [
                            /"(?:browser_native_sd_url|playable_url|sd_src_no_ratelimit|sd_src|video_url)"\s*:\s*"([^"]+)"/g,
                            /\\"(?:browser_native_sd_url|playable_url|sd_src_no_ratelimit|sd_src|video_url)\\"\s*:\s*\\"([^"\\]+(?:\\.[^"\\]+)*)\\"/g
                        ];

                        for (var p = 0; p < hdPatterns.length; p++) {
                            var m;
                            while ((m = hdPatterns[p].exec(text)) !== null) {
                                var hdUrl = unescapeFbUrl(m[1]);
                                if (hdUrl.indexOf('http') === 0 && !isTeaserOrChunkUrl(hdUrl) && window.SDownloadBridge) {
                                    window.SDownloadBridge.onMediaFoundFull(hdUrl, 'video/mp4', 0, 1280, 720, thumb);
                                }
                            }
                        }
                        for (var q = 0; q < sdPatterns.length; q++) {
                            var m2;
                            while ((m2 = sdPatterns[q].exec(text)) !== null) {
                                var sdUrl = unescapeFbUrl(m2[1]);
                                if (sdUrl.indexOf('http') === 0 && !isTeaserOrChunkUrl(sdUrl) && window.SDownloadBridge) {
                                    window.SDownloadBridge.onMediaFoundFull(sdUrl, 'video/mp4', 0, 640, 360, thumb);
                                }
                            }
                        }
                    } catch (err) {}
                }

                function isSpecificVideoPageUrl(u) {
                    if (!u || u.indexOf('http') !== 0) return false;
                    var l = u.toLowerCase();
                    if (l.indexOf('facebook.com') !== -1 || l.indexOf('fb.watch') !== -1) {
                        return l.indexOf('/reel') !== -1 || l.indexOf('/watch') !== -1 ||
                               l.indexOf('/videos/') !== -1 || l.indexOf('/share/v/') !== -1 ||
                               l.indexOf('/share/r/') !== -1 || l.indexOf('story_fbid=') !== -1;
                    }
                    if (l.indexOf('instagram.com') !== -1) {
                        return l.indexOf('/reel') !== -1 || l.indexOf('/p/') !== -1 || l.indexOf('/tv/') !== -1;
                    }
                    if (l.indexOf('youtube.com') !== -1) return l.indexOf('/watch') !== -1 || l.indexOf('/shorts/') !== -1;
                    if (l.indexOf('x.com') !== -1 || l.indexOf('twitter.com') !== -1) return l.indexOf('/status/') !== -1;
                    if (l.indexOf('tiktok.com') !== -1) return l.indexOf('/video/') !== -1;
                    return false;
                }

                function findClosestVideoPermalink(v) {
                    if (isSpecificVideoPageUrl(location.href)) return location.href;
                    try {
                        var el = v;
                        for (var depth = 0; depth < 12 && el; depth++) {
                            var links = el.querySelectorAll ? el.querySelectorAll('a[href]') : [];
                            for (var i = 0; i < links.length; i++) {
                                if (isSpecificVideoPageUrl(links[i].href)) return links[i].href;
                            }
                            el = el.parentElement;
                        }
                    } catch (e) {}
                    return '';
                }

                function checkVideo(v) {
                    if (!v) return;
                    if (v.duration && v.duration > 0 && v.duration <= 8) return;
                    var dur = (v.duration && !isNaN(v.duration) && isFinite(v.duration)) ? Math.round(v.duration) : 0;
                    var w = v.videoWidth || 0;
                    var h = v.videoHeight || 0;
                    var thumb = getPageThumb(v);
                    var src = v.currentSrc || v.src || '';

                    if (src && src.indexOf('blob:') !== 0 && !isTeaserOrChunkUrl(src)) {
                        if (window.SDownloadBridge) {
                            window.SDownloadBridge.onMediaFoundFull(src, 'video/mp4', dur, w, h, thumb);
                        }
                    } else if (src && src.indexOf('blob:') === 0 && window.SDownloadBridge) {
                        var targetPage = findClosestVideoPermalink(v);
                        if (targetPage) {
                            window.SDownloadBridge.onPageVideoAvailable(targetPage, dur, w, h, thumb);
                        }
                    }
                }

                function scanAll() {
                    try {
                        var host = location.hostname.toLowerCase();
                        if (host.indexOf('facebook.com') !== -1 || host.indexOf('instagram.com') !== -1) {
                            scanTextForSocialStreams(document.documentElement ? document.documentElement.innerHTML : '');
                        }
                        var videos = document.querySelectorAll('video');
                        for (var i = 0; i < videos.length; i++) {
                            checkVideo(videos[i]);
                        }
                    } catch (e) {}
                }

                // Hook XHR & fetch to capture feed video URLs dynamically
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function() {
                    this.addEventListener('load', function() {
                        try {
                            if (this.responseText && this.responseText.indexOf('playable_url') !== -1 ||
                                (this.responseText && this.responseText.indexOf('browser_native') !== -1)) {
                                scanTextForSocialStreams(this.responseText);
                            }
                        } catch (e) {}
                    });
                    return origOpen.apply(this, arguments);
                };

                if (window.fetch) {
                    var origFetch = window.fetch;
                    window.fetch = function() {
                        return origFetch.apply(this, arguments).then(function(resp) {
                            try {
                                var clone = resp.clone();
                                clone.text().then(function(t) {
                                    if (t && (t.indexOf('playable_url') !== -1 || t.indexOf('browser_native') !== -1)) {
                                        scanTextForSocialStreams(t);
                                    }
                                }).catch(function(){});
                            } catch (e) {}
                            return resp;
                        });
                    };
                }

                document.addEventListener('play', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        checkVideo(e.target);
                        scanAll();
                    }
                }, true);
                document.addEventListener('loadedmetadata', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') checkVideo(e.target);
                }, true);

                scanAll();
            })();
        """.trimIndent()
        view?.evaluateJavascript(jsMonitor, null)
    }

    private fun inspectUrlForMedia(streamUrl: String) {
        val clean = streamUrl.split("?").first().lowercase()
        val lowerUrl = streamUrl.lowercase()

        // 1. Filter out static web assets and tracking
        if (clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".gif") || clean.endsWith(".webp") || clean.endsWith(".css") ||
            clean.endsWith(".js") || clean.endsWith(".svg") || clean.endsWith(".vtt") ||
            clean.endsWith(".ico") || clean.endsWith(".woff") || clean.endsWith(".woff2") ||
            clean.endsWith(".ttf") || clean.endsWith(".json")
        ) {
            return
        }

        // 2. Handle Facebook / Instagram fbcdn.net & cdninstagram.com DASH/progressive MP4 streams smartly!
        val isMetaCdn = lowerUrl.contains(".fbcdn.net/") || lowerUrl.contains(".cdninstagram.com/")
        if (isMetaCdn && clean.endsWith(".mp4")) {
            handleMetaCdnStream(streamUrl)
            return
        }

        // 3. Reject HLS chunks and non-Meta DASH byte-range fragments (YouTube videoplayback, .m4s, range=)
        if (clean.endsWith(".ts") || clean.contains(".ts?") || lowerUrl.contains("seg-") ||
            lowerUrl.contains("/range/") || lowerUrl.contains("init.mp4") || lowerUrl.contains("init-") ||
            clean.endsWith(".m4s") || lowerUrl.contains("bytestart=") || lowerUrl.contains("byteend=") ||
            lowerUrl.contains("&range=") || lowerUrl.contains("?range=") || lowerUrl.contains("/videoplayback") ||
            lowerUrl.contains("sq=") || lowerUrl.contains("mime=audio")
        ) {
            return
        }

        // 4. Reject teaser / hover preview / promo snippets
        if (lowerUrl.contains("preview") || lowerUrl.contains("teaser") || lowerUrl.contains("trailer") ||
            lowerUrl.contains("video-previews") || lowerUrl.contains("hover") || lowerUrl.contains("short_preview") ||
            lowerUrl.contains("/promo/") || Regex("""/\d+-\d+\.mp4""").containsMatchIn(lowerUrl)
        ) {
            return
        }

        val isVideo = clean.endsWith(".mp4") || clean.endsWith(".m3u8") || clean.endsWith(".webm") ||
                clean.endsWith(".mkv") || clean.endsWith(".mov")
        val isAudio = clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac")

        if (isVideo || isAudio) {
            val ext = when {
                clean.endsWith(".m3u8") -> "m3u8"
                clean.endsWith(".webm") -> "webm"
                clean.endsWith(".mp3") -> "mp3"
                clean.endsWith(".m4a") -> "m4a"
                else -> "mp4"
            }

            val normKey = normalizeStreamKey(streamUrl, ext)
            if (!seenNormalizedKeys.add(normKey)) return
            seenUrls.add(streamUrl)

            val mime = when (ext) {
                "m3u8" -> "application/x-mpegURL"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                else -> "video/mp4"
            }

            val urlResolution = Regex("""(?:/|_|-)(\d{3,4}p)(?:/|_|-|\.|$)""", RegexOption.IGNORE_CASE)
                .find(streamUrl)?.groupValues?.get(1)?.lowercase()

            val isMaster = ext == "m3u8" && (streamUrl.contains("master", true) ||
                streamUrl.contains("playlist", true) || streamUrl.contains("_TPL_") ||
                streamUrl.contains("multi="))

            val label = when (ext) {
                "m3u8" -> when {
                    urlResolution != null -> "HLS Video Stream ($urlResolution)"
                    isMaster -> "HLS Master Stream (Auto HD)"
                    else -> "HLS Video Stream (HD)"
                }
                "webm" -> "WebM Video Stream"
                "mp3" -> "MP3 Audio Track"
                "m4a" -> "M4A Audio Track"
                else -> if (urlResolution != null) "MP4 Video ($urlResolution)" else "MP4 Video Stream (HD)"
            }

            onStreamDetected(
                DetectedStream(
                    url = streamUrl,
                    mimeType = mime,
                    fileExtension = ext,
                    label = label,
                    estimatedBytes = if (ext == "mp3") 5_000_000L else 35_000_000L,
                    durationSeconds = 0,
                    resolution = urlResolution ?: if (ext == "m3u8" || ext == "mp4") "HD" else "Audio"
                )
            )
        }
    }

    /**
     * Strips `bytestart`/`byteend` from `fbcdn.net` / `cdninstagram.com` URLs so the full stream
     * can be downloaded instead of a 64KB chunk, decodes `efg=` to detect resolution & video vs audio,
     * and pairs separate DASH video + audio tracks automatically.
     */
    private fun handleMetaCdnStream(rawUrl: String) {
        val fullUrl = stripByteRangeParams(rawUrl)
        val efgInfo = parseEfgMetadata(fullUrl)
        val assetKey = efgInfo.assetId.ifBlank { "default_meta" }

        // If audio track: cache & notify existing streams to pair with it, don't clutter video list unless standalone
        if (efgInfo.isAudioOnly) {
            fbAudioByAsset[assetKey] = fullUrl
            latestFbAudioUrl = fullUrl
            onAudioTrackDetected(assetKey, fullUrl)
            return
        }

        val normKey = normalizeStreamKey(fullUrl, "mp4")
        if (!seenNormalizedKeys.add(normKey)) return
        seenUrls.add(fullUrl)

        val resLabel = efgInfo.resolutionLabel.ifBlank {
            if (rawUrl.contains("bytestart=")) "HD" else "Progressive HD"
        }
        val pairedAudio = fbAudioByAsset[assetKey] ?: if (efgInfo.isDash) latestFbAudioUrl else null
        val duration = efgInfo.durationSeconds
        val estBytes = if (duration > 0) {
            val bps = when {
                resLabel.contains("1080") -> 2_200_000L
                resLabel.contains("720") -> 1_100_000L
                resLabel.contains("480") -> 650_000L
                else -> 400_000L
            }
            ((bps * duration) / 8L).coerceAtLeast(2_000_000L)
        } else 25_000_000L

        onStreamDetected(
            DetectedStream(
                url = fullUrl,
                mimeType = "video/mp4",
                fileExtension = "mp4",
                label = "Facebook / IG Video ($resLabel)",
                estimatedBytes = estBytes,
                durationSeconds = duration,
                resolution = resLabel,
                audioUrl = pairedAudio,
                assetKey = assetKey
            )
        )
    }

    private data class EfgInfo(
        val isAudioOnly: Boolean = false,
        val isDash: Boolean = false,
        val resolutionLabel: String = "",
        val durationSeconds: Int = 0,
        val assetId: String = ""
    )

    private fun parseEfgMetadata(url: String): EfgInfo {
        return try {
            val efgMatch = Regex("""efg=([a-zA-Z0-9_\-\.%]+)""").find(url) ?: return EfgInfo()
            val raw = URLDecoder.decode(efgMatch.groupValues[1], "UTF-8")
            val clean = raw.replace("-", "+").replace("_", "/")
            val pad = (4 - clean.length % 4) % 4
            val bytes = android.util.Base64.decode(clean + "=".repeat(pad), android.util.Base64.DEFAULT)
            val json = JSONObject(String(bytes, Charsets.UTF_8))

            val tag = json.optString("vencode_tag", "").lowercase()
            val isDash = tag.contains("dash") || url.contains("bytestart=")
            val isAudio = tag.contains("aac") || tag.contains("audio") || tag.contains("opus")
            val dur = json.optInt("duration_s", 0)
            val assetId = json.optString("xpv_asset_id", json.optString("asset_id", ""))

            val res = when {
                tag.contains("1080") -> "1080p Full HD"
                tag.contains("720") -> "720p HD"
                tag.contains("540") -> "540p"
                tag.contains("480") -> "480p SD"
                tag.contains("360") -> "360p SD"
                tag.contains("hd") -> "720p HD"
                tag.contains("sd") -> "360p SD"
                else -> ""
            }
            EfgInfo(isAudio, isDash, res, dur, assetId)
        } catch (_: Throwable) {
            EfgInfo()
        }
    }

    private fun stripByteRangeParams(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val builder = uri.buildUpon().clearQuery()
            for (name in uri.queryParameterNames) {
                if (!name.equals("bytestart", true) && !name.equals("byteend", true)) {
                    for (v in uri.getQueryParameters(name)) {
                        builder.appendQueryParameter(name, v)
                    }
                }
            }
            builder.build().toString()
        } catch (_: Exception) {
            url.replace(Regex("""([?&])(?:bytestart|byteend)=[^&]*"""), "$1")
                .replace("?&", "?")
                .trimEnd('?', '&')
        }
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("doubleclick.net") || lower.contains("googleads") ||
                lower.contains("googlesyndication.com") || lower.contains("adservice.google") ||
                lower.contains("adnxs.com") || lower.contains("trafficjunky.com") ||
                lower.contains("exoclick.com") || lower.contains("popads.net") ||
                lower.contains("propellerads.com") || lower.contains("adsterra.com") ||
                lower.contains("juicyads.com") || lower.contains("/pagead/") ||
                lower.contains("scorecardresearch.com") || lower.contains("moatads.com")
    }

    companion object {
        fun normalizeStreamKey(url: String, ext: String = ""): String {
            return try {
                val uri = Uri.parse(url)
                val host = uri.host.orEmpty().lowercase()
                var path = uri.path.orEmpty()
                path = path.replace(Regex("""/(?:key|token|sig|t|e|exp|expires|hash)=[^/]+"""), "")
                "$ext|$host|$path"
            } catch (_: Exception) {
                url.substringBefore("?")
            }
        }
    }
}

/**
 * JavaScript interface exposed to the WebView DOM.
 */
class MediaBridge(
    private val onMediaDetected: (
        url: String,
        mimeType: String,
        durationSec: Int,
        width: Int,
        height: Int,
        thumbUrl: String,
        isPageLevel: Boolean
    ) -> Unit
) {
    @JavascriptInterface
    fun onMediaFound(url: String?, mimeType: String?) {
        if (!url.isNullOrBlank()) {
            onMediaDetected(url, mimeType ?: "video/mp4", 0, 0, 0, "", false)
        }
    }

    @JavascriptInterface
    fun onMediaFoundWithDetails(url: String?, mimeType: String?, durationSec: Int, width: Int, height: Int) {
        if (!url.isNullOrBlank()) {
            onMediaDetected(url, mimeType ?: "video/mp4", durationSec, width, height, "", false)
        }
    }

    @JavascriptInterface
    fun onMediaFoundFull(url: String?, mimeType: String?, durationSec: Int, width: Int, height: Int, thumbUrl: String?) {
        if (!url.isNullOrBlank()) {
            onMediaDetected(url, mimeType ?: "video/mp4", durationSec, width, height, thumbUrl ?: "", false)
        }
    }

    @JavascriptInterface
    fun onPageVideoAvailable(pageUrl: String?, durationSec: Int, width: Int, height: Int, thumbUrl: String?) {
        if (!pageUrl.isNullOrBlank()) {
            onMediaDetected(pageUrl, "video/mp4", durationSec, width, height, thumbUrl ?: "", true)
        }
    }
}
