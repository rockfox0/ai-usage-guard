package com.aid.usageguard.domain.behavior

import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.model.AppDetailModel
import com.aid.usageguard.domain.model.AppUsageSummary
import com.aid.usageguard.domain.model.BehaviorEventModel
import com.aid.usageguard.domain.model.BehaviorEventType
import com.aid.usageguard.domain.model.BehaviorSeverity
import com.aid.usageguard.domain.model.SessionSlice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 本地行为分析引擎（纯规则，不调用任何 LLM）。
 *
 * 设计目标：
 * - **有个人基线**：所有「变多」的判断都拿用户自己的历史均值做参照，而不是固定绝对值；
 * - **不误报**：单次会话必须 > 45 分钟才算长、频繁打开必须有「1 小时内 N 次」的会话级证据、
 *   连续增长必须连续 3 天且每天都有实质使用，宁缺毋滥；
 * - **只出候选事件**：去重 / 冷却 / 落库由 [com.aid.usageguard.data.repo.BehaviorRepository] 负责，
 *   同一个 (类型, 应用, 日期) 只会被保留一条。
 *
 * 阈值全部放在 [companion] 的 `var` 上，可在运行时按用户习惯或测试覆盖。
 */
class BehaviorAnalyzer(
    private val usage: UsageRepository,
    private val settings: SettingsRepository,
) : BehaviorAnalyzerContract {

    override suspend fun analyze(date: String): List<BehaviorEventModel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cfg = settings.current()
        val isToday = date == UsageEventSource.todayDate()
        // 历史日期的事件时间戳落在当天末尾，避免冷却窗口把「昨天的检测」算成刚刚发生
        val stamp = if (isToday) now else UsageEventSource.startOfDay(date) + UsageEventSource.DAY_MS - 1L

        val today = runCatching { usage.rankingForDate(date, rankingLimit) }.getOrDefault(emptyList())
        if (today.isEmpty()) return@withContext emptyList()

        // 最近 historyDays 天的逐 App 排行（不含 date 当天的数据），用于构建个人基线
        val history: List<List<AppUsageSummary>> = (1..historyDays).map { offset ->
            runCatching {
                usage.rankingForDate(UsageEventSource.addDays(date, -offset), rankingLimit)
            }.getOrDefault(emptyList())
        }
        val week = runCatching { usage.weekTotals(date) }.getOrDefault(emptyList())
        val dayTotal = week.firstOrNull { it.date == date }?.minutes ?: today.sumOf { it.minutes }
        val sessions = runCatching { usage.sessionsOf(date) }.getOrDefault(emptyList())
        // 会话明细（含 packageName）只有 appDetail 能给出，且它锚定「今天」，所以只在分析今天时取
        val details = if (isToday) detailsOf(today) else emptyMap()

        val events = ArrayList<BehaviorEventModel>()
        events += detectOverTotalTarget(date, stamp, cfg, dayTotal)
        events += detectUsageIncrease(date, stamp, today, history)
        events += detectMultiDayGrowth(date, stamp, today, history)
        events += detectLongSession(date, stamp, sessions, details)
        events += detectLateNight(date, stamp, sessions, details)
        events += detectFrequentLaunch(date, stamp, today, details)

        // 严重级高的排前面，方便 UI / 通知只取前几条
        events.sortedBy { severityOrder(it.severity) }
    }

    // ---------------------------------------------------------------- 1) 超每日目标

    private fun detectOverTotalTarget(
        date: String,
        stamp: Long,
        cfg: AppSettings,
        dayTotal: Long,
    ): List<BehaviorEventModel> {
        val target = cfg.dailyTargetMinutes.toLong()
        if (target <= 0L || dayTotal <= target) return emptyList()
        val over = dayTotal - target
        val ratio = dayTotal.toDouble() / target.toDouble()
        val severity = when {
            ratio >= overTargetHighRatio -> BehaviorSeverity.HIGH
            over >= overTargetAlertMinutes -> BehaviorSeverity.MEDIUM
            else -> BehaviorSeverity.LOW
        }
        val dayWord = if (date == UsageEventSource.todayDate()) "今天" else "${UsageEventSource.dayLabel(date)} 全天"
        val description = buildString {
            append(dayWord).append("已使用 ").append(minutesText(dayTotal))
            append("，超出每日目标（").append(minutesText(target)).append("）")
            append(minutesText(over)).append("，已达目标的 ")
            append(ratioText(ratio)).append(" 倍。")
        }
        return listOf(
            event(BehaviorEventType.OVER_TOTAL_TARGET, null, severity, description, stamp, date),
        )
    }

    // ---------------------------------------------------------------- 2) 用量激增

    /** 今日某 App 明显高于「最近 N 天自身均值」 */
    private fun detectUsageIncrease(
        date: String,
        stamp: Long,
        today: List<AppUsageSummary>,
        history: List<List<AppUsageSummary>>,
    ): List<BehaviorEventModel> {
        val events = ArrayList<BehaviorEventModel>()
        for (item in today) {
            if (item.minutes < usageIncreaseMinTodayMinutes) continue
            val past = history
                .mapNotNull { day -> day.firstOrNull { it.packageName == item.packageName }?.minutes }
                .filter { it > 0L }
            // 基线天数不足（新装应用 / 偶尔才用）→ 没有可比性，直接放过，避免首日误报
            if (past.size < baselineMinDays) continue
            val average = past.average()
            // 基线本身很小（日均几分钟）时倍数没有意义
            if (average < baselineMinAverageMinutes) continue
            val delta = item.minutes - average.toLong()
            if (delta < usageIncreaseMinDeltaMinutes) continue
            val ratio = item.minutes.toDouble() / average
            if (ratio < usageIncreaseFactor) continue

            val severity = when {
                ratio >= usageIncreaseHighFactor || delta >= usageIncreaseHighDeltaMinutes ->
                    BehaviorSeverity.HIGH
                else -> BehaviorSeverity.MEDIUM
            }
            val description = buildString {
                append("「").append(item.appName).append("」今天使用了 ")
                append(minutesText(item.minutes)).append("，是最近 ")
                append(past.size).append(" 天日均（").append(minutesText(average.toLong())).append("）的 ")
                append(ratioText(ratio)).append(" 倍，多用了 ")
                append(minutesText(delta)).append("。")
            }
            events += event(
                BehaviorEventType.USAGE_INCREASE,
                item.packageName,
                severity,
                description,
                stamp,
                date,
            )
            if (events.size >= maxEventsPerType) break
        }
        return events
    }

    // ---------------------------------------------------------------- 3) 连续多日增长

    private fun detectMultiDayGrowth(
        date: String,
        stamp: Long,
        today: List<AppUsageSummary>,
        history: List<List<AppUsageSummary>>,
    ): List<BehaviorEventModel> {
        if (growthDays < 2 || history.size < growthDays - 1) return emptyList()
        val events = ArrayList<BehaviorEventModel>()
        for (item in today) {
            // 从旧到新的 (日期, 分钟) 序列，最后一项是 date 当天
            val series = ArrayList<Pair<String, Long>>()
            for (offset in growthDays - 1 downTo 0) {
                val dayDate = UsageEventSource.addDays(date, -offset)
                val minutes = if (offset == 0) {
                    item.minutes
                } else {
                    history.getOrNull(offset - 1)
                        ?.firstOrNull { it.packageName == item.packageName }?.minutes ?: 0L
                }
                series += dayDate to minutes
            }
            // 每一天都要有实质使用，缺一天就说明不是「持续增长」
            if (series.any { it.second < growthMinDayMinutes }) continue
            if (!series.zipWithNext().all { (prev, next) -> next.second > prev.second }) continue

            val first = series.first().second
            val last = series.last().second
            if (last < growthMinLastDayMinutes) continue

            val severity = if (last >= growthHighLastDayMinutes && last >= first * growthHighFactor) {
                BehaviorSeverity.HIGH
            } else {
                BehaviorSeverity.MEDIUM
            }
            val path = series.joinToString(" → ") { (d, m) ->
                "${UsageEventSource.dayLabel(d)} ${minutesText(m)}"
            }
            val description = buildString {
                append("「").append(item.appName).append("」连续 ")
                append(growthDays).append(" 天使用时长上升：").append(path)
                append("，注意及时收手。")
            }
            events += event(BehaviorEventType.MULTI_DAY_GROWTH, item.packageName, severity, description, stamp, date)
            if (events.size >= maxEventsPerType) break
        }
        return events
    }

    // ---------------------------------------------------------------- 4) 单次连续使用过长

    private fun detectLongSession(
        date: String,
        stamp: Long,
        sessions: List<SessionSlice>,
        details: Map<String, AppDetailModel>,
    ): List<BehaviorEventModel> {
        if (sessions.isEmpty()) return emptyList()
        val owner = sessionOwner(details)
        val events = ArrayList<BehaviorEventModel>()
        var unattributed: SessionSlice? = null

        for (slice in sessions.sortedByDescending { it.minutes }) {
            if (slice.minutes < longSessionMinutes) continue
            val who = owner[slice.startTime]
            if (who == null) {
                if (unattributed == null) unattributed = slice
                continue
            }
            val severity = if (slice.minutes >= longSessionHighMinutes) {
                BehaviorSeverity.HIGH
            } else {
                BehaviorSeverity.MEDIUM
            }
            val description = buildString {
                append("「").append(who.second).append("」从 ").append(timeText(slice.startTime))
                append(" 起连续使用了 ").append(minutesText(slice.minutes))
                append("，超过单次 ").append(minutesText(longSessionMinutes))
                append(" 的警戒线，中间几乎没有离开。")
            }
            events += event(BehaviorEventType.LONG_SESSION, who.first, severity, description, stamp, date)
            if (events.size >= maxEventsPerType) return events
        }

        // 会话归属不到具体应用时，只给一条笼统的事件（避免同一天刷出多条无法行动的提醒）
        val leftover = unattributed
        if (events.isEmpty() && leftover != null) {
            val severity = if (leftover.minutes >= longSessionHighMinutes) {
                BehaviorSeverity.HIGH
            } else {
                BehaviorSeverity.MEDIUM
            }
            val description = buildString {
                append("从 ").append(timeText(leftover.startTime)).append(" 起出现了一次 ")
                append(minutesText(leftover.minutes)).append(" 的连续使用，超过单次 ")
                append(minutesText(longSessionMinutes)).append(" 的警戒线。")
            }
            events += event(BehaviorEventType.LONG_SESSION, null, severity, description, stamp, date)
        }
        return events
    }

    // ---------------------------------------------------------------- 5) 深夜使用

    private fun detectLateNight(
        date: String,
        stamp: Long,
        sessions: List<SessionSlice>,
        details: Map<String, AppDetailModel>,
    ): List<BehaviorEventModel> {
        if (sessions.isEmpty()) return emptyList()
        val owner = sessionOwner(details)
        val buckets = HashMap<String?, NightBucket>()

        for (slice in sessions) {
            // 按 session 的「开始时间」判断是否属于深夜时段
            val hour = hourOf(slice.startTime)
            if (!isLateNightHour(hour)) continue
            if (slice.minutes < lateNightMinMinutes) continue
            val who = owner[slice.startTime]
            val bucket = buckets.getOrPut(who?.first) { NightBucket() }
            bucket.minutes += slice.minutes
            if (slice.startTime < bucket.start) bucket.start = slice.startTime
            if (who != null) bucket.appName = who.second
        }
        if (buckets.isEmpty()) return emptyList()

        val events = ArrayList<BehaviorEventModel>()
        for ((pkg, bucket) in buckets.entries.sortedByDescending { it.value.minutes }) {
            val startHour = hourOf(bucket.start)
            val severity = when {
                bucket.minutes >= lateNightHighMinutes || startHour in lateNightDeepHours ->
                    BehaviorSeverity.HIGH
                else -> BehaviorSeverity.MEDIUM
            }
            val window = "$lateNightStartHour:00–${lateNightEndHour}:00"
            val description = if (pkg != null && bucket.appName.isNotBlank()) {
                "深夜 ${timeText(bucket.start)} 起在「${bucket.appName}」上累计使用了 " +
                    "${minutesText(bucket.minutes)}（$window 属于休息时段）。"
            } else {
                "深夜 ${timeText(bucket.start)} 起累计使用了手机 ${minutesText(bucket.minutes)}" +
                    "（$window 属于休息时段），建议早点休息。"
            }
            events += event(BehaviorEventType.LATE_NIGHT, pkg, severity, description, stamp, date)
            if (events.size >= maxEventsPerType) break
        }
        return events
    }

    private class NightBucket(
        var minutes: Long = 0L,
        var start: Long = Long.MAX_VALUE,
        var appName: String = "",
    )

    // ---------------------------------------------------------------- 6) 短时间频繁打开

    private fun detectFrequentLaunch(
        date: String,
        stamp: Long,
        today: List<AppUsageSummary>,
        details: Map<String, AppDetailModel>,
    ): List<BehaviorEventModel> {
        if (details.isEmpty()) return emptyList()
        val events = ArrayList<BehaviorEventModel>()
        for (item in today) {
            val detail = details[item.packageName] ?: continue
            // 只有「用得不算多但反复打开」才像焦虑式刷手机；重度使用另有 USAGE_INCREASE / LONG_SESSION 负责
            if (detail.todayMinutes < frequentLaunchMinMinutes) continue
            val starts = detail.sessions.map { it.startTime }.sorted()
            if (starts.size < frequentLaunchPerHour) continue
            val (count, windowStart) = maxInWindow(starts, HOUR_MS)
            if (count < frequentLaunchPerHour) continue

            val severity = if (count >= frequentLaunchHighCount) {
                BehaviorSeverity.HIGH
            } else {
                BehaviorSeverity.MEDIUM
            }
            val description = buildString {
                append("「").append(detail.appName).append("」在 ").append(timeText(windowStart))
                append(" 起的 1 小时内被打开了 ").append(count)
                append(" 次，频繁切换应用容易打断专注。")
            }
            events += event(BehaviorEventType.FREQUENT_LAUNCH, item.packageName, severity, description, stamp, date)
            if (events.size >= maxEventsPerType) break
        }
        return events
    }

    // ---------------------------------------------------------------- 数据装配

    /**
     * 取「按时长前 N」+「按启动次数前 M」的应用详情。
     * appDetail 会额外查 4 次库，所以严格限量；结果在本次 analyze 内复用。
     */
    private suspend fun detailsOf(ranked: List<AppUsageSummary>): Map<String, AppDetailModel> {
        val targets = LinkedHashMap<String, Boolean>()
        ranked.take(detailedAppLimit).forEach { targets[it.packageName] = true }
        ranked.sortedByDescending { it.launchCount }.take(frequentLaunchAppLimit).forEach {
            targets[it.packageName] = true
        }
        val result = HashMap<String, AppDetailModel>()
        for (pkg in targets.keys) {
            result[pkg] = runCatching { usage.appDetail(pkg) }.getOrNull() ?: continue
        }
        return result
    }

    /** session.startTime -> (packageName, appName)，用于给「只有区间没有包名」的会话做归属 */
    private fun sessionOwner(details: Map<String, AppDetailModel>): Map<Long, Pair<String, String>> {
        val map = HashMap<Long, Pair<String, String>>()
        for (detail in details.values) {
            for (session in detail.sessions) {
                map[session.startTime] = detail.packageName to detail.appName
            }
        }
        return map
    }

    /** 滑动窗口内最多有多少个时间点，返回 (数量, 该窗口起点) */
    private fun maxInWindow(sortedStarts: List<Long>, windowMs: Long): Pair<Int, Long> {
        var best = 0
        var bestStart = sortedStarts.firstOrNull() ?: 0L
        var lo = 0
        for (hi in sortedStarts.indices) {
            while (sortedStarts[hi] - sortedStarts[lo] >= windowMs) lo++
            val count = hi - lo + 1
            if (count > best) {
                best = count
                bestStart = sortedStarts[lo]
            }
        }
        return best to bestStart
    }

    private fun event(
        type: BehaviorEventType,
        packageName: String?,
        severity: BehaviorSeverity,
        description: String,
        timestamp: Long,
        date: String,
    ) = BehaviorEventModel(
        eventType = type,
        packageName = packageName,
        severity = severity,
        description = description,
        timestamp = timestamp,
        date = date,
    )

    // ---------------------------------------------------------------- 小工具

    private fun isLateNightHour(hour: Int): Boolean =
        hour >= lateNightStartHour || hour < lateNightEndHour

    private fun hourOf(ts: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    private fun timeText(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))

    private fun minutesText(minutes: Long): String = when {
        minutes <= 0L -> "0 分钟"
        minutes < 60L -> "$minutes 分钟"
        minutes % 60L == 0L -> "${minutes / 60L} 小时"
        else -> "${minutes / 60L} 小时 ${minutes % 60L} 分钟"
    }

    private fun ratioText(ratio: Double): String = String.format(Locale.US, "%.1f", ratio)

    private fun severityOrder(severity: BehaviorSeverity): Int = when (severity) {
        BehaviorSeverity.HIGH -> 0
        BehaviorSeverity.MEDIUM -> 1
        BehaviorSeverity.LOW -> 2
    }

    companion object {
        // —— 通用 ——
        /** 基线窗口（天），不含待分析当天 */
        var historyDays: Int = 7

        /** 每天参与分析的 App 数量（按时长排行截断） */
        var rankingLimit: Int = 40

        /** 拉取 appDetail 的「时长 Top N」应用数 */
        var detailedAppLimit: Int = 6

        /** 额外拉取 appDetail 的「启动次数 Top N」应用数 */
        var frequentLaunchAppLimit: Int = 4

        /** 同一类型一次分析最多产出几条（再多的交给仓储去重） */
        var maxEventsPerType: Int = 3

        // —— USAGE_INCREASE：用量激增 ——
        /** 今日达到自身日均的多少倍才算激增 */
        var usageIncreaseFactor: Double = 1.8
        var usageIncreaseHighFactor: Double = 3.0

        /** 今日绝对时长下限（低于 30 分钟不报，避免正常波动误报） */
        var usageIncreaseMinTodayMinutes: Long = 30

        /** 相比日均至少多出多少分钟 */
        var usageIncreaseMinDeltaMinutes: Long = 20
        var usageIncreaseHighDeltaMinutes: Long = 90

        /** 至少要有几天的历史数据才能建立个人基线 */
        var baselineMinDays: Int = 3

        /** 日均低于该值时倍数没有意义（例如日均 3 分钟 → 20 分钟是 6.6 倍） */
        var baselineMinAverageMinutes: Long = 5

        // —— LONG_SESSION：单次连续使用过长（> 45 分钟才报） ——
        var longSessionMinutes: Long = 45
        var longSessionHighMinutes: Long = 90

        // —— LATE_NIGHT：深夜使用 ——
        var lateNightStartHour: Int = 23
        var lateNightEndHour: Int = 6

        /** 深夜单次至少用几分钟才值得提醒 */
        var lateNightMinMinutes: Long = 5
        var lateNightHighMinutes: Long = 60

        /** 凌晨这几点开始用，直接判为 HIGH */
        val lateNightDeepHours: IntRange = 0..3

        // —— FREQUENT_LAUNCH：1 小时内频繁打开 ——
        var frequentLaunchPerHour: Int = 8
        var frequentLaunchHighCount: Int = 16

        /** 频繁打开只针对「有实质使用」的应用，纯工具类几分钟的不报 */
        var frequentLaunchMinMinutes: Long = 5

        // —— MULTI_DAY_GROWTH：连续多日增长 ——
        var growthDays: Int = 3

        /** 序列里每一天都必须达到的时长下限 */
        var growthMinDayMinutes: Long = 10
        var growthMinLastDayMinutes: Long = 20
        var growthHighLastDayMinutes: Long = 60
        var growthHighFactor: Double = 2.0

        // —— OVER_TOTAL_TARGET：超出每日目标 ——
        var overTargetHighRatio: Double = 1.5

        /** 超出这么多分钟才算「明显超过目标」 */
        var overTargetAlertMinutes: Long = 10

        private const val HOUR_MS = 60L * 60L * 1000L

        /**
         * 兜底装配：把真实分析器挂到 Container 上（幂等）。
         *
         * 正常情况下由 AppWiring / App.onCreate 负责注入；这里只作为
         * Worker/调度侧的安全网，保证「没接线也不会分析出空结果」。
         */
        fun installIfNeeded(): Boolean {
            val repository = runCatching { Container.behavior }.getOrNull() ?: return false
            if (repository.analyzer !is StubBehaviorAnalyzer) return true
            val usageRepo = runCatching { Container.usage }.getOrNull() ?: return false
            val settingsRepo = runCatching { Container.settings }.getOrNull() ?: return false
            Container.attachAnalyzer(BehaviorAnalyzer(usageRepo, settingsRepo))
            return true
        }
    }
}
