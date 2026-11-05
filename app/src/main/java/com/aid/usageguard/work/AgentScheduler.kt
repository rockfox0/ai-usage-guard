package com.aid.usageguard.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 主动管理的排期入口（WorkManager）。
 *
 * | 任务 | 类型 | 频率 | 唯一名 | 冲突策略 |
 * |---|---|---|---|---|
 * | [UsageSyncWorker] | Periodic | 30 分钟 | [UNIQUE_SYNC] | KEEP（不重置计时） |
 * | [AnomalyCheckWorker] | Periodic | 60 分钟 | [UNIQUE_ANALYSIS] | KEEP |
 * | [DailyReportWorker] | Periodic(24h) | 每天 22:30（首启按 [calculateInitialDelay]） | [UNIQUE_DAILY_REPORT] | UPDATE（跟随代码里的时间点） |
 * | [WeeklyReportWorker] | Periodic(7d) | 每周日 21:00 | [UNIQUE_WEEKLY_REPORT] | UPDATE |
 *
 * 语义说明：
 * - **KEEP**：已存在就保留原排期。同步/分析如果每次冷启都用 REPLACE，
 *   用户频繁打开 App 会把「30 分钟一次」不断往后推，实际上永远不触发；
 * - **UPDATE**：日报/周报的触发时刻是代码里的常量，允许后续版本用新值覆盖旧排期。
 *
 * 调用点：`Application.onCreate` 里调用一次 [ensureScheduled] 即可（幂等）。
 */
object AgentScheduler {

    const val UNIQUE_SYNC = "usage_sync_periodic"
    const val UNIQUE_ANALYSIS = "behavior_analysis_periodic"
    const val UNIQUE_DAILY_REPORT = "daily_report_periodic"
    const val UNIQUE_WEEKLY_REPORT = "weekly_report_periodic"

    /** 使用数据同步周期（分钟） */
    const val SYNC_INTERVAL_MINUTES = 30L

    /** 行为分析周期（分钟） */
    const val ANALYSIS_INTERVAL_MINUTES = 60L

    /** 日报触发时刻：每天 22:30 */
    const val DAILY_REPORT_HOUR = 22
    const val DAILY_REPORT_MINUTE = 30

    /** 周报触发时刻：每周日 21:00 */
    const val WEEKLY_REPORT_HOUR = 21
    const val WEEKLY_REPORT_MINUTE = 0
    const val WEEKLY_REPORT_DAY_OF_WEEK = Calendar.SUNDAY

    private const val TAG = "AgentScheduler"
    private const val TAG_SYNC = "usage_sync"
    private const val TAG_ANALYSIS = "behavior_analysis"
    private const val TAG_DAILY_REPORT = "daily_report"
    private const val TAG_WEEKLY_REPORT = "weekly_report"

    /** WorkManager 要求的最小退避时间 10s，这里用 15 分钟指数退避 */
    private const val BACKOFF_MINUTES = 15L

    /** 首次延迟下限：避免任务和 App 冷启动抢资源 */
    private const val MIN_INITIAL_DELAY_MS = 60_000L

    /**
     * 幂等地把所有后台任务排上（App 启动 / 进入报告页 / 报告 Worker 结束后都可安全调用）。
     *
     * 内部吞掉异常：排期失败不应该让 App 启动崩溃（例如个别 ROM 上 WorkManager 初始化异常）。
     */
    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        runCatching {
            WorkerSupport.prepare(app)
            val workManager = WorkManager.getInstance(app)

            // 1) 使用数据同步：30 分钟一次
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_SYNC,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest(),
            )
            // 2) 行为分析：60 分钟一次（数据同步完再分析，所以首次延迟更长一点）
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_ANALYSIS,
                ExistingPeriodicWorkPolicy.KEEP,
                analysisRequest(),
            )
            // 3) 日报：每天 22:30
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_DAILY_REPORT,
                ExistingPeriodicWorkPolicy.UPDATE,
                dailyReportRequest(),
            )
            // 4) 周报：每周日 21:00
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WEEKLY_REPORT,
                ExistingPeriodicWorkPolicy.UPDATE,
                weeklyReportRequest(),
            )
            Log.i(
                TAG,
                "scheduled: sync=${SYNC_INTERVAL_MINUTES}min analysis=${ANALYSIS_INTERVAL_MINUTES}min " +
                    "daily=${DAILY_REPORT_HOUR}:${DAILY_REPORT_MINUTE} weekly=Sun ${WEEKLY_REPORT_HOUR}:00",
            )
        }.onFailure { Log.w(TAG, "ensureScheduled failed", it) }
    }

    /**
     * 计算「距离下一个 hour:minute（可选星期几）」的延迟毫秒数，用于周期任务的首次延迟。
     *
     * @param dayOfWeek [Calendar] 的星期常量，例如 [Calendar.SUNDAY]=1；null 表示每天
     */
    fun calculateInitialDelay(hourOfDay: Int, minuteOfHour: Int, dayOfWeek: Int? = null): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
            set(Calendar.MINUTE, minuteOfHour.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (dayOfWeek != null) {
            var diff = dayOfWeek - target.get(Calendar.DAY_OF_WEEK)
            if (diff < 0) diff += 7
            target.add(Calendar.DAY_OF_MONTH, diff)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            // 今天（本周）的触发点已过 → 顺延一个周期
            target.add(Calendar.DAY_OF_MONTH, if (dayOfWeek != null) 7 else 1)
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(MIN_INITIAL_DELAY_MS)
    }

    /** 取消全部后台任务（调试 / 用户关闭主动管理时用） */
    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        runCatching {
            workManager.cancelUniqueWork(UNIQUE_SYNC)
            workManager.cancelUniqueWork(UNIQUE_ANALYSIS)
            workManager.cancelUniqueWork(UNIQUE_DAILY_REPORT)
            workManager.cancelUniqueWork(UNIQUE_WEEKLY_REPORT)
        }.onFailure { Log.w(TAG, "cancelAll failed", it) }
    }

    // ------------------------------------------------------------------ 具体请求

    private fun syncRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<UsageSyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(lightConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            // 首启 2 分钟后先跑一次，让页面/通知尽早有数据
            .setInitialDelay(2, TimeUnit.MINUTES)
            .addTag(TAG_SYNC)
            .build()

    private fun analysisRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<AnomalyCheckWorker>(ANALYSIS_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(lightConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .setInitialDelay(5, TimeUnit.MINUTES)
            .addTag(TAG_ANALYSIS)
            .build()

    private fun dailyReportRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<DailyReportWorker>(24, TimeUnit.HOURS)
            .setConstraints(lightConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .setInitialDelay(
                calculateInitialDelay(DAILY_REPORT_HOUR, DAILY_REPORT_MINUTE),
                TimeUnit.MILLISECONDS,
            )
            .addTag(TAG_DAILY_REPORT)
            .build()

    private fun weeklyReportRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
            .setConstraints(lightConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .setInitialDelay(
                calculateInitialDelay(
                    WEEKLY_REPORT_HOUR,
                    WEEKLY_REPORT_MINUTE,
                    WEEKLY_REPORT_DAY_OF_WEEK,
                ),
                TimeUnit.MILLISECONDS,
            )
            .addTag(TAG_WEEKLY_REPORT)
            .build()

    /**
     * 轻量约束：数据都是本地读，不需要网络；只要电量不是过低即可，
     * 避免在低电量时和系统省电策略抢资源。
     */
    private fun lightConstraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()
}
