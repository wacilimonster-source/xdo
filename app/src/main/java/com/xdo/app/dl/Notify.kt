package com.xdo.app.dl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.xdo.app.MainActivity
import com.xdo.app.R

/** 通知助手：下载进度 / 完成 / 失败 */
object Notify {

    const val CHANNEL_ID = "download"
    const val NOTIFICATION_ID = 1001

    /** 完成/失败通知的独立 ID（B09：与前台通知分离，不会被 stopForeground 清掉） */
    const val NOTIFICATION_ID_DONE = 1002

    /** T-01：后台时长超限自动暂停的提示通知（独立 ID，不覆盖完成/失败通知） */
    const val NOTIFICATION_ID_TIMEOUT = 1003

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_download),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
    }

    fun buildProgress(
        context: Context,
        title: String,
        pct: Int,
        cancelPendingIntent: PendingIntent?,
        cancelLabel: String = context.getString(R.string.notif_action_cancel),
    ): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notif_downloading, title, pct))
            .setContentIntent(appIntent(context))
            .setOngoing(true)
            .setProgress(100, pct, pct <= 0)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (cancelPendingIntent != null) {
                    addAction(0, cancelLabel, cancelPendingIntent)
                }
            }
            .build()
    }

    fun buildDone(context: Context, title: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.notif_done, title))
            .setContentIntent(appIntent(context))
            .setAutoCancel(true)
            .build()
    }

    fun buildFailed(context: Context, title: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.notif_failed, title))
            .setContentIntent(appIntent(context))
            .setAutoCancel(true)
            .build()
    }

    /** T-01：系统后台时长上限到达、下载已自动暂停；点通知回到应用（同时重置系统额度） */
    fun buildTimeoutPaused(context: Context, count: Int): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.notif_timeout_paused_title, count))
            .setContentText(context.getString(R.string.notif_timeout_paused_text))
            .setContentIntent(appIntent(context))
            .setAutoCancel(true)
            .build()
    }

    private fun appIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}