package com.flowfetch.app.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.flowfetch.app.MainActivity

/**
 * Foreground Service keeping active video/audio downloads running reliably even if
 * the user minimizes or closes the S Download UI on HyperOS / Android 14+.
 */
class DownloadForegroundService : Service() {

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val finishedTitle = intent.getStringExtra(EXTRA_TITLE)
                val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
                if (success && !finishedTitle.isNullOrBlank()) {
                    postCompletionNotification(finishedTitle)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE, null -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Downloading media..."
                val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 1) ?: 1
                val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
                val notif = buildProgressNotification(title, activeCount, progress)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        PROGRESS_NOTIF_ID,
                        notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(PROGRESS_NOTIF_ID, notif)
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do NOT stop self when user swipes app from recents — keep active downloads alive!
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS,
                "Active Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time download progress in background"
                setShowBadge(false)
            }
            val doneChannel = NotificationChannel(
                CHANNEL_DONE,
                "Completed Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when downloads finish saving to Gallery"
            }
            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(doneChannel)
        }
    }

    private fun buildProgressNotification(title: String, activeCount: Int, progress: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val header = if (activeCount > 1) {
            "Downloading $activeCount files ($progress%)"
        } else {
            "Downloading ($progress%)"
        }

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(header)
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun postCompletionNotification(title: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Saved to Gallery")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            notificationManager.notify(DONE_NOTIF_ID, notif)
        } catch (_: SecurityException) {}
    }

    companion object {
        private const val CHANNEL_PROGRESS = "sdownload_progress_channel"
        private const val CHANNEL_DONE = "sdownload_done_channel"
        private const val PROGRESS_NOTIF_ID = 4101
        private const val DONE_NOTIF_ID = 4102

        const val ACTION_UPDATE = "com.flowfetch.app.action.DOWNLOAD_UPDATE"
        const val ACTION_STOP = "com.flowfetch.app.action.DOWNLOAD_STOP"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ACTIVE_COUNT = "extra_active_count"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SUCCESS = "extra_success"

        fun updateProgress(context: Context, title: String, activeCount: Int, progress: Int) {
            try {
                val intent = Intent(context, DownloadForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_ACTIVE_COUNT, activeCount)
                    putExtra(EXTRA_PROGRESS, progress)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Throwable) {}
        }

        fun stop(context: Context, completedTitle: String? = null, success: Boolean = false) {
            try {
                val intent = Intent(context, DownloadForegroundService::class.java).apply {
                    action = ACTION_STOP
                    if (!completedTitle.isNullOrBlank()) putExtra(EXTRA_TITLE, completedTitle)
                    putExtra(EXTRA_SUCCESS, success)
                }
                context.startService(intent)
            } catch (_: Throwable) {}
        }
    }
}
