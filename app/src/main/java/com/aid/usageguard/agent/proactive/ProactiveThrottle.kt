package com.aid.usageguard.agent.proactive

/**
 * 主动洞察节流（v1.9，纯函数）：
 * 保护用户不被"AI 每天追着分析"打扰——每天最多 1 条，且开关关闭时完全不分析。
 */
object ProactiveThrottle {

    const val MAX_INSIGHTS_PER_DAY = 1

    fun shouldSkip(
        enabled: Boolean,
        todayInsightCount: Int,
        latestInsightDate: String?,
        today: String,
    ): Boolean {
        if (!enabled) return true
        if (todayInsightCount >= MAX_INSIGHTS_PER_DAY) return true
        return latestInsightDate == today
    }
}
