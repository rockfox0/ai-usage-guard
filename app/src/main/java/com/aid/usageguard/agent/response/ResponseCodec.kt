package com.aid.usageguard.agent.response

import org.json.JSONArray
import org.json.JSONObject

/**
 * 结构化回复的持久化编码（v1.10，纯函数）。
 *
 * 助手页的卡片之前只存在内存里，重进页面就只剩文字；现在把 [AgentResponse]
 * 以 JSON 存到 `agent_message.structuredJson`，历史加载时还原。
 * 解码复用 [ResponseParser.parse]（同一套白名单校验），坏数据自动忽略。
 */
object ResponseCodec {

    fun encode(response: AgentResponse): String = JSONObject().apply {
        response.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
        put("blocks", JSONArray().apply { response.blocks.forEach { put(encodeBlock(it)) } })
    }.toString()

    fun decode(json: String?): AgentResponse? {
        if (json.isNullOrBlank()) return null
        return runCatching { ResponseParser.parse(json) }.getOrNull()
    }

    private fun encodeBlock(block: ResponseBlock): JSONObject = when (block) {
        is ResponseBlock.Text -> JSONObject().put("type", "text").put("text", block.text)

        is ResponseBlock.StatisticCard -> JSONObject().put("type", "statistic").put("title", block.title)
            .put("items", JSONArray().apply {
                block.items.forEach { item -> put(JSONObject().put("label", item.label).put("value", item.value)) }
            })

        is ResponseBlock.InsightCard -> JSONObject().put("type", "insight")
            .put("title", block.title).put("severity", block.severity).put("detail", block.detail)

        is ResponseBlock.RecommendationCard -> JSONObject().put("type", "recommendation")
            .put("title", block.title).put("detail", block.detail)
            .put("requires_confirmation", block.requiresConfirmation)

        is ResponseBlock.ComparisonBlock -> JSONObject().put("type", "comparison")
            .put("title", block.title)
            .put("rows", JSONArray().apply {
                block.rows.forEach { row ->
                    put(JSONObject().put("label", row.label).put("left", row.left).put("right", row.right))
                }
            })

        is ResponseBlock.ChartBlock -> JSONObject().put("type", "chart")
            .put("chart", block.chart).put("title", block.title)

        is ResponseBlock.FollowUpAction -> JSONObject().put("type", "follow_up")
            .put("label", block.label).put("prompt", block.prompt)
    }
}
