package com.aid.usageguard.agent.plan

import com.aid.usageguard.agent.contract.AgentContext

/** 任务执行模式（v1.9 Phase 2）：简单直答 / 多维分析 / 目标型任务。 */
enum class TaskMode(val label: String) {
    DIRECT("直答"),
    ANALYSIS("分析"),
    GOAL("目标");
}

/**
 * 本地模式判定（快速、零 token）：只决定「要不要先规划」，不做意图的最终解释。
 *
 * 简单问题（今天用了多久）→ DIRECT，不额外消耗模型请求；
 * 多维度/带目标的问题 → ANALYSIS / GOAL，由 TaskPlanner 生成计划。
 * 判定不确定时偏向 DIRECT（后续模型仍可自行多轮调用工具）。
 */
object TaskModeClassifier {

    private val GOAL_MARKERS = listOf(
        "制定", "计划", "方案", "目标", "帮我改", "帮我设置", "帮我管", "减少", "戒掉", "戒了",
        "管理", "监控", "规则", "以后", "从此", "坚持", "改善", "调整", "限制方案",
        "help me", "plan", "goal", "reduce", "manage", "monitor", "rule",
    )

    private val ANALYSIS_MARKERS = listOf(
        "分析", "问题", "找出", "为什么", "原因", "趋势", "习惯", "对比", "比较", "变化", "规律",
        "多维度", "评估", "诊断", "最近一个月", "最近30天", "这周", "本周", "上月",
        "analyze", "analysis", "why", "trend", "habit", "compare", "problem",
    )

    private val MULTI_INTENT_MARKERS = listOf("然后", "并且", "同时", "顺便", "再帮", "以及", "接着", "and then")

    fun classify(input: String, context: AgentContext? = null): TaskMode {
        val text = input.lowercase()
        val hasGoal = GOAL_MARKERS.any { text.contains(it) }
        val hasAnalysis = ANALYSIS_MARKERS.any { text.contains(it) }
        val multiIntent = MULTI_INTENT_MARKERS.count { text.contains(it) } >= 1 ||
            text.count { it == '，' || it == ',' } >= 2

        return when {
            hasGoal -> TaskMode.GOAL
            hasAnalysis || multiIntent || input.length > 80 -> TaskMode.ANALYSIS
            // 页面上下文里明确在看某个应用 + 泛问题（"最近怎么样"）→ 按分析处理
            context?.focusPackage != null && input.length <= 10 -> TaskMode.ANALYSIS
            else -> TaskMode.DIRECT
        }
    }
}
