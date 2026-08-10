package com.xdo.app.data

import androidx.room.Entity
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
)

@Entity(tableName = "records")
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