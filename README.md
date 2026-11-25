# ⚡ S Download Manager (PRO) — All-in-One Android Video Downloader & Sniffer

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20%7C%20Material%203-blue.svg)](https://developer.android.com/jetpack/compose)
[![MinSDK](https://img.shields.io/badge/MinSDK-26-orange.svg)](https://developer.android.com)
[![TargetSDK](https://img.shields.io/badge/TargetSDK-35-red.svg)](https://developer.android.com)
[![Developer](https://img.shields.io/badge/Developer-Sifat-brightgreen.svg)](https://github.com/mdsifat01777-boop)

**S Download Manager (PRO)** is a high-performance, modern Android media download manager and web video sniffer designed and developed by **Sifat** ([@mdsifat01777-boop](https://github.com/mdsifat01777-boop)). 

Built from the ground up using **Kotlin**, **Jetpack Compose**, **Material 3**, and embedded **FFmpeg**, it provides lightning-fast video extraction, genuine HD downloads (up to 1080p FHD and 4K), lossless audio/video muxing, and resilient background downloading across major social networks and web video sites.

---

## 🌟 What We Built & Why It's Powerful

Most mobile video downloaders either fail on modern encrypted/adaptive streams, take 30+ seconds to inspect a link, or claim "1080p" while actually downloading a low-res 360p or 480p file. **S Download Manager** completely fixes this with an advanced multi-layered extraction architecture:

### 1. 🚀 Ultra-Fast Parallel Extraction Racing (`raceExtractors`)
- Instead of testing extractors one by one in sequence, S Download runs platform-tailored extractors concurrently in parallel Kotlin coroutines.
- Cuts analysis time from **20–30 seconds down to 1–3 seconds** with instant preview generation.
- Sequential job cancellation ensures that switching or clearing a link immediately discards previous network jobs without flickering.

### 2. 🎯 True HD Quality Extraction & Automated Stream Muxing
- **Facebook**: Delivers real **1080p FHD**, **720p HD**, and SD streams. Automatically decodes SnapSave 1080p tokens to retrieve separate DASH video and audio tracks, losslessly muxing them into a single, perfectly synchronized MP4 file using embedded `libffmpeg.so`.
- **Instagram**: Verified extraction for Reels, Stories, and Posts with direct CDN access.
- **YouTube**: Multi-stream resolution extraction (4K, 1080p, 720p, 480p, 360p) plus high-bitrate Audio-Only MP3.
- **TikTok**: 100% watermark-free HD direct video downloads.
- **Twitter / X**: Fast multi-bitrate progressive MP4 streams via optimized syndication endpoints.
- **No Fake Quality**: All synthetic resolution tiers have been eliminated—every quality option presented corresponds to an authentic, deliverable stream.

### 3. 🌐 Built-in Web Video Sniffer
- **Deep Network Interception**: Injected DOM/XHR/fetch observers and `WebViewClient.shouldInterceptRequest` sniff media streams in real time as you browse any web page.
- **Facebook & Instagram DASH Interception**:
  - Automatically strips byte-range restrictions (`bytestart=`, `byteend=`) from `fbcdn.net` and `cdninstagram.com` URLs to download complete video streams rather than corrupted 64KB fragments.
  - Decodes `efg=` metadata to extract authentic stream resolution and pairs DASH video tracks with matching AAC audio streams.
- **M3U8 HLS Segment Engine**: Pure Kotlin OkHttp HLS segment downloader with live byte progress calculation, fragment concatenation, and local FFmpeg remuxing fallback.
- **In-Memory Tab Preservation**: Switching between the Downloader and Web Sniffer tabs keeps the WebView active without reloading or losing browsing context.

### 4. 🛡️ Background Persistence & Download Engine
- **Android Foreground Service (`DownloadForegroundService`)**: Live notification channel with real-time percentage and byte counters in the Android status bar (`FOREGROUND_SERVICE_DATA_SYNC`). Downloads continue uninterrupted when the app is minimized or swiped from recent tasks.
- **Persistent Download History (`DownloadHistoryStore`)**: Saves all active, completed, and failed downloads as JSON in `SharedPreferences`, restoring them seamlessly across app closures and restarts.
- **Media Header Integrity Check (`isValidMediaFile`)**: Validates MP4 file container headers (`ftyp`, `moov`, `mdat`) and minimum file size before indexing, preventing corrupted fragments from entering the device gallery.
- **Automatic Gallery Indexing**: Registers finished media files into Android's public `Download/` directory with `MediaScannerConnection` so videos appear immediately in Android Gallery and Google Photos.

---

## 📱 User Interface (Jetpack Compose & Material 3)

- **Dark Modern Aesthetic**: Built on a deep slate palette (`#0A0D14` background, `#181B26` surface cards) with neon violet/cyan accents.
- **Auto-Paste Trailing Button & On-Resume Detection**: Detects copied video links from clipboard upon returning to the app and presents a slide-in quick-action card.
- **Video Preview Card**: Displays high-resolution thumbnail, platform badge, video title, author, and interactive quality selection chips.
- **Interactive Download Queue**: Filterable by `All`, `Active`, and `Completed`. Features real-time progress indicators, byte counters, "Open Video" action via `FileProvider`, retry, and deletion controls.

---

## 🏗️ Architecture & Tech Stack

```
G:\S-Download\
├── app/src/main/
│   ├── AndroidManifest.xml                    # Permissions, FileProvider, Services
│   └── java/com/flowfetch/app/
│       ├── FlowFetchApplication.kt            # App lifecycle & singleton repository initialization
│       ├── MainActivity.kt                    # Single-activity Jetpack Compose host
│       ├── data/
│       │   ├── browser/
│       │   │   └── WebVideoSniffer.kt         # WebView stream interceptor & DASH byte-range stripper
│       │   ├── download/
│       │   │   ├── DownloadForegroundService.kt# Persistent background service with live notifications
│       │   │   ├── DownloadHistoryStore.kt    # SharedPreferences JSON persistence for downloads
│       │   │   ├── DownloadManagerHelper.kt   # FFmpeg muxing, HLS downloader, & media scanner
│       │   │   └── DownloadCompletedReceiver.kt
│       │   ├── extractor/
│       │   │   ├── Extractor.kt               # Base extractor contract
│       │   │   ├── DirectStreamExtractor.kt   # Direct media probing (HEAD / GET)
│       │   │   ├── SmartVideoExtractor.kt     # Multi-platform regex & DOM extractor
│       │   │   ├── InstagramExtractor.kt      # High-speed Instagram Reels/Posts extractor
│       │   │   ├── SnapSaveExtractor.kt       # SnapSave token decoder & HD audio muxer
│       │   │   ├── TwitterFxExtractor.kt      # Twitter/X API syndication extractor
│       │   │   └── NativeYtDlpExtractor.kt    # Native engine fallback
│       │   ├── model/
│       │   │   ├── DownloadItem.kt            # Download state model with persistent JSON serialization
│       │   │   ├── Platform.kt                # Platform enum & metadata
│       │   │   ├── VideoFormat.kt             # Video format, codec, audio pairing & resolution model
│       │   │   └── VideoMetadata.kt           # Media title, author, thumbnail, and stream list
│       │   └── repository/
│       │       └── VideoRepository.kt         # Parallel racing engine & extraction orchestrator
│       └── ui/
│           ├── components/
│           │   ├── AnimatedUrlInput.kt        # Glowing animated input bar with auto-paste
│           │   ├── ClipboardDetectionCard.kt  # On-resume clipboard slide-in card
│           │   ├── DownloadItemCard.kt        # Item card with progress, retry, delete, & open actions
│           │   ├── FlowFetchTopBar.kt         # Top bar with "S Download PRO" branding & info dialog
│           │   └── VideoPreviewCard.kt        # Thumbnail, quality chips, & download trigger
│           └── screen/
│               ├── BrowserScreen.kt           # Built-in web sniffer browser interface
│               ├── FlowFetchScreen.kt         # Main container with animated bottom navigation
│               └── FlowFetchViewModel.kt      # Reactive state management with coroutine job isolation
```

---

## 🛠️ Building & Testing

### Prerequisites
- Android Studio Ladybug / Meerkat or Command Line Tools
- JDK 17 or JDK 21
- Android SDK 35 (Platform Tools & Build Tools 35.0.0)

### Run Unit Tests
```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

### Build Debug APK
```powershell
.\gradlew.bat assembleDebug --no-daemon
```
The compiled APK will be generated at `app\build\outputs\apk\debug\app-debug.apk`.

---

## 👤 Author

- **Developer**: **Sifat**
- **GitHub**: [@mdsifat01777-boop](https://github.com/mdsifat01777-boop)
- **Repository**: [S-Download-Manager](https://github.com/mdsifat01777-boop/S-Download-Manager)

---

## 📄 License
This project is open-source and developed by Sifat. All rights reserved.
