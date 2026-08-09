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

    fun buildProgress(context: Context, title: String, pct: Int, cancelPendingIntent: PendingIntent?): Notification {
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
                    addAction(0, context.getString(R.string.notif_action_cancel), cancelPendingIntent)
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