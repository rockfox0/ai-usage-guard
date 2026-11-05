package com.aid.usageguard.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aid.usageguard.MainActivity
import com.aid.usageguard.R
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.domain.model.BehaviorEventModel
import com.aid.usageguard.domain.model.BehaviorEventType
import com.aid.usageguard.domain.model.BehaviorSeverity
import com.aid.usageguard.domain.score.UsageScore
import com.aid.usageguard.domain.score.WeekScore
import java.util.Calendar

/**
 * 通知统一出口。
 *
 * 约定：
 * - 4 个渠道：`daily_reminder` / `goal_exceeded` / `anomaly` / `ai_report`；
 * - 每个用途一个**固定通知 id**，重复通知只会更新同一条，不会在通知栏里堆积；
 * - 叠加一层按用途的最小间隔（[_shouldPost]）与 [AppSettings] 开关判断；
 * - Android 13+ 未授予 POST_NOTIFICATIONS 时**静默跳过**并返回 false，任何调用方都不该因此崩溃。
 *
 * 所有 notifyXxx 都是 `suspend`，因为要读 [SettingsRepository]（DataStore）。
 * 传 `settings` 可跳过读 DataStore（Worker 里已经读过一次时更省）。
 */
object NotificationHelper {

    const val CHANNEL_DAILY_REMINDER = "daily_reminder"
    const val CHANNEL_GOAL_EXCEEDED = "goal_exceeded"
    const val CHANNEL_ANOMALY = "anomaly"
    const val CHANNEL_AI_REPORT = "ai_report"
    const val CHANNEL_MONITOR = "monitor_alert"

    /** 固定通知 id：同一用途反复通知只会替换，不会堆积 */
    const val ID_DAILY_REMINDER = 1001
    const val ID_GOAL_EXCEEDED = 1002
    const val ID_ANOMALY = 1003
    const val ID_REPORT_DAILY = 1004
    const val ID_MONITOR = 1005
    const val ID_REPORT_WEEKLY = 1005

    private const val PREFS = "usageguard_notify_state"
    private const val KEY_DAILY_REMINDER = "last_daily_reminder_at"
    private const val KEY_GOAL_EXCEEDED = "last_goal_exceeded_at"
    private const val KEY_ANOMALY = "last_anomaly_at"
    private const val KEY_REPORT_DAILY = "last_report_daily_at"
    private const val KEY_REPORT_WEEKLY = "last_report_weekly_at"

    private const val MIN_INTERVAL_DAILY_REMINDER = 12L * 60L * 60L * 1000L
    private const val MIN_INTERVAL_GOAL_EXCEEDED = 6L * 60L * 60L * 1000L
    private const val MIN_INTERVAL_ANOMALY = 10L * 60L * 1000L
    private const val MIN_INTERVAL_REPORT = 10L * 60L * 1000L

    // ------------------------------------------------------------------ 渠道（v1.9 Phase 5 增加自定义监控）


