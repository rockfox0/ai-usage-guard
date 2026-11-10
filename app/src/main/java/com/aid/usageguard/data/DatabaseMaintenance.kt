package com.aid.usageguard.data

import android.util.Log
import androidx.room.withTransaction
import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.data.db.SyncStateEntity
import com.aid.usageguard.domain.maintenance.RetentionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 数据库定期清理（v1.10）：按 [RetentionPolicy] 删除过旧明细，避免数据库无限增长。
 *
 * 每天最多执行一次（`last_prune_at` 记录在 sync_state），由 UsageSyncWorker 顺带调用。
 */
class DatabaseMaintenance(private val db: AppDatabase) {

    data class Summary(
        val usageSessions: Int,
        val behaviorEvents: Int,
        val agentMessages: Int,
        val agentReports: Int,
        val agentFeedback: Int,
        val pendingActions: Int,
        val monitorTriggers: Int,
        val insights: Int,
        val memoryTrimmed: Int,
    ) {
        val totalDeleted: Int
            get() = usageSessions + behaviorEvents + agentMessages + agentReports + agentFeedback +
                pendingActions + monitorTriggers + insights
    }

    /** 每天最多清理一次；返回 null 表示还没到下次清理时间 */
    suspend fun pruneIfDue(now: Long = System.currentTimeMillis()): Summary? = withContext(Dispatchers.IO) {
        val last = runCatching { db.syncStateDao().get(KEY_LAST_PRUNE) }.getOrNull() ?: 0L
        if (now - last < DAY_MS) return@withContext null
        val summary = prune(now)
        runCatching { db.syncStateDao().put(SyncStateEntity(KEY_LAST_PRUNE, now)) }
        Log.i(TAG, "prune done: deleted=${summary.totalDeleted} $summary")
        summary
    }

    suspend fun prune(now: Long = System.currentTimeMillis()): Summary = withContext(Dispatchers.IO) {
        val cut = RetentionPolicy.cutoffs(now)
        var usageSessions = 0
        var behaviorEvents = 0
        var agentMessages = 0
        var agentReports = 0
        var agentFeedback = 0
        var pendingActions = 0
        var monitorTriggers = 0
        var insights = 0
        var memoryTrimmed = 0
        db.withTransaction {
            runCatching { db.usageSessionDao().deleteOlderThan(cut.usageSessionBeforeDate) }
            runCatching { db.behaviorEventDao().deleteOlderThan(cut.behaviorEventBefore) }
            agentMessages = runCatching { db.agentMessageDao().deleteBefore(cut.agentMessageBefore) }.getOrDefault(0)
            agentReports = runCatching { db.agentReportDao().deleteBefore(cut.agentReportBefore) }.getOrDefault(0)
            agentFeedback = runCatching { db.agentFeedbackDao().deleteBefore(cut.agentFeedbackBefore) }.getOrDefault(0)
            runCatching { db.pendingActionDao().purgeFinishedBefore(cut.pendingActionBefore) }
            monitorTriggers = runCatching { db.monitorTriggerDao().deleteBefore(cut.monitorTriggerBefore) }.getOrDefault(0)
            insights = runCatching { db.proactiveInsightDao().deleteBeforeDate(cut.insightBeforeDate) }.getOrDefault(0)
            MEMORY_KINDS.forEach { kind ->
                memoryTrimmed += runCatching { db.agentMemoryDao().trimKind(kind, cut.memoryKeepPerKind) }.getOrDefault(0)
            }
        }
        Summary(
            usageSessions = usageSessions,
            behaviorEvents = behaviorEvents,
            agentMessages = agentMessages,
            agentReports = agentReports,
            agentFeedback = agentFeedback,
            pendingActions = pendingActions,
            monitorTriggers = monitorTriggers,
            insights = insights,
            memoryTrimmed = memoryTrimmed,
        )
    }

    companion object {
        private const val TAG = "DatabaseMaintenance"
        private const val KEY_LAST_PRUNE = "last_prune_at"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val MEMORY_KINDS = listOf("plan", "decision", "profile", "goal", "feedback", "fact")
    }
}
