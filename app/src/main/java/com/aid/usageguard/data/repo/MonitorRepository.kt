package com.aid.usageguard.data.repo

import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.data.db.MonitorRuleEntity
import com.aid.usageguard.data.db.MonitorTriggerEntity
import com.aid.usageguard.domain.monitor.MonitorAction
import com.aid.usageguard.domain.monitor.MonitorDefinition
import com.aid.usageguard.domain.monitor.MonitorMetric
import com.aid.usageguard.domain.monitor.MonitorOperator
import com.aid.usageguard.domain.monitor.MonitorSchedule
import com.aid.usageguard.domain.monitor.MonitorTriggerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** 自定义监控规则仓储（v1.9 Phase 5）：规则 CRUD + 触发去重/历史。 */
class MonitorRepository(private val db: AppDatabase) {

    suspend fun all(): List<MonitorDefinition> = withContext(Dispatchers.IO) {
        db.monitorRuleDao().all().map { it.toModel() }
    }

    suspend fun enabled(): List<MonitorDefinition> = withContext(Dispatchers.IO) {
        db.monitorRuleDao().enabled().map { it.toModel() }
    }

    suspend fun create(draft: MonitorDefinition): MonitorDefinition = withContext(Dispatchers.IO) {
        val entity = draft.copy(id = draft.id.ifBlank { UUID.randomUUID().toString() })
            .let { it.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis()) }
            .toEntity()
        db.monitorRuleDao().upsert(entity)
        entity.toModel()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        db.monitorRuleDao().delete(id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.monitorRuleDao().setEnabled(id, enabled)
    }

    suspend fun hasTriggered(ruleId: String, date: String): Boolean = withContext(Dispatchers.IO) {
        db.monitorTriggerDao().countFor(ruleId, date) > 0
    }

    suspend fun recordTrigger(result: MonitorTriggerResult, date: String) = withContext(Dispatchers.IO) {
        db.monitorTriggerDao().insert(
            MonitorTriggerEntity(
                id = UUID.randomUUID().toString(),
                ruleId = result.ruleId,
                ruleName = result.ruleName,
                date = date,
                value = result.value,
                threshold = result.threshold,
                message = result.message,
                triggeredAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun recentTriggers(limit: Int = 20): List<MonitorTriggerEntity> = withContext(Dispatchers.IO) {
        db.monitorTriggerDao().recent(limit)
    }

    private fun MonitorRuleEntity.toModel() = MonitorDefinition(
        id = id,
        name = name,
        packageName = packageName.ifBlank { null },
        metric = runCatching { MonitorMetric.valueOf(metric) }.getOrDefault(MonitorMetric.FOREGROUND_DURATION),
        operator = runCatching { MonitorOperator.valueOf(operator) }.getOrDefault(MonitorOperator.GREATER_THAN),
        threshold = threshold,
        schedule = runCatching { MonitorSchedule.valueOf(schedule) }.getOrDefault(MonitorSchedule.EVERY_DAY),
        action = runCatching { MonitorAction.valueOf(action) }.getOrDefault(MonitorAction.NOTIFY),
        timeWindow = timeWindow,
        enabled = enabled,
        createdAt = createdAt,
    )

    private fun MonitorDefinition.toEntity() = MonitorRuleEntity(
        id = id,
        name = name,
        packageName = packageName.orEmpty(),
        metric = metric.name,
        operator = operator.name,
        threshold = threshold,
        schedule = schedule.name,
        action = action.name,
        timeWindow = timeWindow,
        enabled = enabled,
        createdAt = createdAt,
    )
}