    /** 创建/补齐所有通知渠道（幂等；App.onCreate 里也会建，这里保证 Worker 场景同样可用） */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            Channel(CHANNEL_DAILY_REMINDER, "每日使用提醒", NotificationManager.IMPORTANCE_DEFAULT),
            Channel(CHANNEL_GOAL_EXCEEDED, "超过使用目标", NotificationManager.IMPORTANCE_DEFAULT),
            Channel(CHANNEL_ANOMALY, "异常使用提醒", NotificationManager.IMPORTANCE_HIGH),
            Channel(CHANNEL_AI_REPORT, "AI 使用报告", NotificationManager.IMPORTANCE_LOW),
            Channel(CHANNEL_MONITOR, "自定义监控提醒", NotificationManager.IMPORTANCE_DEFAULT),
        )
        channels.forEach { item ->
            val channel = NotificationChannel(item.id, item.name, item.importance)
            channel.description = item.description
            manager.createNotificationChannel(channel)
        }
    }

    private class Channel(
        val id: String,
        val name: String,
        val importance: Int,
    ) {
        val description: String
            get() = when (id) {
                NotificationHelper.CHANNEL_DAILY_REMINDER -> "每天的使用时长小结与提醒"
                NotificationHelper.CHANNEL_GOAL_EXCEEDED -> "当天使用时长超出你设定的目标时提醒"
                NotificationHelper.CHANNEL_ANOMALY -> "检测到异常使用模式（用量激增、深夜使用、连续过长等）"
                NotificationHelper.CHANNEL_MONITOR -> "你自己设置的监控规则被触发时提醒"
                else -> "AI 生成的日报 / 周报"
            }
    }

    // ------------------------------------------------------------------ 权限 / 开关

    /** Android 13+ 的运行时通知权限 + 系统级通知开关 */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(true)
    }

    private suspend fun settingsOf(provided: AppSettings?): AppSettings =
        provided ?: runCatching { Container.settings.current() }.getOrDefault(AppSettings.DEFAULT)

    /** 免打扰时段（默认 23:00-07:00），摘要类通知在里面直接跳过 */
    private fun inQuietHours(cfg: AppSettings, now: Long = System.currentTimeMillis()): Boolean {
        val start = cfg.quietHoursStart.coerceIn(0, 23)
        val end = cfg.quietHoursEnd.coerceIn(0, 23)
        if (start == end) return false
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    // ------------------------------------------------------------------ 通知入口

    /**
     * 每日使用提醒（不会在免打扰时段打扰）。
     *
     * @param score 今日评分；传 null 时会自己取一次（`Container.score.todayScore()`），
     *              文案首行统一为「今天 62 分 · 有点上头」这种一句式评分。
     */
    suspend fun notifyDailyReminder(
        context: Context,
        totalMinutes: Long,
        targetMinutes: Int,
        settings: AppSettings? = null,
        score: UsageScore? = null,
    ): Boolean {
        val cfg = settingsOf(settings)
        if (!cfg.notificationEnabled || !cfg.notifyDailyReminder) return false
        if (!canNotify(context)) return false
        if (inQuietHours(cfg)) return false
        if (!_shouldPost(context, KEY_DAILY_REMINDER, MIN_INTERVAL_DAILY_REMINDER)) return false

        val target = targetMinutes.toLong()
        val scoreLine = resolveTodayScore(score)?.let { "今天 ${it.score} 分 · ${it.headline}" }
        val timeLine = if (target > 0 && totalMinutes > target) {
            "今天已使用 ${_minutes(totalMinutes)}，超出目标 ${_minutes(totalMinutes - target)}（目标 ${_minutes(target)}）。"
        } else if (target > 0) {
            "今天已使用 ${_minutes(totalMinutes)}，距离目标 ${_minutes(target)} 还剩 ${_minutes(target - totalMinutes)}。"
        } else {
            "今天已使用 ${_minutes(totalMinutes)}。"
        }
        val title = "今日使用提醒"
        val body = buildString {
            if (scoreLine != null) append(scoreLine).append('\n')
            append(timeLine)
        }
        return _post(context, CHANNEL_DAILY_REMINDER, ID_DAILY_REMINDER, title, body)
    }

    /** 超出每日目标（实时提醒，不受免打扰限制，但有 6 小时间隔） */
    suspend fun notifyGoalExceeded(
        context: Context,
        totalMinutes: Long,
        targetMinutes: Int,
        settings: AppSettings? = null,
    ): Boolean {
        val cfg = settingsOf(settings)
        if (!cfg.notificationEnabled || !cfg.notifyGoalExceeded) return false
        if (!canNotify(context)) return false
        val target = targetMinutes.toLong()
        if (target <= 0L || totalMinutes <= target) return false
        if (!_shouldPost(context, KEY_GOAL_EXCEEDED, MIN_INTERVAL_GOAL_EXCEEDED)) return false

        val over = totalMinutes - target
        val title = "已超出今日使用目标"
        val body = "今天已使用 ${_minutes(totalMinutes)}，超出目标 ${_minutes(over)}（目标 ${_minutes(target)}）。" +
            "现在开始，试着放下手机休息一会儿。"
        return _post(context, CHANNEL_GOAL_EXCEEDED, ID_GOAL_EXCEEDED, title, body)
    }

    /** 本地行为分析产出的异常事件（最多展开 3 条） */
    /**
     * 自定义监控触发通知（v1.9 Phase 5）：本地规则引擎判定后调用，不经过模型。
     * 同一规则同一天只触发一次，由 MonitorEngine 去重。
     */
    suspend fun notifyMonitorTriggers(
        context: Context,
        triggers: List<com.aid.usageguard.domain.monitor.MonitorTriggerResult>,
        settings: AppSettings? = null,
    ): Boolean {
        if (triggers.isEmpty()) return false
        val cfg = settingsOf(settings)
        if (!cfg.notificationEnabled) return false
        if (!canNotify(context)) return false
        val title = if (triggers.size == 1) "监控提醒：${triggers[0].ruleName}" else "有 ${triggers.size} 条监控规则被触发"
        val body = triggers.take(3).joinToString("\n") { "· ${it.message}" }
        return _post(context, CHANNEL_MONITOR, ID_MONITOR, title, body)
    }

    suspend fun notifyAnomaly(
        context: Context,
        events: List<BehaviorEventModel>,
        settings: AppSettings? = null,
    ): Boolean {
        if (events.isEmpty()) return false
        val cfg = settingsOf(settings)
        if (!cfg.notificationEnabled || !cfg.notifyAnomaly) return false
        if (!canNotify(context)) return false
        if (!_shouldPost(context, KEY_ANOMALY, MIN_INTERVAL_ANOMALY)) return false

        val sorted = events.sortedBy { _severityOrder(it.severity) }
        val shown = sorted.take(3)
        val title = if (sorted.any { it.severity == BehaviorSeverity.HIGH }) {
            "发现 ${sorted.size} 条值得注意的使用异常"
        } else {
            "发现 ${sorted.size} 条使用异常"
        }
        val body = shown.joinToString("\n") { "· ${it.description}" }
        return _post(context, CHANNEL_ANOMALY, ID_ANOMALY, title, body)
    }

    /**
     * 报告通知（日报 / 周报共用）。
     * @param id 用 [ID_REPORT_DAILY] / [ID_REPORT_WEEKLY]，保证同类型报告只留一条通知。
     * @param score 日报用的今日评分（日报通知首行「今天 62 分 · 有点上头」）
     * @param week 周报用的本周总结（周报通知首行「本周平均 68 分 · 节奏不错」）
     *             两者都可为 null，为 null 时按 id 自动取一次。
     */
    suspend fun notifyReport(
        context: Context,
        title: String,
        content: String,
        id: Int = ID_REPORT_DAILY,
        settings: AppSettings? = null,
        score: UsageScore? = null,
        week: WeekScore? = null,
    ): Boolean {
        val cfg = settingsOf(settings)
        if (!cfg.notificationEnabled) return false
        val enabled = if (id == ID_REPORT_WEEKLY) cfg.notifyWeeklySummary else cfg.notifyDailySummary
        if (!enabled) return false
        if (!canNotify(context)) return false
        if (inQuietHours(cfg)) return false
        val key = if (id == ID_REPORT_WEEKLY) KEY_REPORT_WEEKLY else KEY_REPORT_DAILY
        if (!_shouldPost(context, key, MIN_INTERVAL_REPORT)) return false

        // 折叠态只显示第一行 → 把评分摘要放最前面，展开后才是完整报告
        val brief = if (id == ID_REPORT_WEEKLY) {
            resolveWeek(week)?.let { "本周平均 ${it.averageScore} 分 · ${it.headline}" }
        } else {
            resolveTodayScore(score)?.let { "今天 ${it.score} 分 · ${it.headline}" }
        }
        val summary = buildString {
            if (!brief.isNullOrBlank()) append(brief).append('\n')
            append(content.trim().replace("\n\n", "\n"))
        }
        return _post(context, CHANNEL_AI_REPORT, id, title, summary)
    }

    // ------------------------------------------------------------------ 内部实现

    /** 通知文案用的今日评分；调用方没给就现取一次（容器未初始化时返回 null） */
    private suspend fun resolveTodayScore(provided: UsageScore?): UsageScore? =
        provided ?: runCatching { Container.score.todayScore() }.getOrNull()

    /** 通知文案用的本周总结 */
    private suspend fun resolveWeek(provided: WeekScore?): WeekScore? =
        provided ?: runCatching { Container.score.week() }.getOrNull()

    private fun _severityOrder(severity: BehaviorSeverity): Int = when (severity) {
        BehaviorSeverity.HIGH -> 0
        BehaviorSeverity.MEDIUM -> 1
        BehaviorSeverity.LOW -> 2
    }

    private fun _minutes(minutes: Long): String = when {
        minutes <= 0L -> "0 分钟"
        minutes < 60L -> "$minutes 分钟"
        minutes % 60L == 0L -> "${minutes / 60L} 小时"
        else -> "${minutes / 60L} 小时 ${minutes % 60L} 分钟"
    }

    /** 最小间隔去重：同一用途短时间内只发一次（进程重启后依然有效） */
    private fun _shouldPost(context: Context, key: String, minIntervalMs: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(key, 0L)
        if (last > 0L && now - last < minIntervalMs) return false
        prefs.edit().putLong(key, now).apply()
        return true
    }

    private fun _contentIntent(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @SuppressLint("MissingPermission")
    private fun _post(
        context: Context,
        channelId: String,
        id: Int,
        title: String,
        body: String,
    ): Boolean {
        // 权限/开关不满足时静默跳过，绝不让后台任务因此崩溃
        if (!canNotify(context)) return false
        ensureChannels(context)
        val text = body.ifBlank { title }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(
                if (channelId == CHANNEL_ANOMALY) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(_contentIntent(context, id))

        return runCatching {
            NotificationManagerCompat.from(context).notify(id, builder.build())
            true
        }.getOrDefault(false)
    }

    /** 供 UI/测试查看：当前是否已有某条通知（无副作用） */
    fun isNotificationAllowed(context: Context): Boolean = canNotify(context)

    /** 事件类型 -> 人类可读标签（UI 与通知共用同一套文案） */
    fun typeLabel(type: BehaviorEventType): String = when (type) {
        BehaviorEventType.USAGE_INCREASE -> "用量激增"
        BehaviorEventType.LONG_SESSION -> "连续使用过久"
        BehaviorEventType.LATE_NIGHT -> "深夜使用"
        BehaviorEventType.FREQUENT_LAUNCH -> "频繁打开"
        BehaviorEventType.OVER_TOTAL_TARGET -> "超出每日目标"
        BehaviorEventType.MULTI_DAY_GROWTH -> "连续多日增长"
    }

    /** 严重级 -> 人类可读标签 */
    fun severityLabel(severity: BehaviorSeverity): String = when (severity) {
        BehaviorSeverity.HIGH -> "严重"
        BehaviorSeverity.MEDIUM -> "注意"
        BehaviorSeverity.LOW -> "轻微"
    }
}
