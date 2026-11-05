package com.aid.usageguard.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aid.usageguard.agent.proactive.ProactiveAgent
import com.aid.usageguard.data.Container
import com.aid.usageguard.notify.NotificationHelper

/**
 * 只做行为分析 + 通知（周期性，默认 60 分钟一次）。
 *
 * 与 [UsageSyncWorker] 的分工：这里**不**重新采集使用数据，
 * 只基于已落库的数据重新跑规则，适合「数据刚同步完但上次分析被冷却拦掉」的场景。
 */
class AnomalyCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            WorkerSupport.prepare(applicationContext)
            val events = WorkerSupport.analyzeAndNotify(applicationContext)
            Log.i(TAG, "anomaly check: ${events.size} new event(s)")

            // v1.9 Phase 6：本地候选 → 最多一次模型分析 → 主动洞察（每天最多一条）
            val insight = runCatching {
                ProactiveAgent(
                    usage = Container.usage,
                    insights = Container.insights,
                    memory = Container.memory,
                    settings = Container.settings,
                ).maybeGenerate()
            }.getOrNull()
            if (insight != null) {
                runCatching { NotificationHelper.notifyProactiveInsight(applicationContext, insight) }
                Log.i(TAG, "proactive insight: ${insight.title}")
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "anomaly check failed (attempt=$runAttemptCount)", t)
            WorkerSupport.retryOrGiveUp(runAttemptCount)
        }
    }

    companion object {
        private const val TAG = "AnomalyCheckWorker"
    }
}
