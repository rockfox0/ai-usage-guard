package com.aid.usageguard.domain.insight

import com.aid.usageguard.domain.model.DailyTotal

/**
 * 本地候选事件检测（v1.9 Phase 6，纯函数）：
 * 先在本地用阈值筛出「值得让 AI 深度分析」的事件，避免每次定时任务都调模型。
 *
 * 规则：
 * - 总时长：最近 7 天日均比前 7 天高 25% 以上，且每天多 30 分钟以上；
 * - 单应用：某应用最近 7 天比前 7 天高 50% 且基数 ≥30 分钟；
 * - 时间转移：一个应用降 ≥30%、另一个升 ≥30%，总时长变化 <10%（换了个地方刷手机）；
 * - 超目标：今天已超每日目标 20%。
 */
object InsightDetector {

    private const val MIN_DELTA_MINUTES = 30L
    private const val TOTAL_INCREASE_RATIO = 1.25
    private const val APP_INCREASE_RATIO = 1.5
    private const val APP_MIN_BASE_MINUTES = 30L
    private const val SHIFT_RATIO = 0.3
    private const val SHIFT_TOTAL_TOLERANCE = 0.10

    fun detect(
        dailyTotals: List<DailyTotal>,
        appLast7: Map<String, Long>,
        appPrev7: Map<String, Long>,
        todayMinutes: Long,
        dailyTargetMinutes: Int,
        today: String,
    ): List<InsightCandidate> {
        val candidates = ArrayList<InsightCandidate>()

        // 1) 总时长上升：dailyTotals 按日期升序，取最近 14 天
        val recent = dailyTotals.sortedBy { it.date }.takeLast(14)
        if (recent.size >= 14) {
            val last7 = recent.takeLast(7).map { it.minutes }
            val prev7 = recent.take(7).map { it.minutes }
            val lastAvg = last7.average()
            val prevAvg = prev7.average()
            val delta = lastAvg - prevAvg
            if (prevAvg > 0 && lastAvg >= prevAvg * TOTAL_INCREASE_RATIO && delta >= MIN_DELTA_MINUTES) {
                val percent = ((delta / prevAvg) * 100).toInt()
                candidates += InsightCandidate(
                    kind = InsightKind.TOTAL_INCREASE,
                    dedupeKey = "total:$today",
                    fact = "最近 7 天日均 ${lastAvg.toLong()} 分钟，前 7 天 ${prevAvg.toLong()} 分钟，增加 $percent%",
                    deltaPercent = percent,
                )
            }
        }

        // 2) 单应用明显增加（最多挑 1 个最明显的）
        val appGrowth = appLast7.mapNotNull { (pkg, last) ->
            val prev = appPrev7[pkg] ?: 0L
            if (prev < APP_MIN_BASE_MINUTES) return@mapNotNull null
            if (last < prev * APP_INCREASE_RATIO) return@mapNotNull null
            val percent = (((last - prev).toDouble() / prev) * 100).toInt()
            Triple(pkg, last - prev, percent)
        }.sortedByDescending { it.second }.firstOrNull()
        if (appGrowth != null) {
            candidates += InsightCandidate(
                kind = InsightKind.APP_INCREASE,
                dedupeKey = "app:${appGrowth.first}:$today",
                fact = "应用 ${appGrowth.first} 最近 7 天比前 7 天多 ${appGrowth.second} 分钟（+${appGrowth.third}%）",
                packageName = appGrowth.first,
                deltaPercent = appGrowth.third,
            )
        }

        // 3) 时间转移：一个应用显著下降、另一个显著上升，但总时长基本不变
        run {
            val down = appLast7.mapNotNull { (pkg, last) ->
                val prev = appPrev7[pkg] ?: 0L
                if (prev < APP_MIN_BASE_MINUTES) return@mapNotNull null
                if (last > prev * (1 - SHIFT_RATIO)) return@mapNotNull null
                pkg to (prev - last)
            }.sortedByDescending { it.second }.firstOrNull()
            val up = appLast7.mapNotNull { (pkg, last) ->
                val prev = appPrev7[pkg] ?: 0L
                if (last < APP_MIN_BASE_MINUTES) return@mapNotNull null
                if (last < prev * (1 + SHIFT_RATIO)) return@mapNotNull null
                pkg to (last - prev)
            }.sortedByDescending { it.second }.firstOrNull()
            val totalLast = appLast7.values.sum()
            val totalPrev = appPrev7.values.sum()
            val totalChange = if (totalPrev > 0) kotlin.math.abs(totalLast - totalPrev).toDouble() / totalPrev else 1.0
            if (down != null && up != null && down.first != up.first && totalChange <= SHIFT_TOTAL_TOLERANCE) {
                candidates += InsightCandidate(
                    kind = InsightKind.TIME_SHIFT,
                    dedupeKey = "shift:${down.first}->${up.first}:$today",
                    fact = "${down.first} 减少 ${down.second} 分钟，${up.first} 增加 ${up.second} 分钟，总时长基本不变",
                    packageName = up.first,
                )
            }
        }

        // 4) 超目标
        if (dailyTargetMinutes > 0 && todayMinutes > dailyTargetMinutes * 1.2) {
            candidates += InsightCandidate(
                kind = InsightKind.TARGET_EXCEEDED,
                dedupeKey = "target:$today",
                fact = "今天已用 $todayMinutes 分钟，超过每日目标 $dailyTargetMinutes 分钟的 20%",
            )
        }
        return candidates
    }
}
