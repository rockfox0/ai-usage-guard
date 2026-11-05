package com.aid.usageguard.domain.report

import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.BehaviorRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.domain.score.UsageScore
import com.aid.usageguard.domain.score.WeekScore

/**
 * 本地规则报告生成器：没有配置 LLM / 调用失败 / 离线时的降级路径。
 * 保证「断网也能给出可读的分析」。
 *
 * 评分接入方式：
 * - 日报/周报的【今日评分】【本周总结】段落由 [UsageScore] / [WeekScore] 提供；
 * - 调用方可以显式传入 `score` / `week`（推荐，避免重复计算）；
 * - 不传时回退到 [Container.score] 现算一份，这样既有的
 *   `ReportRepository` / `ReportTools` 调用点无需改动也能带上评分。
 */
object LocalReportGenerator {

    suspend fun daily(
        usage: UsageRepository,
        behavior: BehaviorRepository,
        settings: AppSettings,
        date: String,
        score: UsageScore? = null,
    ): String {
        val total = usage.todayTotalMinutes()
        val ranking = usage.todayRanking(limit = 5)
        val week = usage.weekTotals()
        val events = behavior.forDate(date)
        val target = settings.dailyTargetMinutes.toLong()

        val builder = StringBuilder()
        // 【今日评分】放在最前面：分数 → 等级 → 词条 → 判断句 → 时段分布 → 明细
        appendDailyScore(builder, resolveScore(date, score))

        builder.append("【使用概况】\n")
        if (total <= 0) {
            builder.append("今天还没有记录到应用使用时长（可能刚授权或今天还没怎么用手机）。\n")
        } else {
            val top = ranking.firstOrNull()
            builder.append("今天手机前台使用约 ").append(fmt(total)).append("，目标 ")
                .append(fmt(target)).append("。")
            if (top != null) {
                builder.append("使用最多的是「").append(top.appName).append("」")
                builder.append(fmt(top.minutes)).append("，占比约 ")
                    .append((top.minutes * 100.0 / total).toInt()).append("%。")
            }
            builder.append('\n')
        }

        builder.append("\n【值得注意】\n")
        val notes = ArrayList<String>()
        if (total > target && target > 0) {
            notes.add("已超过今日目标 " + fmt(total - target) + "。")
        }
        if (events.isNotEmpty()) {
            events.take(3).forEach { notes.add(it.description) }
        }
        if (notes.isEmpty()) {
            notes.add("没有检测到明显的异常使用模式。")
            val avg = week.filter { it.date != date }.map { it.minutes }.average().toLong()
            if (avg > 0 && total > avg * 2) notes.add("今天是近 7 天的 2 倍以上，注意节奏。")
        }
        notes.forEach { builder.append("- ").append(it).append('\n') }

        builder.append("\n【建议】\n")
        val suggestions = ArrayList<String>()
        val top = ranking.firstOrNull()
        if (top != null && top.minutes >= 30) {
            suggestions.add("给「${top.appName}」设一个暂停开关，用它之前先想清楚要做什么。")
        }
        if (total > target && target > 0) {
            suggestions.add("把今天剩下的时间用来做一件线下的事，手机放到看不见的地方。")
        }
        suggestions.add("晚上 22:30 看一眼 AI 日报，比无意识地刷手机更容易建立节奏。")
        suggestions.add("如果某个 App 连续三天都在前三名，考虑在「应用」页长期暂停它。")
        suggestions.take(3).forEach { builder.append("- ").append(it).append('\n') }
        return builder.toString().trim()
    }

