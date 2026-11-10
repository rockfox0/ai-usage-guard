package com.aid.usageguard.domain.usage

/**
 * 使用数据一致性校验（v1.10，纯函数）。
 *
 * 首页显示的是「今日总分钟（app_usage_record 求和）」，评分卡片的时段分布来自
 * daily_score.slotsJson。两者理论上应相等（都来自同一天的会话聚合），
 * 但后台同步/落库时点不同可能短暂不一致；超过容差就提示用户「数据正在重算」。
 */
data class ConsistencyReport(
    val ok: Boolean,
    val message: String? = null,
)

object UsageConsistency {

    /** 分钟级四舍五入误差允许 ±2 分钟 */
    const val DEFAULT_TOLERANCE_MINUTES = 2L

    fun verify(
        totalMinutes: Long,
        appSumMinutes: Long,
        slotSumMinutes: Long,
        toleranceMinutes: Long = DEFAULT_TOLERANCE_MINUTES,
    ): ConsistencyReport {
        val appDiff = kotlin.math.abs(totalMinutes - appSumMinutes)
        val slotDiff = kotlin.math.abs(totalMinutes - slotSumMinutes)
        val bad = maxOf(appDiff, slotDiff) > toleranceMinutes
        if (!bad) return ConsistencyReport(ok = true)
        val message = buildString {
            append("数据一致性校验：")
            if (appDiff > toleranceMinutes) append("总分与 App 合计差 $appDiff 分钟；")
            if (slotDiff > toleranceMinutes) append("总分与时段分布差 $slotDiff 分钟；")
            append("数据正在重算")
        }
        return ConsistencyReport(ok = false, message = message)
    }
}
