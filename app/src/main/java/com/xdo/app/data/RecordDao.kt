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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: DownloadRecord): Long

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM records WHERE status = :status")
    suspend fun deleteByStatus(status: Int)

    @Query("DELETE FROM records")
    suspend fun deleteAll()
}