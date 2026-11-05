package com.xdo.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    @Query("SELECT * FROM records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM records WHERE id = :id")
    fun observeById(id: Long): Flow<DownloadRecord?>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getById(id: Long): DownloadRecord?

    @Query("SELECT * FROM records WHERE sourceUrl = :url LIMIT 1")
    suspend fun findBySourceUrl(url: String): DownloadRecord?

    @Query("SELECT * FROM records WHERE status = :status")
    suspend fun getByStatus(status: Int): List<DownloadRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: DownloadRecord): Long

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM records WHERE status = :status")
    suspend fun deleteByStatus(status: Int)

    @Query("DELETE FROM records")
    suspend fun deleteAll()

    /** 定向状态更新：仅当当前状态在 allowed 内才生效，返回受影响行数（0=记录不存在或状态不符） */
    @Query("UPDATE records SET status = :status, errorMsg = :errorMsg WHERE id = :id AND status IN (:allowed)")
    suspend fun updateStatusIn(id: Long, status: Int, errorMsg: String?, allowed: List<Int>): Int

    /**
     * 写回解析结果：只更新元数据字段与状态，绝不触碰 chosenUrl/fileUri/progress/fileSize 等
     * 下载相关字段（防 B02 状态重置、B12 记录复活）。仅当当前状态在 allowed 内才生效。
     */
    @Query(
        """UPDATE records SET
            authorName = :authorName, handle = :handle, text = :text,
            posterUrl = :posterUrl, durationMs = :durationMs, variantsJson = :variantsJson,
            status = :status, errorMsg = :errorMsg
        WHERE id = :id AND status IN (:allowed)"""
    )
    suspend fun applyResolveResult(
        id: Long,
        authorName: String,
        handle: String,
        text: String,
        posterUrl: String?,
        durationMs: Long?,
        variantsJson: String,
        status: Int,
        errorMsg: String?,
        allowed: List<Int>,
    ): Int

    /** 只更新播放断点（播放页退出时用，避免整行 REPLACE 覆盖并发写入） */
    @Query("UPDATE records SET positionMs = :pos WHERE id = :id")
    suspend fun updatePosition(id: Long, pos: Long)
}