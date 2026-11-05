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
    ],
    version = 2,
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

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "usage_guard.db",
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
