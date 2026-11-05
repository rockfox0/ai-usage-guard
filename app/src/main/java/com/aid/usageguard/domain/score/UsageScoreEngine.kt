package com.aid.usageguard.domain.score

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 使用评分引擎：只用「时长 + 时段 + 目标对比 + 打开频率」这类本地聚合数据，
 * 不调用 LLM，保证离线可用、结果可解释。
 *
 * 得分构成（满分 100）：
 * - 超出目标：按比例最多扣 45 分
 * - 深夜使用（23:00–05:00）：最多扣 20 分
 * - 超长连续使用：最多扣 15 分
 * - 高频解锁：最多扣 12 分
 * - 克制加成：远低于目标 +3、无深夜使用 +3
 */
object UsageScoreEngine {

    private const val GRADE_EXCELLENT = "优秀"
    private const val GRADE_GOOD = "良好"
    private const val GRADE_FAIR = "一般"
    private const val GRADE_HEAVY = "偏多"
    private const val GRADE_OVERLOAD = "超负荷"

    fun gradeOf(score: Int): String = when {
        score >= 90 -> GRADE_EXCELLENT
        score >= 75 -> GRADE_GOOD
        score >= 60 -> GRADE_FAIR
        score >= 40 -> GRADE_HEAVY
        else -> GRADE_OVERLOAD
    }

    fun score(snapshot: UsageSnapshot, persona: ScorePersona): UsageScore {
        val target = snapshot.targetMinutes.coerceAtLeast(1)
        val ratio = snapshot.totalMinutes.toDouble() / target

        var raw = 100.0
        if (ratio > 1.0) raw -= min(45.0, (ratio - 1.0) * 45.0)
        raw -= min(20.0, snapshot.lateNightMinutes / 8.0)
        raw -= min(15.0, max(0.0, (snapshot.longestSessionMinutes - 30).toDouble()) / 6.0)
        raw -= min(12.0, max(0.0, (snapshot.launchCount - 50).toDouble()) / 8.0)
        if (ratio <= 0.5 && snapshot.totalMinutes > 0) raw += 3.0
        if (snapshot.lateNightMinutes == 0L) raw += 3.0

        val finalScore = raw.roundToInt().coerceIn(0, 100)
        val slots = TimeSlot.entries.map { SlotMinutes(it, snapshot.slots[it] ?: 0L) }
        val band = bandOf(ratio)
        val late = lateBandOf(snapshot.lateNightMinutes)

        return UsageScore(
            date = snapshot.date,
            score = finalScore,
            grade = gradeOf(finalScore),
            headline = headline(band, late, snapshot.date, persona),
            verdict = verdict(band, late, snapshot, persona),
            totalMinutes = snapshot.totalMinutes,
            targetMinutes = snapshot.targetMinutes,
            lateNightMinutes = snapshot.lateNightMinutes,
            longestSessionMinutes = snapshot.longestSessionMinutes,
            launchCount = snapshot.launchCount,
            slots = slots,
            topApps = snapshot.topApps,
        )
    }

    fun week(scores: List<UsageScore>, persona: ScorePersona): WeekScore {
        if (scores.isEmpty()) {
            return WeekScore(
                days = emptyList(), averageScore = 0, grade = GRADE_FAIR,
                headline = "还没有数据", verdict = "这一周还没有可用的使用记录，先正常使用几天再来看看。",
                totalMinutes = 0, averageMinutes = 0, overTargetDays = 0, lateNightDays = 0,
                bestDay = null, worstDay = null, trend = "持平",
            )
        }
        val days = scores.sortedBy { it.date }
        val avgScore = days.map { it.score }.average().roundToInt()
        val total = days.sumOf { it.totalMinutes }
        val avgMinutes = total / days.size
        val overTarget = days.count { it.targetRatio > 1.0 }
        val lateDays = days.count { it.lateNightMinutes >= 15 }
        val best = days.maxByOrNull { it.score }
        val worst = days.minByOrNull { it.score }

        val firstHalf = days.take(days.size / 2).ifEmpty { days.take(1) }
        val secondHalf = days.takeLast(days.size / 2).ifEmpty { days.takeLast(1) }
        val firstAvg = firstHalf.map { it.totalMinutes }.average()
        val secondAvg = secondHalf.map { it.totalMinutes }.average()
        val trend = when {
            firstAvg <= 0.0 && secondAvg <= 0.0 -> "持平"
            secondAvg > firstAvg * 1.1 -> "上升"
            secondAvg < firstAvg * 0.9 -> "下降"
            else -> "持平"
        }

        return WeekScore(
            days = days,
            averageScore = avgScore,
            grade = gradeOf(avgScore),
            headline = weekHeadline(avgScore, lateDays, trend),
            verdict = weekVerdict(days, avgScore, avgMinutes, overTarget, lateDays, best, worst, trend, persona),
            totalMinutes = total,
            averageMinutes = avgMinutes,
            overTargetDays = overTarget,
            lateNightDays = lateDays,
            bestDay = best,
            worstDay = worst,
            trend = trend,
        )
    }

