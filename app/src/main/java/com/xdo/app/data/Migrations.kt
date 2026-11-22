package com.xdo.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库迁移。
 *
 * D-01：v1 → v2
 * - 不改列，只加索引：
 *   - tweetId 唯一索引：数据库层保证「一条推文只有一条记录」（之前只靠内存里的 Mutex）；
 *   - status 索引：启动对账 getByStatus；
 *   - createdAt 索引：首页 ORDER BY createdAt DESC。
 * - 建唯一索引前先去重：老版本（B27 修复前）并发分享可能插入过重复记录。
 *   每个 tweetId 只保留一条，优先级：已完成 > 下载中 > 已暂停 > 待选清晰度 > 失败 > 解析中，
 *   同优先级保留最新的。被删记录已保存的视频文件留在相册里不动，只是不再出现在记录列表。
 * - 只用相关子查询，不用窗口函数（ROW_NUMBER 需要 SQLite 3.25 / Android 11，minSdk 是 26）。
 *
 * 索引名必须与 Room 按 @Entity(indices) 生成的默认名一致（index_<表>_<列>），
 * 否则迁移后的结构校验会失败并崩溃。
 *
 * v1 当年 exportSchema=false，没有 1.json，所以这一步只能手写；
 * 从 v2 起 schema 会导出到 app/schemas/，以后的结构变更可以用 AutoMigration。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(DEDUPE_BY_TWEET_ID_SQL)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_records_tweetId` ON `records` (`tweetId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_records_status` ON `records` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_records_createdAt` ON `records` (`createdAt`)")
    }
}

/** 全部迁移，按版本顺序 */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)

/**
 * 去重：对每个 tweetId，用子查询挑出「最该保留」的那一条的 id，其余删除。
 * 状态取值见 [RecordStatus]：DONE=4, DOWNLOADING=2, PAUSED=3, READY=1, FAILED=5, PARSING=0。
 */
internal const val DEDUPE_BY_TWEET_ID_SQL = """
DELETE FROM records WHERE id NOT IN (
    SELECT (
        SELECT r2.id FROM records AS r2
        WHERE r2.tweetId = r1.tweetId
        ORDER BY
            CASE r2.status
                WHEN 4 THEN 0
                WHEN 2 THEN 1
                WHEN 3 THEN 2
                WHEN 1 THEN 3
                WHEN 5 THEN 4
                ELSE 5
            END,
            r2.createdAt DESC,
            r2.id DESC
        LIMIT 1
    )
    FROM records AS r1
    GROUP BY r1.tweetId
)
"""
