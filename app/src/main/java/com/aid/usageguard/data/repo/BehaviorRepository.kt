package com.aid.usageguard.data.repo

import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.data.db.BehaviorEventEntity
import com.aid.usageguard.domain.behavior.BehaviorAnalyzerContract
import com.aid.usageguard.domain.behavior.StubBehaviorAnalyzer
import com.aid.usageguard.domain.model.BehaviorEventModel
import com.aid.usageguard.domain.model.BehaviorEventType
import com.aid.usageguard.domain.model.BehaviorSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 行为事件仓储：调用本地分析器，做去重/冷却后落库，并提供查询。
 */
class BehaviorRepository(
    private val db: AppDatabase,
) {

    var analyzer: BehaviorAnalyzerContract = StubBehaviorAnalyzer()

    /** 同类型事件冷却时间，避免同一天反复生成同样的提醒 */
    var cooldownMs: Long = 6 * 60 * 60 * 1000L

    suspend fun runAnalysis(date: String = UsageEventSource.todayDate()): List<BehaviorEventModel> =
        withContext(Dispatchers.IO) {
            val candidates = runCatching { analyzer.analyze(date) }.getOrDefault(emptyList())
            val persisted = ArrayList<BehaviorEventModel>()
            for (candidate in candidates) {
                val dedupKey = buildString {
                    append(candidate.eventType.name)
                    append('|')
                    append(candidate.packageName ?: "ALL")
                    append('|')
                    append(date)
                }
                val since = candidate.timestamp - cooldownMs
                if (db.behaviorEventDao().countSince(dedupKey, since) > 0) continue
                val id = db.behaviorEventDao().insert(
                    BehaviorEventEntity(
                        eventType = candidate.eventType.name,
                        packageName = candidate.packageName,
                        severity = candidate.severity.name,
                        description = candidate.description,
                        timestamp = candidate.timestamp,
                        date = date,
                        dedupKey = dedupKey,
                    ),
                )
                persisted.add(candidate.copy(id = id))
            }
            persisted
        }

    suspend fun recent(limit: Int = 50): List<BehaviorEventModel> = withContext(Dispatchers.IO) {
        db.behaviorEventDao().recent(limit).map { it.toModel() }
    }

    fun observeRecent(limit: Int = 50): Flow<List<BehaviorEventModel>> =
        db.behaviorEventDao().observeRecent(limit).map { list -> list.map { it.toModel() } }

    suspend fun forDate(date: String): List<BehaviorEventModel> = withContext(Dispatchers.IO) {
        db.behaviorEventDao().forDate(date).map { it.toModel() }
    }

    suspend fun countToday(): Int = withContext(Dispatchers.IO) {
        db.behaviorEventDao().countForDate(UsageEventSource.todayDate())
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        db.behaviorEventDao().clear()
    }

    private fun BehaviorEventEntity.toModel() = BehaviorEventModel(
        id = id,
        eventType = BehaviorEventType.from(eventType),
        packageName = packageName,
        severity = BehaviorSeverity.entries.firstOrNull { it.name == severity } ?: BehaviorSeverity.MEDIUM,
        description = description,
        timestamp = timestamp,
        date = date,
    )
}