    suspend fun weekly(
        usage: UsageRepository,
        settings: AppSettings,
        week: WeekScore? = null,
    ): String {
        val weekTotals = usage.weekTotals()
        val total = weekTotals.sumOf { it.minutes }
        val average = if (weekTotals.isEmpty()) 0L else total / weekTotals.size
        val maxDay = weekTotals.maxByOrNull { it.minutes }
        val ranking = usage.todayRanking(limit = 5)

        val builder = StringBuilder()
        // 【本周总结】放在最前面：平均分 → 等级 → 词条 → 判断/建议 → 关键天数 → 最佳/最差
        appendWeeklyScore(builder, resolveWeek(week))

        builder.append("【本周概况】\n")
        builder.append("最近 7 天累计使用 ").append(fmt(total))
            .append("，日均 ").append(fmt(average)).append("。")
        if (maxDay != null && maxDay.minutes > 0) {
            builder.append("最高的一天是 ").append(maxDay.date).append("（")
                .append(fmt(maxDay.minutes)).append("）。")
        }
        builder.append('\n')

        builder.append("\n【趋势与注意】\n")
        val sorted = weekTotals.sortedBy { it.date }
        val rising = sorted.zipWithNext().count { (a, b) -> b.minutes > a.minutes }
        builder.append("- 最近 7 天里有 ").append(rising).append(" 天比前一天用得多。\n")
        if (ranking.isNotEmpty()) {
            builder.append("- 今天使用最多的是「").append(ranking.first().appName).append("」")
                .append(fmt(ranking.first().minutes)).append("。\n")
        }
        if (average > settings.dailyTargetMinutes) {
            builder.append("- 日均已超过目标 ").append(fmt(settings.dailyTargetMinutes.toLong())).append("。\n")
        }

        builder.append("\n【下周建议】\n")
        builder.append("- 选 1 个「每次打开都后悔」的应用加入暂停清单。\n")
        builder.append("- 给每天设一个不使用手机的固定时段（例如睡前一小时）。\n")
        builder.append("- 继续在 22:30 查看日报，观察趋势是否下降。\n")
        return builder.toString().trim()
    }

    // ------------------------------------------------------------------ 评分段落

    /** 【今日评分】：分数 + 等级 + 词条 + 判断句 + 时段分布 + summaryLines */
    private fun appendDailyScore(builder: StringBuilder, score: UsageScore?) {
        if (score == null) return
        builder.append("【今日评分】\n")
        builder.append(score.score).append(" 分 · ").append(score.grade)
        if (score.headline.isNotBlank()) builder.append(" · ").append(score.headline)
        builder.append('\n')
        if (score.verdict.isNotBlank()) builder.append(score.verdict).append('\n')
        val slots = score.slotText()
        if (slots.isNotBlank()) builder.append("时段分布：").append(slots).append('\n')
        score.summaryLines().forEach { line -> builder.append("- ").append(line).append('\n') }
        builder.append('\n')
    }

    /** 【本周总结】：平均分 + 等级 + 词条 + verdict + 超目标/深夜天数 + 最佳/最差日 + 趋势 */
    private fun appendWeeklyScore(builder: StringBuilder, week: WeekScore?) {
        if (week == null) return
        builder.append("【本周总结】\n")
        builder.append("平均 ").append(week.averageScore).append(" 分 · ").append(week.grade)
        if (week.headline.isNotBlank()) builder.append(" · ").append(week.headline)
        builder.append('\n')
        if (week.verdict.isNotBlank()) builder.append(week.verdict).append('\n')
        builder.append("- 超目标 ").append(week.overTargetDays).append(" 天 · 深夜使用 ")
            .append(week.lateNightDays).append(" 天 · 趋势：").append(week.trend).append('\n')
        week.bestDay?.let { day ->
            builder.append("- 最好的一天：").append(day.date)
                .append("（").append(day.score).append(" 分，").append(day.headline).append("）\n")
        }
        week.worstDay?.let { day ->
            builder.append("- 最需要留意：").append(day.date)
                .append("（").append(day.score).append(" 分，").append(day.headline).append("）\n")
        }
        if (week.days.isEmpty()) builder.append("- 这一周还没有可用的使用记录。\n")
        builder.append('\n')
    }

    /** 调用方没传评分时，回退到 Container.score 现算一份（容器未初始化时静默跳过） */
    private suspend fun resolveScore(date: String, provided: UsageScore?): UsageScore? =
        provided ?: runCatching { Container.score.scoreFor(date) }.getOrNull()

    private suspend fun resolveWeek(provided: WeekScore?): WeekScore? =
        provided ?: runCatching { Container.score.week() }.getOrNull()

    // ------------------------------------------------------------------ 工具

    private fun fmt(minutes: Long): String {
        if (minutes <= 0) return "0 分钟"
        if (minutes < 60) return "$minutes 分钟"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "$h 小时" else "$h 小时 $m 分钟"
    }

    fun today(): String = UsageEventSource.todayDate()
}
