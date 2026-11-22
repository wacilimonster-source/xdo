package com.xdo.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * D-01：
 * - version 2：records 表加 tweetId 唯一索引 + status/createdAt 索引（见 [MIGRATION_1_2]）；
 * - exportSchema = true：每个版本的表结构导出到 app/schemas/（需提交到 git），
 *   以后改表可用 @AutoMigration，也能用 MigrationTestHelper 做迁移测试。
 */
@Database(
    entities = [DownloadRecord::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordDao(): RecordDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xdo.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    // 只在「数据库版本比应用新」时生效（开发时 adb install -d 装回旧版本），
                    // 避免启动即崩溃；正常升级永远走上面的迁移，不会丢数据
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
    }
}