    // ------------------------------------------------------------------
    // 判断词条
    // ------------------------------------------------------------------

    private enum class Band(val range: ClosedFloatingPointRange<Double>) {
        MINIMAL(0.0..0.4),
        HEALTHY(0.4..0.8),
        NEAR(0.8..1.0),
        OVER(1.0..1.5),
        HEAVY(1.5..2.5),
        EXTREME(2.5..Double.MAX_VALUE),
    }

    private enum class LateBand { NONE, LIGHT, MEDIUM, HEAVY }

    private fun bandOf(ratio: Double): Band = when {
        ratio < 0.4 -> Band.MINIMAL
        ratio < 0.8 -> Band.HEALTHY
        ratio < 1.0 -> Band.NEAR
        ratio < 1.5 -> Band.OVER
        ratio < 2.5 -> Band.HEAVY
        else -> Band.EXTREME
    }

    private fun lateBandOf(minutes: Long): LateBand = when {
        minutes <= 0 -> LateBand.NONE
        minutes < 15 -> LateBand.LIGHT
        minutes < 45 -> LateBand.MEDIUM
        else -> LateBand.HEAVY
    }

    private fun headlinesFor(band: Band): List<String> = when (band) {
        Band.MINIMAL -> listOf("今天很克制", "几乎没怎么碰手机", "专注在线", "状态很稳")
        Band.HEALTHY -> listOf("节奏不错", "稳稳的一天", "分寸感很好", "张弛有度")
        Band.NEAR -> listOf("刚刚好", "收得住", "接近目标但没超", "把住了线")
        Band.OVER -> listOf("有点上头", "小超一点", "该收一收了", "今天多了一点")
        Band.HEAVY -> listOf("今天有点失控", "屏幕时间偏多", "该踩刹车了", "有点舍不得放下")
        Band.EXTREME -> listOf("屏幕占领了今天", "重度使用日", "需要认真调整", "手机几乎没离手")
    }

    private fun headline(band: Band, late: LateBand, date: String, persona: ScorePersona): String {
        val options = headlinesFor(band)
        val base = options[abs(date.hashCode()) % options.size]
        val lateSuffix = when (late) {
            LateBand.NONE -> ""
            LateBand.LIGHT -> ""
            LateBand.MEDIUM -> " · 深夜还在刷"
            LateBand.HEAVY -> " · 熬夜偏多"
        }
        return "$base$lateSuffix"
    }

    private fun verdict(band: Band, late: LateBand, s: UsageSnapshot, persona: ScorePersona): String {
        val pct = (s.totalMinutes.toDouble() / s.targetMinutes.coerceAtLeast(1) * 100).roundToInt()
        val over = s.totalMinutes - s.targetMinutes

        val durationPart = when (band) {
            Band.MINIMAL -> "今天只用了 ${s.totalMinutes} 分钟，离目标（${s.targetMinutes} 分钟）还有很大空间。"
            Band.HEALTHY -> "今天用了 ${s.totalMinutes} 分钟，约占目标的 $pct%，节奏在健康区间。"
            Band.NEAR -> "今天用了 ${s.totalMinutes} 分钟，已经贴近目标（$pct%），基本收住了。"
            Band.OVER -> "今天用了 ${s.totalMinutes} 分钟，超出目标 $over 分钟（$pct%），开始有点多了。"
            Band.HEAVY -> "今天用了 ${s.totalMinutes} 分钟，是目标的 ${oneDecimal(s.totalMinutes.toDouble() / s.targetMinutes.coerceAtLeast(1))} 倍，属于偏重的一天。"
            Band.EXTREME -> "今天用了 ${s.totalMinutes} 分钟，是目标的 ${oneDecimal(s.totalMinutes.toDouble() / s.targetMinutes.coerceAtLeast(1))} 倍，屏幕几乎占满了空闲时间。"
        }

        val dominant = s.slots.maxByOrNull { it.value }?.takeIf { it.value > 0 }
        val slotPart = if (dominant == null) {
            ""
        } else {
            val share = dominant.value.toDouble() / max(1L, s.totalMinutes)
            if (share >= 0.5) {
                "使用主要集中在${dominant.key.label}（${dominant.value} 分钟，占 ${(share * 100).roundToInt()}%）。"
            } else {
                "使用比较分散，最长的一段在${dominant.key.label}（${dominant.value} 分钟）。"
            }
        }

        val latePart = when (late) {
            LateBand.NONE -> "没有深夜使用，作息友好。"
            LateBand.LIGHT -> "深夜还有 ${s.lateNightMinutes} 分钟，注意别越拖越晚。"
            LateBand.MEDIUM -> "深夜用了 ${s.lateNightMinutes} 分钟，已经影响到休息。"
            LateBand.HEAVY -> "深夜用了 ${s.lateNightMinutes} 分钟，这是今天最该改的地方。"
        }

        val advice = adviceFor(band, late, s.targetMinutes, persona.tone)
        val who = persona.nickname.trim().takeIf { it.isNotEmpty() }?.let { "$it，" } ?: ""
        return who + durationPart + slotPart + latePart + advice
    }

