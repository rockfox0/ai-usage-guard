package com.aid.usageguard.agent.plan

import com.aid.usageguard.domain.model.DataCoverage
import com.aid.usageguard.domain.model.DataStatus

/**
 * 结果评估器（v1.9 Phase 2）：工具 ok != 任务完成。
 *
 * 纯本地规则（不额外消耗模型请求）：
 * - 回答为空 → NEED_MORE；
 * - 分析步骤要求数据但一个数据工具都没调 → NEED_MORE；
 * - 回答里一个数字都没有 → NEED_MORE（防止"看起来像分析"的空话）；
 * - 用户要 30 天/月维度，但本地有效数据天数明显不足且回答没提数据不足 → INSUFFICIENT_DATA（追加说明）。
 */
object ResultEvaluator {

    private val DATA_TOOLS = setOf(
        "get_daily_usage", "get_weekly_usage", "get_usage_ranking", "get_app_usage_history",
        "get_usage_trend", "get_behavior_events", "get_usage_score", "get_quota_status",
        "get_whitelist", "get_app_settings", "get_user_preferences", "get_pending_actions",
    )

    private val INSUFFICIENT_HINTS = listOf("数据不完整", "只有", "不足", "缺少", "天数据", "无法确认")

    fun evaluate(
        text: String,
        usedTools: List<String>,
        plan: TaskPlan?,
        coverage: DataCoverage? = null,
        expectsMonthRange: Boolean = false,
    ): Evaluation {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return Evaluation(
                EvaluationVerdict.NEED_MORE,
                reason = "还没有生成任何结论",
                directive = "请基于已获取的数据给出结论；若数据不足请说明缺什么。",
            )
        }

        val usesData = usedTools.any { it in DATA_TOOLS }
        if (!usesData && looksLikeAnalysis(plan)) {
            return Evaluation(
                EvaluationVerdict.NEED_MORE,
                reason = "结论缺少本地数据支撑（还没有调用统计类工具）",
                directive = "请先调用所需的数据查询工具（如 get_daily_usage / get_usage_trend / get_usage_ranking），再给出结论。",
            )
        }

        val hasNumber = trimmed.any { it.isDigit() }
        if (!hasNumber && usesData) {
            return Evaluation(
                EvaluationVerdict.NEED_MORE,
                reason = "结论里没有引用任何具体数据",
                directive = "请从工具结果中引用关键数字（分钟数、次数、天数等）后再回答。",
            )
        }

        if (expectsMonthRange && coverage != null &&
            coverage.status == DataStatus.PARTIAL && coverage.validDates.size < 25
        ) {
            val mentionsGap = INSUFFICIENT_HINTS.any { trimmed.contains(it) }
            if (!mentionsGap) {
                return Evaluation(
                    EvaluationVerdict.INSUFFICIENT_DATA,
                    reason = "请求的是较长时间范围，但本地只有 ${coverage.validDates.size} 天有效数据",
                    caveat = "（说明：本地只记录到 ${coverage.validDates.size} 天有效数据，" +
                        "更早的数据系统不一定保留，以上结论只基于已有天数。）",
                )
            }
        }

        return Evaluation(EvaluationVerdict.OK)
    }

    private fun looksLikeAnalysis(plan: TaskPlan?): Boolean {
        if (plan == null) return false
        return plan.steps.any { step ->
            step.requiresData && step.capabilities.any {
                it == "usage_analysis" || it == "score_review" || it == "quota_control"
            }
        }
    }
}
