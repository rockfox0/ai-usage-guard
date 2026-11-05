package com.aid.usageguard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppInfoEntity::class,
        UsageSessionEntity::class,
        AppUsageRecordEntity::class,
        BehaviorEventEntity::class,
        AgentReportEntity::class,
        RestrictionRuleEntity::class,
        SyncStateEntity::class,
        AgentMessageEntity::class,
        DailyScoreEntity::class,
        PendingActionEntity::class,
        AppDailyLimitEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appInfoDao(): AppInfoDao
    abstract fun usageSessionDao(): UsageSessionDao
    abstract fun appUsageRecordDao(): AppUsageRecordDao
    abstract fun behaviorEventDao(): BehaviorEventDao
    abstract fun agentReportDao(): AgentReportDao
    abstract fun restrictionDao(): RestrictionDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun dailyScoreDao(): DailyScoreDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun appDailyLimitDao(): AppDailyLimitDao

    companion object {
        /** v1 → v2：新增每日评分表（保留历史使用数据，不破坏性迁移） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `daily_score` (
                        `date` TEXT NOT NULL,
                        `score` INTEGER NOT NULL,
                        `grade` TEXT NOT NULL,
                        `headline` TEXT NOT NULL,
                        `verdict` TEXT NOT NULL,
                        `totalMinutes` INTEGER NOT NULL,
                        `targetMinutes` INTEGER NOT NULL,
                        `lateNightMinutes` INTEGER NOT NULL,
                        `longestSessionMinutes` INTEGER NOT NULL,
                        `launchCount` INTEGER NOT NULL,
                        `slotsJson` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`date`)
                    )""".trimIndent(),
                )
            }
        }

        /** v2 → v3：评分表补算法版本/数据状态列；新增待确认操作表 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `daily_score` ADD COLUMN `algorithmVersion` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `daily_score` ADD COLUMN `dataStatus` TEXT NOT NULL DEFAULT 'VALID'")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `pending_action` (
                        `opId` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `packages` TEXT NOT NULL,
                        `paramsJson` TEXT NOT NULL,
                        `beforeJson` TEXT NOT NULL,
                        `afterJson` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `confirmedAt` INTEGER,
                        `executedAt` INTEGER,
                        `resultJson` TEXT,
                        `error` TEXT,
                        PRIMARY KEY(`opId`)
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_action_status` ON `pending_action` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_action_createdAt` ON `pending_action` (`createdAt`)")
            }
        }

        /** v3 → v4：新增单应用每日时长限额表 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `app_daily_limit` (
                        `packageName` TEXT NOT NULL,
                        `dailyLimitMinutes` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`packageName`)
                    )""".trimIndent(),
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "usage_guard.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
