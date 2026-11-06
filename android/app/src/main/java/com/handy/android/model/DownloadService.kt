package com.handy.android.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.handy.android.HandyApp
import com.handy.android.R
import com.handy.android.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while models download (they can be 600+ MB) and
 * shows progress in the notification shade.
 */
class DownloadService : LifecycleService() {

    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, build(getString(R.string.notif_preparing), 0, true), type)
        if (!started) {
            started = true
            val models = (application as HandyApp).graph.models
            lifecycleScope.launch {
                models.status.collectLatest { map ->
                    val active = map.entries.firstOrNull { it.value is ModelStatus.Downloading || it.value is ModelStatus.Extracting }
                    if (active == null && !models.hasActiveDownloads) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    if (active == null) return@collectLatest
                    val name = ModelCatalog.byId(active.key)?.name ?: active.key
                    when (val s = active.value) {
                        is ModelStatus.Downloading -> nm.notify(
                            NOTIF_ID,
                            build(
                                getString(R.string.notif_downloading, name) + "  " +
                                    "${formatBytes(s.downloaded)} / ${formatBytes(s.total)}",
                                (s.fraction * 100).toInt(), false,
                            ),
                        )
                        is ModelStatus.Extracting -> nm.notify(
                            NOTIF_ID, build(getString(R.string.notif_extracting, name), (s.fraction * 100).toInt(), false),
                        )
                        else -> {}
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private var started = false

    private fun build(text: String, progress: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "models"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_handy)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
    }
}
