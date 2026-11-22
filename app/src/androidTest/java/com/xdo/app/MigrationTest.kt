package com.xdo.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xdo.app.data.ALL_MIGRATIONS
import com.xdo.app.data.AppDatabase
import com.xdo.app.data.DownloadRecord
import com.xdo.app.data.RecordStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-01：v1 → v2 迁移测试（需在设备/模拟器上运行：gradlew connectedDebugAndroidTest）。
 *
 * v1 没有导出 schema（exportSchema=false），用不了 MigrationTestHelper，
 * 所以这里直接用原始 SQL 建一个 v1 库，再用 Room（version 2 + 迁移）打开：
 * Room 会执行迁移，并按 v2 的实体定义校验表结构和索引名——任何一处对不上都会抛异常。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    @Test
    fun duplicatesAreDedupedByPriorityAndIndexesCreated() {
        createV1 { db ->
            // 推文 100：失败 / 已完成 / 待选，应保留「已完成」那条（id=2）
            insertV1(db, id = 1, tweetId = "100", status = RecordStatus.FAILED, createdAt = 3000)
            insertV1(db, id = 2, tweetId = "100", status = RecordStatus.DONE, createdAt = 1000,
                fileUri = "content://media/external/video/media/42", progress = 100)
            insertV1(db, id = 3, tweetId = "100", status = RecordStatus.READY, createdAt = 2000)
            // 推文 200：两条都是待选，应保留较新的（id=5）
            insertV1(db, id = 4, tweetId = "200", status = RecordStatus.READY, createdAt = 1000)
            insertV1(db, id = 5, tweetId = "200", status = RecordStatus.READY, createdAt = 2000)
            // 推文 300：只有一条，原样保留
            insertV1(db, id = 6, tweetId = "300", status = RecordStatus.PAUSED, createdAt = 1000, progress = 40)
        }

        val room = openV2()
        try {
            val dao = room.recordDao()
            runBlocking {
                assertEquals(3, dao.count())

                val r100 = dao.findByTweetId("100")
                assertNotNull(r100)
                assertEquals(2L, r100!!.id)
                assertEquals("content://media/external/video/media/42", r100.fileUri)
                assertEquals(100, r100.progress)

                assertEquals(5L, dao.findByTweetId("200")!!.id)

                val r300 = dao.findByTweetId("300")!!
                assertEquals(6L, r300.id)
                assertEquals(RecordStatus.PAUSED, r300.status)
                assertEquals(40, r300.progress)
            }
            val indexes = indexNames(room.openHelper.readableDatabase)
            assertTrue(indexes.containsAll(
                listOf("index_records_tweetId", "index_records_status", "index_records_createdAt"),
            ))
        } finally {
            room.close()
        }
    }

    @Test
    fun uniqueIndexRejectsDuplicateTweet() {
        createV1 { db ->
            insertV1(db, id = 1, tweetId = "100", status = RecordStatus.DONE, createdAt = 1000)
        }
        val room = openV2()
        try {
            val dao = room.recordDao()
            runBlocking {
                val newId = dao.insertIfAbsent(record(tweetId = "100"))
                assertEquals("同一推文不应插入第二条", -1L, newId)
                assertEquals(1, dao.count())
                // 原记录没被覆盖
                assertEquals(RecordStatus.DONE, dao.findByTweetId("100")!!.status)

                assertTrue(dao.insertIfAbsent(record(tweetId = "101")) > 0)
                assertEquals(2, dao.count())
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun emptyV1DatabaseMigrates() {
        createV1 { }
        val room = openV2()
        try {
            runBlocking { assertEquals(0, room.recordDao().count()) }
        } finally {
            room.close()
        }
    }

    // ===== helpers =====

    private fun openV2(): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase } // 触发打开 → 迁移 → 结构校验

    private fun createV1(block: (SupportSQLiteDatabase) -> Unit) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(V1_CREATE_RECORDS)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            block(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }

    private fun insertV1(
        db: SupportSQLiteDatabase,
        id: Long,
        tweetId: String,
        status: Int,
        createdAt: Long,
        fileUri: String? = null,
        progress: Int = 0,
    ) {
        db.execSQL(
            """INSERT INTO records (id, sourceUrl, tweetId, authorName, handle, text, posterUrl,
                durationMs, variantsJson, chosenLabel, chosenUrl, status, progress, fileUri, fileSize,
                errorMsg, positionMs, createdAt, completedAt)
               VALUES (?, ?, ?, '', '', '', NULL, NULL, '[]', NULL, NULL, ?, ?, ?, NULL, NULL, 0, ?, NULL)""",
            arrayOf<Any?>(id, "https://x.com/i/status/$tweetId", tweetId, status, progress, fileUri, createdAt),
        )
    }

    private fun indexNames(db: SupportSQLiteDatabase): List<String> =
        db.query("PRAGMA index_list(`records`)").use { c ->
            val col = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(col)) }
        }

    private fun record(tweetId: String) = DownloadRecord(
        sourceUrl = "https://x.com/i/status/$tweetId",
        tweetId = tweetId,
        authorName = "",
        handle = "",
        text = "",
        posterUrl = null,
        durationMs = null,
        variantsJson = "[]",
        chosenLabel = null,
        chosenUrl = null,
        status = RecordStatus.PARSING,
        progress = 0,
        fileUri = null,
        fileSize = null,
        errorMsg = null,
        positionMs = 0,
        createdAt = System.currentTimeMillis(),
        completedAt = null,
    )

    private companion object {
        /** v1 时 Room 为 DownloadRecord 生成的建表语句（无索引） */
        const val V1_CREATE_RECORDS = """CREATE TABLE IF NOT EXISTS `records` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `sourceUrl` TEXT NOT NULL, `tweetId` TEXT NOT NULL, `authorName` TEXT NOT NULL,
            `handle` TEXT NOT NULL, `text` TEXT NOT NULL, `posterUrl` TEXT, `durationMs` INTEGER,
            `variantsJson` TEXT NOT NULL, `chosenLabel` TEXT, `chosenUrl` TEXT,
            `status` INTEGER NOT NULL, `progress` INTEGER NOT NULL, `fileUri` TEXT, `fileSize` INTEGER,
            `errorMsg` TEXT, `positionMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
            `completedAt` INTEGER)"""
    }
}