    private fun adviceFor(band: Band, late: LateBand, target: Int, tone: String): String = when {
        band == Band.MINIMAL -> "保持这个状态就好。"
        band == Band.HEALTHY -> when (tone) {
            "strict" -> "明天维持 ${target} 分钟以内，不要放松。"
            "direct" -> "明天继续保持，别让总量反弹。"
            else -> "明天保持这个节奏就很好了。"
        }
        band == Band.NEAR -> when (tone) {
            "strict" -> "明天必须在 ${target} 分钟前停下。"
            "direct" -> "明天提前 10 分钟收手，别顶到目标线。"
            else -> "下次提前一点收手，会轻松很多。"
        }
        band == Band.OVER -> when (tone) {
            "strict" -> "明天必须压回 ${target} 分钟，先关掉最耗时应用的通知。"
            "direct" -> "明天把总量压回 ${target} 分钟；先删掉最耗时应用的通知。"
            else -> "明天试着把总量压回 ${target} 分钟，从少一次无意识解锁开始。"
        }
        band == Band.HEAVY -> when (tone) {
            "strict" -> "这一周都要盯着：明天先设 ${target} 分钟硬上限，超了就停。"
            "direct" -> "明天先砍掉三分之一，把最耗时的应用设个上限。"
            else -> "明天先挑一个最耗时的应用做限制，比一次性全改容易坚持。"
        }
        else -> when (tone) {
            "strict" -> "今天已经过载，明天必须限时：给最耗时应用设每日上限。"
            "direct" -> "今天明显过载了，明天从最耗时的那个应用开始限制。"
            else -> "别自责，明天先从最耗时的那个应用开始调整就好。"
        } + if (late == LateBand.HEAVY) " 另外，睡前把手机放到床外。" else ""
    }

    private fun weekHeadline(avgScore: Int, lateDays: Int, trend: String): String {
        val base = when {
            avgScore >= 85 && trend != "上升" -> "稳步向好"
            avgScore >= 85 -> "整体不错"
            avgScore >= 70 -> "中规中矩"
            avgScore >= 55 -> "偏多的一周"
            else -> "很吃力的一周"
        }
        return if (lateDays >= 3) "$base · 熬夜偏多" else base
    }

    private fun weekVerdict(
        days: List<UsageScore>,
        avgScore: Int,
        avgMinutes: Long,
        overTarget: Int,
        lateDays: Int,
        best: UsageScore?,
        worst: UsageScore?,
        trend: String,
        persona: ScorePersona,
    ): String {
        val who = persona.nickname.trim().takeIf { it.isNotEmpty() }?.let { "$it，" } ?: ""
        val sb = StringBuilder()
        sb.append(who)
        sb.append("这一周（${days.size} 天）日均 $avgMinutes 分钟，平均得分 $avgScore（${gradeOf(avgScore)}）。")
        sb.append("超目标 $overTarget 天")
        sb.append(if (lateDays > 0) "，深夜使用 $lateDays 天。" else "，没有深夜使用。")
        best?.let { sb.append("状态最好的是 ${it.date}（${it.score} 分，${it.headline}）。") }
        worst?.let {
            if (worst.date != best?.date) {
                sb.append("最需要留意的是 ${it.date}（${it.score} 分，${it.headline}）。")
            }
        }
        sb.append(
            when (trend) {
                "下降" -> "后半周比前半周更收敛，趋势在往好的方向走。"
                "上升" -> "后半周比前半周用得更多，注意别形成惯性。"
                else -> "这一周整体比较平稳。"
            },
        )
        sb.append(
            when {
                avgScore >= 80 -> "下周保持现在的节奏即可，不用加码。"
                avgScore >= 60 -> "下周挑一天做「少用 30 分钟」的小挑战，看看能不能稳住。"
                else -> {
                    when (persona.tone) {
                        "strict" -> "下周必须做两件事：给最耗时应用设上限，睡前手机不带上床。"
                        "direct" -> "下周先做一件事：给最耗时应用设每日上限，把深夜使用压到 0。"
                        else -> "下周先做一件小事：给最耗时的那个应用设个上限，慢慢来。"
                    }
                }
            },
        )
        return sb.toString()
    }

    private fun oneDecimal(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
    }
}
