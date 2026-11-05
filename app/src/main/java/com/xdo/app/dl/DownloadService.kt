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
 * 下载逻辑在 DownloadManager 中；服务通过伴生对象暴露给 DownloadManager 触发通知刷新。
 *
 * B28：通知由「活跃任务进度快照」驱动——单任务显示其标题/进度/取消按钮，
 *      多任务显示聚合条目，不再互相覆盖。
 * B29：启动时若任务已全部结束（极小文件先完成的竞态），立即退出，不留永久「准备下载」通知。
 */
class DownloadService : Service() {

    private var foregroundStarted = false
    private var lastRecordId = -1L

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
                lastRecordId = intent?.getLongExtra(EXTRA_RECORD_ID, -1L) ?: -1L
                refresh()
                // B29：竞态兜底——任务已经全部结束（如极小文件在服务启动前完成）则立即退出
                if (AppHolder.downloadManager?.isIdle() != false) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
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

    /** 按当前活跃任务快照重建前台通知（B08：取消按钮绑定真实 recordId） */
    private fun refresh() {
        val items = AppHolder.downloadManager?.progressSnapshot().orEmpty()
        val notif = when {
            items.isEmpty() ->
                Notify.buildProgress(this, "准备下载", 0, cancelAction(lastRecordId))
            items.size == 1 ->
                Notify.buildProgress(this, items[0].second, items[0].third, cancelAction(items[0].first))
            else -> Notify.buildProgress(
                this, "正在下载 ${items.size} 个视频",
                items.map { it.third }.average().toInt().coerceIn(0, 99), null,
            )
        }
        ensureForeground(notif)
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

        /** 触发服务按最新进度快照刷新通知 */
        fun refresh(context: Context) {
            instance?.refresh()
        }

        /**
         * 完成后：先发独立 ID 的可清除完成/失败通知，再视情况停前台。
         * B09：完成通知使用独立 ID（1002），不再被 stopForeground(REMOVE) 一并清掉；
         * B10：并发下载时任一任务完成不停服务（还有其他任务在跑则保活继续）。
         */
        fun finishDone(context: Context, recordId: Long, title: String) {
            finishWith(context, recordId, title, done = true)
        }

        fun finishFailed(context: Context, recordId: Long, title: String) {
            finishWith(context, recordId, title, done = false)
        }

        private fun finishWith(context: Context, recordId: Long, title: String, done: Boolean) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            // 先停前台（移除进行中通知 1001），再发独立完成通知（1002）
            val othersActive = AppHolder.downloadManager?.hasActiveExcept(recordId) == true
            if (!othersActive) {
                instance?.let { svc ->
                    svc.stopForeground(STOP_FOREGROUND_REMOVE)
                    svc.stopSelf()
                }
            }
            val notif = if (done) Notify.buildDone(context, title) else Notify.buildFailed(context, title)
            nm.notify(Notify.NOTIFICATION_ID_DONE, notif)
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
