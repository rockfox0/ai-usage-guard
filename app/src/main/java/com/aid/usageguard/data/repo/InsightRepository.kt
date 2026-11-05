package com.aid.usageguard.data.repo

import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.data.db.ProactiveInsightEntity
import com.aid.usageguard.domain.insight.InsightKind
import com.aid.usageguard.domain.insight.ProactiveInsight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** 主动洞察仓储（v1.9 Phase 6）：同一日期最多一条，未读可标记。 */
class InsightRepository(private val db: AppDatabase) {

    suspend fun firstForDate(date: String): ProactiveInsight? = withContext(Dispatchers.IO) {
        db.proactiveInsightDao().firstForDate(date)?.toModel()
    }

    suspend fun countForDate(date: String): Int = withContext(Dispatchers.IO) {
        db.proactiveInsightDao().countForDate(date)
    }

    suspend fun recent(limit: Int = 20): List<ProactiveInsight> = withContext(Dispatchers.IO) {
        db.proactiveInsightDao().recent(limit).map { it.toModel() }
    }

    suspend fun save(insight: ProactiveInsight): ProactiveInsight = withContext(Dispatchers.IO) {
        val entity = ProactiveInsightEntity(
            id = insight.id.ifBlank { UUID.randomUUID().toString() },
            date = insight.date,
            kind = insight.kind.name,
            title = insight.title,
            detail = insight.detail,
            suggestion = insight.suggestion,
            read = insight.read,
            createdAt = insight.createdAt,
        )
        db.proactiveInsightDao().insert(entity)
        entity.toModel()
    }

    suspend fun markRead(id: String) = withContext(Dispatchers.IO) {
        db.proactiveInsightDao().markRead(id)
    }

    private fun ProactiveInsightEntity.toModel() = ProactiveInsight(
        id = id,
        date = date,
        kind = runCatching { InsightKind.valueOf(kind) }.getOrDefault(InsightKind.TOTAL_INCREASE),
        title = title,
        detail = detail,
        suggestion = suggestion,
        createdAt = createdAt,
        read = `read`,
    )
}
