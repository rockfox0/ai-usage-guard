package com.aid.usageguard.agent.response

/**
 * 结构化回复（v1.9 Phase 4）：模型可以额外产出一个 JSON 块，由本地解析成白名单 UI 组件。
 *
 * 安全边界：
 * - block 类型是本地白名单（[ResponseBlock]），模型不能自定义 UI / 代码；
 * - 统计数字与图表数据由本地工具/本地聚合提供，模型只负责组织展示；
 * - 解析失败（未知类型/超限/坏 JSON）直接回退纯 Markdown 文本。
 */
data class AgentResponse(
    val title: String? = null,
    val blocks: List<ResponseBlock>,
)

sealed interface ResponseBlock {
    data class Text(val text: String) : ResponseBlock

    /** 关键数字（模型只能引用工具结果里的数字） */
    data class StatisticCard(val title: String, val items: List<StatItem>) : ResponseBlock

    data class InsightCard(
        val title: String,
        val severity: String = "info",
        val detail: String = "",
    ) : ResponseBlock

    data class RecommendationCard(
        val title: String,
        val detail: String = "",
        /** true 表示该建议需要生成待确认草案，由用户确认后生效 */
        val requiresConfirmation: Boolean = false,
    ) : ResponseBlock

    data class ComparisonBlock(val title: String, val rows: List<ComparisonRow>) : ResponseBlock

    /** 图表只声明类型；数据由本地聚合填充（renderer 从 UsageRepository 取） */
    data class ChartBlock(val chart: String, val title: String) : ResponseBlock

    data class FollowUpAction(val label: String, val prompt: String) : ResponseBlock
}

data class StatItem(val label: String, val value: String)

data class ComparisonRow(val label: String, val left: String, val right: String)

/** 解析失败的分类（仅用于日志/测试断言） */
enum class ParseFailure {
    NO_JSON,
    BAD_JSON,
    UNKNOWN_BLOCK,
    TOO_MANY_BLOCKS,
    EMPTY_BLOCKS,
}
