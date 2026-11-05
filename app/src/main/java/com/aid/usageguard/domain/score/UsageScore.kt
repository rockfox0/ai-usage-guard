package com.aid.usageguard.domain.score

/** 使用时段（本地时区），深夜跨零点单独处理 */
enum class TimeSlot(val key: String, val label: String, val startHour: Int, val endHour: Int) {
    EARLY("early", "清晨 5-9", 5, 9),
    MORNING("morning", "上午 9-12", 9, 12),
    AFTERNOON("afternoon", "下午 12-18", 12, 18),
    EVENING("evening", "晚上 18-23", 18, 23),
    NIGHT("night", "深夜 23-5", 23, 5),
}

data class SlotMinutes(val slot: TimeSlot, val minutes: Long)

/** 计算评分所需的聚合快照（由 ScoreRepository 从 Room + 设置生成） */
data class UsageSnapshot(
    val date: String,
    val totalMinutes: Long,
    val targetMinutes: Int,
    val lateNightMinutes: Long,
    val longestSessionMinutes: Long,
    val launchCount: Int,
    val slots: Map<TimeSlot, Long>,
    val topApps: List<Pair<String, Long>> = emptyList(),
)

/** 打分/词条的个性化参数 */
data class ScorePersona(
    val nickname: String = "",
    val tone: String = "gentle",
)

/** 单日评分结果（含个性化判断词条） */
data class UsageScore(
    val date: String,
    val score: Int,
    val grade: String,
    /** 短词条，例如「有点上头」「节奏不错」 */
    val headline: String,
    /** 完整判断句，1~3 句 */
    val verdict: String,
    val totalMinutes: Long,
    val targetMinutes: Int,
    val lateNightMinutes: Long,
    val longestSessionMinutes: Long,
    val launchCount: Int,
    val slots: List<SlotMinutes>,
    val topApps: List<Pair<String, Long>> = emptyList(),
) {
    val targetRatio: Double
        get() = if (targetMinutes > 0) totalMinutes.toDouble() / targetMinutes else 0.0

    fun slotText(): String = slots
        .filter { it.minutes > 0 }
        .joinToString(" · ") { "${it.slot.label} ${it.minutes}m" }

    /** 每日总结文本（报告 / 卡片 / 通知复用） */
    fun summaryLines(): List<String> {
        val lines = mutableListOf<String>()
        lines += "今日 ${totalMinutes} 分钟（目标 ${targetMinutes} 分钟，完成 ${(targetRatio * 100).toInt()}%）"
        if (lateNightMinutes > 0) lines += "深夜使用 ${lateNightMinutes} 分钟"
        if (longestSessionMinutes > 0) lines += "最长连续 ${longestSessionMinutes} 分钟"
        if (launchCount > 0) lines += "打开 ${launchCount} 次"
        if (topApps.isNotEmpty()) {
            lines += "主要应用：" + topApps.take(3).joinToString("、") { "${it.first} ${it.second}m" }
        }
        return lines
    }
}

/** 一周总结（由最近 7 天的 UsageScore 聚合） */
data class WeekScore(
    val days: List<UsageScore>,
    val averageScore: Int,
    val grade: String,
    val headline: String,
    val verdict: String,
    val totalMinutes: Long,
    val averageMinutes: Long,
    val overTargetDays: Int,
    val lateNightDays: Int,
    val bestDay: UsageScore?,
    val worstDay: UsageScore?,
    /** 与前半周相比：上升 / 下降 / 持平 */
    val trend: String,
) {
    fun summaryLines(): List<String> {
        val lines = mutableListOf<String>()
        lines += "本周合计 ${totalMinutes} 分钟，日均 ${averageMinutes} 分钟，平均得分 $averageScore（$grade）"
        lines += "超目标 $overTargetDays 天 · 深夜使用 $lateNightDays 天"
        bestDay?.let { lines += "最好的一天：${it.date}（${it.score} 分，${it.headline}）" }
        worstDay?.let { lines += "最需要留意：${it.date}（${it.score} 分，${it.headline}）" }
        lines += "趋势：$trend"
        return lines
    }
}
