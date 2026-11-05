package com.aid.usageguard.data.repo

import com.aid.usageguard.data.db.AgentFeedbackEntity
import com.aid.usageguard.data.db.AgentGoalEntity
import com.aid.usageguard.data.db.AgentMemoryEntity
import com.aid.usageguard.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 长期记忆仓储（v1.9 Phase 3）。
 *
 * 三类数据：
 * - `agent_memory`：kind=profile（用户画像/偏好）、kind=decision（AI 建议与结果）；
 * - `agent_goal`：用户长期目标（ACTIVE / DONE / PAUSED）；
 * - `agent_feedback`：用户对建议的反馈（REJECT / PREFER / ACCEPT）。
 *
 * 记忆只影响「怎么建议」，不参与统计数字；所有统计仍必须走工具。
 */
class MemoryRepository(private val db: AppDatabase) {

    // ---------------- profile / decision ----------------

    suspend fun putMemory(kind: String, key: String, valueJson: String, confidence: Double = 1.0) =
        withContext(Dispatchers.IO) {
            db.agentMemoryDao().upsert(
                AgentMemoryEntity(
                    kind = kind,
                    memKey = key,
                    valueJson = valueJson,
                    confidence = confidence,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

    suspend fun memories(kind: String): List<AgentMemoryEntity> = withContext(Dispatchers.IO) {
        db.agentMemoryDao().byKind(kind)
    }

    suspend fun recentMemories(limit: Int = 30): List<AgentMemoryEntity> = withContext(Dispatchers.IO) {
        db.agentMemoryDao().recent(limit)
    }

    suspend fun deleteMemory(kind: String, key: String) = withContext(Dispatchers.IO) {
        db.agentMemoryDao().delete(kind, key)
    }

    // ---------------- goals ----------------

    suspend fun saveGoal(goal: String, targetPeriod: String = ""): AgentGoalEntity =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val existing = db.agentGoalDao().active().firstOrNull { it.goal == goal }
            val entity = existing?.copy(
                targetPeriod = targetPeriod.ifBlank { existing.targetPeriod },
                status = "ACTIVE",
                updatedAt = now,
            ) ?: AgentGoalEntity(
                id = UUID.randomUUID().toString(),
                goal = goal,
                targetPeriod = targetPeriod,
                status = "ACTIVE",
                createdAt = now,
                updatedAt = now,
            )
            db.agentGoalDao().upsert(entity)
            entity
        }

    suspend fun activeGoals(): List<AgentGoalEntity> = withContext(Dispatchers.IO) {
        db.agentGoalDao().active()
    }

    suspend fun allGoals(limit: Int = 20): List<AgentGoalEntity> = withContext(Dispatchers.IO) {
        db.agentGoalDao().recent(limit)
    }

    suspend fun completeGoal(id: String): Boolean = withContext(Dispatchers.IO) {
        val exists = db.agentGoalDao().byId(id) != null
        if (exists) db.agentGoalDao().updateStatus(id, "DONE", System.currentTimeMillis())
        exists
    }

    // ---------------- feedback ----------------

    suspend fun recordFeedback(
        targetType: String,
        targetId: String,
        signal: String,
        reason: String,
    ): AgentFeedbackEntity = withContext(Dispatchers.IO) {
        val entity = AgentFeedbackEntity(
            id = UUID.randomUUID().toString(),
            targetType = targetType,
            targetId = targetId,
            signal = signal,
            reason = reason,
            createdAt = System.currentTimeMillis(),
        )
        db.agentFeedbackDao().insert(entity)
        entity
    }

    suspend fun recentFeedback(limit: Int = 20): List<AgentFeedbackEntity> = withContext(Dispatchers.IO) {
        db.agentFeedbackDao().recent(limit)
    }
}
