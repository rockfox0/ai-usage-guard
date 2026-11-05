package com.aid.usageguard.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aid.usageguard.data.Container

/**
 * 使用数据同步 + 行为分析（周期性，默认 30 分钟一次）。
 *
 * 流程：`UsageStatsManager` 增量采集 → 落库 → 本地行为分析 → 异常通知。
 * 没拿到「使用情况访问权限」时直接跳过（返回 success，不算失败，避免重试风暴）。
 */
class UsageSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            WorkerSupport.prepare(applicationContext)
            if (!Container.usage.hasUsageAccess()) {
                Log.i(TAG, "skip sync: 未授予使用情况访问权限")
                return Result.success()
            }
            val ingest = Container.usage.refresh()
            Log.i(
                TAG,
                "sync done: sessions=${ingest.sessions} days=${ingest.daysTouched} " +
                    "window=${(ingest.windowTo - ingest.windowFrom) / 60_000}min",
            )
            // 落库今日评分（daily_score），供报告 / 周总结 / 通知复用
            val score = WorkerSupport.persistScore()
            Log.i(TAG, "score persisted: ${score?.let { "${it.score} 分/${it.grade} ${it.headline}" } ?: "未生成"}")
            val events = WorkerSupport.analyzeAndNotify(applicationContext)
            Log.i(TAG, "behavior analysis: ${events.size} new event(s)")
            // 傍晚那一轮顺带发每日提醒（文案带评分）；其它时段直接返回
            WorkerSupport.maybeNotifyDailyReminder(applicationContext, score)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "sync failed (attempt=$runAttemptCount)", t)
            WorkerSupport.retryOrGiveUp(runAttemptCount)
        }
    }

    companion object {
        private const val TAG = "UsageSyncWorker"
    }
}
