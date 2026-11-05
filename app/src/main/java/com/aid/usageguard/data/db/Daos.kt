package com.aid.usageguard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppInfoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AppInfoEntity>)

    @Query("SELECT * FROM app_info")
    suspend fun all(): List<AppInfoEntity>

    @Query("SELECT * FROM app_info WHERE packageName = :pkg")
    suspend fun byName(pkg: String): AppInfoEntity?

    @Query("DELETE FROM app_info")
    suspend fun clear()
}

@Dao
interface UsageSessionDao {
    @Insert
    suspend fun insertAll(items: List<UsageSessionEntity>)

    @Query("SELECT * FROM usage_session WHERE date = :date ORDER BY startTime")
    suspend fun forDate(date: String): List<UsageSessionEntity>

    @Query("SELECT * FROM usage_session WHERE packageName = :pkg AND startTime >= :since ORDER BY startTime")
    suspend fun forAppSince(pkg: String, since: Long): List<UsageSessionEntity>

    @Query("SELECT * FROM usage_session WHERE startTime >= :from AND startTime < :to ORDER BY startTime")
    suspend fun between(from: Long, to: Long): List<UsageSessionEntity>

    /** 与 [from, to) 有重叠的会话（含跨窗口/跨午夜的会话），用于按真实区间聚合 */
    @Query("SELECT * FROM usage_session WHERE endTime > :from AND startTime < :to ORDER BY startTime")
    suspend fun overlapping(from: Long, to: Long): List<UsageSessionEntity>

    @Query("SELECT MAX(endTime) FROM usage_session")
    suspend fun latestEnd(): Long?

    @Query("SELECT MAX(endTime) FROM usage_session WHERE packageName = :pkg")
    suspend fun latestEndFor(pkg: String): Long?

    @Query("DELETE FROM usage_session WHERE startTime >= :cut")
    suspend fun deleteFrom(cut: Long)

    @Query("DELETE FROM usage_session WHERE date < :before")
    suspend fun deleteOlderThan(before: String)

    @Query("SELECT COUNT(*) FROM usage_session")
    suspend fun count(): Int

    @Query("DELETE FROM usage_session")
    suspend fun clear()
}

@Dao
interface AppUsageRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AppUsageRecordEntity>)

    @Query("SELECT * FROM app_usage_record WHERE date = :date ORDER BY totalDuration DESC")
    suspend fun forDate(date: String): List<AppUsageRecordEntity>

    @Query("SELECT * FROM app_usage_record WHERE date BETWEEN :fromDate AND :toDate")
    suspend fun between(fromDate: String, toDate: String): List<AppUsageRecordEntity>

    @Query("SELECT * FROM app_usage_record WHERE packageName = :pkg ORDER BY date DESC LIMIT :limit")
    suspend fun forApp(pkg: String, limit: Int): List<AppUsageRecordEntity>

    @Query("SELECT COALESCE(SUM(totalDuration), 0) FROM app_usage_record WHERE date = :date")
    suspend fun totalForDate(date: String): Long

    @Query("DELETE FROM app_usage_record WHERE date BETWEEN :fromDate AND :toDate")
    suspend fun deleteBetween(fromDate: String, toDate: String)

    @Query("DELETE FROM app_usage_record")
    suspend fun clear()
}

