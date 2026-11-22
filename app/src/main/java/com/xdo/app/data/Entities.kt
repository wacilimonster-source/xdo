package com.xdo.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object RecordStatus {
    const val PARSING = 0
    const val READY = 1
    const val DOWNLOADING = 2
    const val PAUSED = 3
    const val DONE = 4
    const val FAILED = 5
}

data class QualityOption(
    val label: String,
    val width: Int,
    val height: Int,
    val bitrateKbps: Int,
    val url: String,
    /** true 表示 url 为 HLS(m3u8) 播放列表，需分片下载后合成 */
    val isHls: Boolean = false,
    /** HLS 音频播放列表 URL（X 的 HLS 音视频分离；为空表示由下载器从 master 自行解析） */
    val audioUrl: String? = null,
)

/**
 * D-01（DB v2）索引：
 * - tweetId 唯一：一条推文只有一条记录，并发分享也不会插出重复行；
 * - status：启动对账按状态查；createdAt：首页按时间倒序。
 * 改动这里的索引必须同步写迁移（见 Migrations.kt），索引名用 Room 默认名。
 */
@Entity(
    tableName = "records",
    indices = [
        Index(value = ["tweetId"], unique = true),
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
    ],
)
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUrl: String,
    val tweetId: String,
    val authorName: String,
    val handle: String,
    val text: String,
    val posterUrl: String?,
    val durationMs: Long?,
    val variantsJson: String,
    val chosenLabel: String?,
    val chosenUrl: String?,
    val status: Int,
    val progress: Int,
    val fileUri: String?,
    val fileSize: Long?,
    val errorMsg: String?,
    val positionMs: Long,
    val createdAt: Long,
    val completedAt: Long?,
)