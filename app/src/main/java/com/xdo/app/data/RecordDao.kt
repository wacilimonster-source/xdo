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

    @Query("SELECT COUNT(*) FROM records")
    suspend fun count(): Int

    /**
     * D-01：按推文 ID 查重（走唯一索引）。
     * 原来按 sourceUrl 查，老版本存的链接格式不统一（x.com / twitter.com / 带参数）时会查不到，
     * 同一推文被再插一条；tweetId 才是稳定的业务主键。
     */
    @Query("SELECT * FROM records WHERE tweetId = :tweetId LIMIT 1")
    suspend fun findByTweetId(tweetId: String): DownloadRecord?

    @Query("SELECT * FROM records WHERE status = :status")
    suspend fun getByStatus(status: Int): List<DownloadRecord>

    /**
     * D-01：插入新记录。tweetId 已存在时什么都不做，返回 -1（调用方再查出已有记录）。
     * 新建记录必须用它，不能用 [upsert]：REPLACE 碰到唯一索引冲突会先删掉已有的那条记录。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(record: DownloadRecord): Long

    /**
     * 整行写回已有记录（按主键覆盖）。只用于更新已存在的记录；新建请用 [insertIfAbsent]。
     */
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

    /**
     * P-01：下载进度节流落库——只改 progress/fileSize 两列，且仅当记录仍处于 [status]（下载中）时生效。
     * - 不再用旧快照整行 REPLACE（REPLACE = 删除+插入，会触发全表观察者、并可能覆盖并发写入）；
     * - 暂停/取消/删除/完成之后的迟到进度写入影响 0 行，不会把记录「打回下载中」或「复活」；
     * - fileSize 传 null 时保留原值（HLS 下载中不知道最终大小）。
     */
    @Query(
        "UPDATE records SET progress = :progress, fileSize = COALESCE(:fileSize, fileSize) " +
            "WHERE id = :id AND status = :status"
    )
    suspend fun updateProgress(id: Long, progress: Int, fileSize: Long?, status: Int): Int
}
