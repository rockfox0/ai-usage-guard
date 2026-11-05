package com.aid.usageguard.data.repo

import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.data.db.PendingActionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 待确认操作的持久化（v1.7 方案 6.2）。
 *
 * 控制类工具不再直接改设置：工具把「具体操作 + 修改前后值」写成草案（PENDING），
 * 用户在 UI 上确认后由 [com.aid.usageguard.domain.action.ActionExecutor] 按保存的参数执行。
 * 执行器以 opId 去重，重复确认不会重复执行。
 */
class ActionDraftRepository(private val db: AppDatabase) {

    private val dao get() = db.pendingActionDao()

    suspend fun propose(
        taskId: String,
        type: String,
        packages: Set<String> = emptySet(),
        paramsJson: String,
        beforeJson: String = "{}",
        afterJson: String = "{}",
        ttlMs: Long = DEFAULT_TTL_MS,
    ): PendingActionEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = PendingActionEntity(
            opId = UUID.randomUUID().toString(),
            taskId = taskId,
            type = type,
            packages = packages.joinToString(","),
            paramsJson = paramsJson,
            beforeJson = beforeJson,
            afterJson = afterJson,
            status = STATUS_PENDING,
            createdAt = now,
            expiresAt = now + ttlMs,
        )
        dao.upsert(entity)
        entity
    }

    suspend fun pending(limit: Int = 20): List<PendingActionEntity> = withContext(Dispatchers.IO) {
        expireStaleInternal()
        dao.byStatus(STATUS_PENDING, limit)
    }

    suspend fun byId(opId: String): PendingActionEntity? = withContext(Dispatchers.IO) {
        dao.byId(opId)
    }

    /**
     * 标记为已确认（仅 PENDING 且未过期可确认）。
     * 返回 null 表示草案不存在、已过期或已被处理 —— 调用方不得重复执行。
     */
    suspend fun markConfirmed(opId: String): PendingActionEntity? = withContext(Dispatchers.IO) {
        val item = dao.byId(opId) ?: return@withContext null
        val now = System.currentTimeMillis()
        when {
            item.status != STATUS_PENDING -> null
            now > item.expiresAt -> {
                dao.updateStatus(opId, STATUS_EXPIRED)
                null
            }
            else -> {
                val updated = item.copy(status = STATUS_CONFIRMED, confirmedAt = now)
                dao.upsert(updated)
                updated
            }
        }
    }

    suspend fun finish(
        opId: String,
        success: Boolean,
        resultJson: String? = null,
        error: String? = null,
    ) = withContext(Dispatchers.IO) {
        val item = dao.byId(opId) ?: return@withContext
        dao.upsert(
            item.copy(
                status = if (success) STATUS_EXECUTED else STATUS_FAILED,
                executedAt = System.currentTimeMillis(),
                resultJson = resultJson,
                error = error,
            ),
        )
    }

    suspend fun cancel(opId: String) = withContext(Dispatchers.IO) {
        val item = dao.byId(opId) ?: return@withContext
        if (item.status == STATUS_PENDING) dao.updateStatus(opId, STATUS_CANCELLED)
    }

    suspend fun recent(limit: Int = 20): List<PendingActionEntity> =
        withContext(Dispatchers.IO) { dao.recent(limit) }

    suspend fun expireStale(): Int = withContext(Dispatchers.IO) { expireStaleInternal() }

    suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }

    private suspend fun expireStaleInternal(): Int {
        val now = System.currentTimeMillis()
        var expired = 0
        dao.byStatus(STATUS_PENDING, 100).forEach { item ->
            if (now > item.expiresAt) {
                dao.updateStatus(item.opId, STATUS_EXPIRED)
                expired++
            }
        }
        dao.purgeFinishedBefore(now - RETAIN_MS)
        return expired
    }

    companion object {
        /** 草案有效期 5 分钟（方案 10.2） */
        const val DEFAULT_TTL_MS = 5 * 60_000L
        private const val RETAIN_MS = 3L * 24 * 60 * 60 * 1000

        const val STATUS_PENDING = "PENDING"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_EXECUTED = "EXECUTED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_EXPIRED = "EXPIRED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
