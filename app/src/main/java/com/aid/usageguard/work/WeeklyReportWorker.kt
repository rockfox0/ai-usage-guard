package com.aid.usageguard.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aid.usageguard.data.Container
import com.aid.usageguard.notify.NotificationHelper

/** 周报生成（每周日 21:00 触发），流程与 [DailyReportWorker] 相同 */
class WeeklyReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            WorkerSupport.prepare(applicationContext)
            val cfg = WorkerSupport.settingsOrDefaults()
            // 落库今日评分 + 取本周总结（本地周报文本会引用这份总结）
            WorkerSupport.persistScore()
            val week = WorkerSupport.currentWeek()
            val report = Container.reports.generateWeekly()
            Log.i(
                TAG,
                "weekly report generated: source=${report.source} chars=${report.content.length} " +
                    "avgScore=${week?.averageScore ?: "-"}",
            )

            if (cfg.notificationEnabled && cfg.notifyWeeklySummary) {
                NotificationHelper.notifyReport(
                    context = applicationContext,
                    title = "本周使用周报已生成",
                    content = report.content,
                    id = NotificationHelper.ID_REPORT_WEEKLY,
                    settings = cfg,
                    week = week,
                )
            }
            AgentScheduler.ensureScheduled(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "weekly report failed (attempt=$runAttemptCount)", t)
            WorkerSupport.retryOrGiveUp(runAttemptCount)
        }
    }

    companion object {
        private const val TAG = "WeeklyReportWorker"
    }
}
