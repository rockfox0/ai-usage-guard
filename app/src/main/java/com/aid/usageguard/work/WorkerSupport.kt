package com.aid.usageguard.work

import android.content.Context
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.domain.behavior.BehaviorAnalyzer
import com.aid.usageguard.domain.model.BehaviorEventModel
import com.aid.usageguard.domain.model.BehaviorEventType
import com.aid.usageguard.domain.score.UsageScore
import com.aid.usageguard.domain.score.WeekScore
import com.aid.usageguard.notify.NotificationHelper
import java.util.Calendar

/**
 * Worker 共用逻辑：容器初始化、分析 + 通知、重试策略。
 *
 * WorkManager 起的进程里 `App.onCreate` 一定先跑过，但为了不依赖这个隐含前提
 * （以及日后把 Worker 跑在独立进程），这里统一做一次幂等的 [Container.init]。
 */
internal object WorkerSupport {

    /** 连续失败几次后放弃本次任务（周期性任务下一轮还会再来，不必无限重试） */
    const val MAX_ATTEMPTS = 3

    /** 每日使用提醒的发送窗口（傍晚，避开 22:30 的日报） */
    private val DAILY_REMINDER_HOURS = 19..21

    /** 幂等准备：初始化容器 + 保证真实分析器已接线 */
    fun prepare(context: Context) {
        Container.init(context.applicationContext)
        // 兜底：AppWiring 还没接线时也不至于永远分析出空结果
        runCatching { BehaviorAnalyzer.installIfNeeded() }
    }

    suspend fun settingsOrDefaults(): AppSettings =
        runCatching { Container.settings.current() }.getOrDefault(AppSettings.DEFAULT)

    /**
     * 计算并落库「今天」的评分（写入 daily_score，upsert 幂等）。
     * 每次使用数据同步后调用，保证每天结束时表里有当日记录；
     * 失败只记日志，不影响同步本身。
     */
    suspend fun persistScore(): UsageScore? =
        runCatching { Container.score.persist() }.getOrNull()

    /** 最近一周总结（周报通知文案用；失败返回 null，通知自动退回纯文本） */
    suspend fun currentWeek(): WeekScore? =
        runCatching { Container.score.week() }.getOrNull()

    /**
     * 傍晚（19:00–21:59）的同步顺带发一条「每日使用提醒」，文案首行带今日评分。
     * 通知开关、免打扰、12 小时间去重都在 [NotificationHelper] 内部判断。
     */
    suspend fun maybeNotifyDailyReminder(context: Context, score: UsageScore?) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour !in DAILY_REMINDER_HOURS) return
        val cfg = settingsOrDefaults()
        if (!cfg.notificationEnabled || !cfg.notifyDailyReminder) return
        val total = runCatching { Container.usage.todayTotalMinutes() }.getOrDefault(0L)
        runCatching {
            NotificationHelper.notifyDailyReminder(context, total, cfg.dailyTargetMinutes, cfg, score)
        }
    }

    /**
     * 跑一次行为分析，并按设置发通知。
     *
     * @return 本次新落库的事件（被冷却 / 去重拦下的不算）
     */
    suspend fun analyzeAndNotify(context: Context): List<BehaviorEventModel> {
        val cfg = settingsOrDefaults()
        val events = runCatching { Container.behavior.runAnalysis() }.getOrDefault(emptyList())

        if (events.isNotEmpty() && cfg.notificationEnabled && cfg.notifyAnomaly) {
            runCatching { NotificationHelper.notifyAnomaly(context, events, cfg) }
        }

        // 分析事件可能已被 6 小时冷却拦掉，超目标时用专用通知兜一次（内部同样有 6 小时间隔）
        val overTargetAlreadyReported = events.any { it.eventType == BehaviorEventType.OVER_TOTAL_TARGET }
        if (cfg.notificationEnabled && cfg.notifyGoalExceeded && !overTargetAlreadyReported) {
            val total = runCatching { Container.usage.todayTotalMinutes() }.getOrDefault(0L)
            if (total > cfg.dailyTargetMinutes.toLong()) {
                runCatching { NotificationHelper.notifyGoalExceeded(context, total, cfg.dailyTargetMinutes, cfg) }
            }
        }
        return events
    }

    /** 统一的重试策略：失败次数用尽后返回 success，避免周期性任务一直卡在 backoff 里 */
    fun retryOrGiveUp(attempt: Int): androidx.work.ListenableWorker.Result =
        if (attempt + 1 >= MAX_ATTEMPTS) {
            androidx.work.ListenableWorker.Result.success()
        } else {
            androidx.work.ListenableWorker.Result.retry()
        }
}
