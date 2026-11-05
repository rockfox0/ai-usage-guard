package com.aid.usageguard.domain.monitor

/**
 * 规则校验（纯函数，单测覆盖）：
 * AI 生成/用户手写规则都先过这里，再进入待确认草案；本地引擎执行时无需重复解析自然语言。
 */
object MonitorValidator {

    const val MAX_RULES = 5
    const val MAX_NAME_LENGTH = 30

    sealed interface Result {
        data object Ok : Result
        data class Rejected(val reason: String) : Result
    }

    fun validate(draft: MonitorDefinition, existing: List<MonitorDefinition>): Result {
        val name = draft.name.trim()
        if (name.isEmpty()) return Result.Rejected("规则名称不能为空")
        if (name.length > MAX_NAME_LENGTH) return Result.Rejected("规则名称最长 $MAX_NAME_LENGTH 个字")

        val isAllApps = draft.packageName.isNullOrBlank()
        if (isAllApps && draft.metric != MonitorMetric.FOREGROUND_DURATION) {
            return Result.Rejected("「全部应用」只支持使用时长监控；启动次数请指定具体应用")
        }

        when (draft.metric) {
            MonitorMetric.FOREGROUND_DURATION ->
                if (draft.threshold !in 5L..1440L) return Result.Rejected("时长阈值需在 5-1440 分钟之间")

            MonitorMetric.LAUNCH_COUNT ->
                if (draft.threshold !in 1L..500L) return Result.Rejected("启动次数阈值需在 1-500 次之间")
        }

        if (existing.any { it.enabled && it.packageName == draft.packageName && it.metric == draft.metric }) {
            return Result.Rejected("已存在同应用同指标的有效监控规则，请先删除或修改旧规则")
        }
        if (existing.count { it.enabled } >= MAX_RULES) {
            return Result.Rejected("最多同时启用 $MAX_RULES 条监控规则")
        }
        return Result.Ok
    }

    /** 给用户/草案卡展示的一句话描述 */
    fun describe(
        draft: MonitorDefinition,
        appNameOf: (String) -> String? = { null },
    ): String {
        val who = draft.packageName?.let { pkg -> appNameOf(pkg) ?: pkg } ?: "全部应用"
        val unit = draft.metric.unit
        val cmp = when (draft.operator) {
            MonitorOperator.GREATER_THAN -> "超过"
            MonitorOperator.GREATER_EQUAL -> "达到"
            MonitorOperator.LESS_THAN -> "低于"
        }
        return "${draft.schedule.label}「$who」${draft.metric.label}$cmp ${draft.threshold} $unit 时提醒"
    }
}