@Dao
interface BehaviorEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BehaviorEventEntity): Long

    @Query("SELECT COUNT(*) FROM behavior_event WHERE dedupKey = :key AND timestamp >= :since")
    suspend fun countSince(key: String, since: Long): Int

    @Query("SELECT * FROM behavior_event ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BehaviorEventEntity>

    @Query("SELECT * FROM behavior_event ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BehaviorEventEntity>>

    @Query("SELECT * FROM behavior_event WHERE date = :date ORDER BY timestamp DESC")
    suspend fun forDate(date: String): List<BehaviorEventEntity>

    @Query("SELECT COUNT(*) FROM behavior_event WHERE date = :date")
    suspend fun countForDate(date: String): Int

    @Query("DELETE FROM behavior_event WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM behavior_event")
    suspend fun clear()
}

@Dao
interface AgentReportDao {
    @Insert
    suspend fun insert(item: AgentReportEntity): Long

    @Query("SELECT * FROM agent_report WHERE reportType = :type ORDER BY createdAt DESC LIMIT 1")
    suspend fun latest(type: String): AgentReportEntity?

    @Query("SELECT * FROM agent_report WHERE reportType = :type AND relatedDate = :date ORDER BY createdAt DESC LIMIT 1")
    suspend fun forDate(type: String, date: String): AgentReportEntity?

    @Query("SELECT * FROM agent_report ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AgentReportEntity>

    @Query("DELETE FROM agent_report")
    suspend fun clear()
}

@Dao
interface RestrictionDao {
    @Query("SELECT * FROM restriction_rule")
    fun observeAll(): Flow<List<RestrictionRuleEntity>>

    @Query("SELECT * FROM restriction_rule WHERE enabled = 1")
    suspend fun enabled(): List<RestrictionRuleEntity>

    @Query("SELECT * FROM restriction_rule WHERE packageName = :pkg")
    suspend fun byPackage(pkg: String): RestrictionRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RestrictionRuleEntity)

    @Query("DELETE FROM restriction_rule WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE key = :key")
    suspend fun get(key: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: SyncStateEntity)
}

@Dao
interface AgentMessageDao {
    @Insert
    suspend fun insert(item: AgentMessageEntity): Long

    @Query("SELECT * FROM agent_message WHERE sessionId = :session ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun recentHistory(session: String, limit: Int): List<AgentMessageEntity>

    @Query("SELECT * FROM agent_message WHERE sessionId = :session ORDER BY timestamp LIMIT :limit")
    suspend fun history(session: String, limit: Int): List<AgentMessageEntity>

    @Query("SELECT * FROM agent_message ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AgentMessageEntity>

    @Query("DELETE FROM agent_message")
    suspend fun clear()
}

@Dao
interface DailyScoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DailyScoreEntity)

    @Query("SELECT * FROM daily_score WHERE date = :date")
    suspend fun byDate(date: String): DailyScoreEntity?

    @Query("SELECT * FROM daily_score WHERE date >= :from ORDER BY date")
    suspend fun since(from: String): List<DailyScoreEntity>

    @Query("SELECT * FROM daily_score ORDER BY date DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DailyScoreEntity>

    @Query("DELETE FROM daily_score")
    suspend fun clear()
}

@Dao
interface PendingActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PendingActionEntity)

    @Query("SELECT * FROM pending_action WHERE opId = :opId")
    suspend fun byId(opId: String): PendingActionEntity?

    @Query("SELECT * FROM pending_action WHERE status = :status ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byStatus(status: String, limit: Int = 20): List<PendingActionEntity>

    @Query("SELECT * FROM pending_action ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<PendingActionEntity>

    @Query("UPDATE pending_action SET status = :status WHERE opId = :opId")
    suspend fun updateStatus(opId: String, status: String)

    @Query("DELETE FROM pending_action WHERE status IN ('EXECUTED','FAILED','EXPIRED','CANCELLED') AND createdAt < :before")
    suspend fun purgeFinishedBefore(before: Long)

    @Query("DELETE FROM pending_action")
    suspend fun clear()
}

@Dao
interface AppDailyLimitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: AppDailyLimitEntity)

    @Query("SELECT * FROM app_daily_limit")
    suspend fun all(): List<AppDailyLimitEntity>

    @Query("SELECT * FROM app_daily_limit WHERE packageName = :pkg")
    suspend fun byName(pkg: String): AppDailyLimitEntity?

    @Query("DELETE FROM app_daily_limit WHERE packageName = :pkg")
    suspend fun delete(pkg: String)

    @Query("DELETE FROM app_daily_limit")
    suspend fun clear()
}
