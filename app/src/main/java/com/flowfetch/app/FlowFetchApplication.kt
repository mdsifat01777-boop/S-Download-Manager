package com.flowfetch.app

import android.app.Application
import android.util.Log
import com.flowfetch.app.data.repository.VideoRepository
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FlowFetchApplication : Application() {

    /**
     * Application-scoped singleton repository so active downloads and download history
     * persist seamlessly across Activity close/reopen and foreground service updates.
     */
    val videoRepository: VideoRepository by lazy {
        VideoRepository(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        initNativeEngines()
    }

    private fun initNativeEngines() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@FlowFetchApplication)
                FFmpeg.getInstance().init(this@FlowFetchApplication)
                Aria2c.getInstance().init(this@FlowFetchApplication)
                Log.d("FlowFetchApp", "Native YoutubeDL, FFmpeg, and Aria2c initialized successfully.")

                try {
                    YoutubeDL.getInstance().updateYoutubeDL(this@FlowFetchApplication, YoutubeDL.UpdateChannel._STABLE)
                    Log.d("FlowFetchApp", "yt-dlp checked for OTA updates successfully.")
                } catch (e: Throwable) {
                    Log.w("FlowFetchApp", "yt-dlp OTA check skipped or offline: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e("FlowFetchApp", "Failed to initialize native media engines", e)
            }
        }
    }
}
