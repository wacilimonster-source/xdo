package com.xdo.app.dl

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 前台下载服务：仅在存在活跃下载任务时保活，负责通知呈现。
 * 下载逻辑在 DownloadManager 中；服务实例通过伴生对象暴露给 DownloadManager 更新通知。
 */
class DownloadService : Service() {

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
                AppHolder.downloadManager?.cancelDownload(id)
                maybeStopSelf()
            }
            else -> {
                ensureForeground(
                    Notify.buildProgress(this, "准备下载", 0, cancelAction(-1))
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ==== 通知呈现 ====

    private fun updateForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, Notify.NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notify.NOTIFICATION_ID, n)
        }
    }

    private fun ensureForeground(n: Notification) {
        if (!foregroundStarted) {
            updateForegroundCompat(n)
            foregroundStarted = true
        } else {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(Notify.NOTIFICATION_ID, n)
        }
    }

    private fun cancelAction(recordId: Long): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_RECORD_ID, recordId)
        }
        return PendingIntent.getService(
            this, recordId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun maybeStopSelf() {
        if (AppHolder.downloadManager?.isIdle() != false) stopSelf()
    }

    // ==== 伴生：DownloadManager 调用 ====

    companion object {
        const val ACTION_START = "com.xdo.app.action.START"
        const val ACTION_CANCEL = "com.xdo.app.action.CANCEL"
        const val EXTRA_RECORD_ID = "record_id"

        @Volatile
        private var instance: DownloadService? = null

        /** 启动前台服务（幂等）。必须在应用处于前台时调用 */
        fun start(context: Context, recordId: Long, title: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RECORD_ID, recordId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateProgress(context: Context, title: String, pct: Int, recordId: Long = -1) {
            instance?.ensureForeground(
                Notify.buildProgress(context, title, pct, instance?.cancelAction(recordId))
            )
        }

        /** 完成后：前台通知换成可清除的完成通知，然后停前台、停服务 */
        fun finishDone(context: Context, title: String) {
            finishWith(context, title, done = true)
        }

        fun finishFailed(context: Context, title: String) {
            finishWith(context, title, done = false)
        }

        private fun finishWith(context: Context, title: String, done: Boolean) {
            val svc = instance ?: return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val notif = if (done) Notify.buildDone(context, title) else Notify.buildFailed(context, title)
            nm.notify(Notify.NOTIFICATION_ID, notif)
            svc.stopForeground(STOP_FOREGROUND_REMOVE)
            svc.stopSelf()
        }

        /** 无活跃任务时调用（幂等安全） */
        fun maybeStop(context: Context) {
            val dm = AppHolder.downloadManager
            if (dm == null || dm.isIdle()) {
                instance?.let { svc ->
                    svc.stopForeground(STOP_FOREGROUND_REMOVE)
                    svc.stopSelf()
                }
            }
        }
    }
}