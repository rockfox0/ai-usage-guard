package com.aid.usageguard.agent.multi

import com.aid.usageguard.agent.plan.TaskMode

/**
 * 子 Agent 启用策略（v1.9 Phase 7，纯函数）：
 * 只有「复杂分析 + 预算充足」才并行启用专家 Agent；简单问题/目标型任务/预算紧张时一律不启用。
 */
object SubAgentPolicy {

    /** 并行 2 个子 Agent，每个消耗 1 次模型请求 */
    const val REQUIRED_REQUESTS = 5

    private val COMPLEX_MARKERS = listOf(
        "30 天", "30天", "一个月", "全面", "多维度", "各个维度", "整体", "综合",
        "习惯", "问题", "规律", "为什么", "对比", "趋势", "周环比", "月环比",
        "analyze", "analysis", "comprehensive", "overall", "habit", "trend",
    )

    fun shouldFanOut(mode: TaskMode, remainingModelRequests: Int, input: String): Boolean {
        if (mode != TaskMode.ANALYSIS) return false
        if (remainingModelRequests < REQUIRED_REQUESTS) return false
        val text = input.lowercase()
        val matched = COMPLEX_MARKERS.any { text.contains(it.lowercase()) }
        return matched || input.length >= 40
    }
}
