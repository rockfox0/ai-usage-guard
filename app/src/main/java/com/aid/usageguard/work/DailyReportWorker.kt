package com.aid.usageguard.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aid.usageguard.data.Container
import com.aid.usageguard.notify.NotificationHelper

/**
 * 日报生成（每天 22:30 触发）。
 *
 * 生成逻辑在 [com.aid.usageguard.data.repo.ReportRepository]：先尝试 AI Agent，
 * 未配置 / 失败时降级为本地规则报告，所以这个任务不需要网络约束。
 */
class DailyReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            WorkerSupport.prepare(applicationContext)
            val cfg = WorkerSupport.settingsOrDefaults()
            // 先把今天的评分落库，再生成日报（本地日报文本会引用这份评分）
            val score = WorkerSupport.persistScore()
            val report = Container.reports.generateDaily()
            Log.i(
                TAG,
                "daily report generated: source=${report.source} chars=${report.content.length} " +
                    "score=${score?.score ?: "-"}",
            )

            if (cfg.notificationEnabled && cfg.notifyDailySummary) {
                NotificationHelper.notifyReport(
                    context = applicationContext,
                    title = "今日使用日报已生成",
                    content = report.content,
                    id = NotificationHelper.ID_REPORT_DAILY,
                    settings = cfg,
                    score = score,
                )
            }
            // 周期性任务自己会续期；这里再幂等排一次，覆盖「清数据/换设备后周期任务丢失」的情况
            AgentScheduler.ensureScheduled(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "daily report failed (attempt=$runAttemptCount)", t)
            WorkerSupport.retryOrGiveUp(runAttemptCount)
        }
    }

    companion object {
        private const val TAG = "DailyReportWorker"
    }
}
