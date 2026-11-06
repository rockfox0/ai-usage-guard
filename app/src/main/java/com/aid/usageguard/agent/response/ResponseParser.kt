package com.aid.usageguard.agent.response

import org.json.JSONArray
import org.json.JSONObject

/**
 * 结构化回复解析器（v1.9 Phase 4，纯函数）：
 * 从模型输出里找出最后一个包含 `blocks` 的 ```json 代码块，
 * 解析成白名单 [ResponseBlock]；任何异常都返回 null（调用方保留原始 Markdown）。
 */
object ResponseParser {

    const val MAX_BLOCKS = 6
    private val ALLOWED_CHARTS = setOf("daily_usage_7d")

    /** 解析结果：正文（已移除 JSON 块）+ 结构化内容 */
    data class Extracted(val text: String, val response: AgentResponse, val failure: ParseFailure? = null)

    /** 找到并剥离 JSON 块；解析失败返回 null（正文原样保留，不丢内容） */
    fun extract(text: String): Extracted? {
        val blocks = findJsonBlocks(text)
        for ((start, end) in blocks.asReversed()) {
            val raw = text.substring(start, end)
            val response = parse(raw) ?: continue
            val stripped = (text.substring(0, start) + text.substring(end))
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
            return Extracted(text = stripped, response = response)
        }
        // 回退：模型有时输出**裸 JSON**（没有 ```json 围栏）；用括号配对找到含 blocks 的那个对象
        val blocksIdx = text.lastIndexOf("\"blocks\"")
        if (blocksIdx >= 0) {
            val open = text.lastIndexOf('{', blocksIdx)
            if (open >= 0) {
                var depth = 0
                var close = -1
                for (i in open until text.length) {
                    when (text[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                close = i
                                break
                            }
                        }
                    }
                }
                if (close > open) {
                    val raw = text.substring(open, close + 1)
                    parse(raw)?.let { response ->
                        val stripped = (text.substring(0, open) + text.substring(close + 1))
                            .replace(Regex("\\n{3,}"), "\n\n")
                            .trim()
                        return Extracted(text = stripped, response = response)
                    }
                }
            }
        }
        return null
    }

    /** 纯解析：只接受白名单 block，超限/未知类型/空内容都判失败（返回 null） */
    fun parse(rawJson: String): AgentResponse? {
        val obj = runCatching { JSONObject(extractJsonObject(rawJson)) }.getOrNull() ?: return null
        val blocksArray = obj.optJSONArray("blocks") ?: return null
        if (blocksArray.length() == 0 || blocksArray.length() > MAX_BLOCKS) return null

        val blocks = ArrayList<ResponseBlock>()
        for (i in 0 until blocksArray.length()) {
            val item = blocksArray.optJSONObject(i) ?: continue
            val block = parseBlock(item) ?: return null // 任何一个非法 → 整体回退
            blocks += block
        }
        if (blocks.isEmpty()) return null
        val title = obj.optString("title").trim().takeIf { it.isNotEmpty() }
        return AgentResponse(title = title, blocks = blocks)
    }

    private fun parseBlock(item: JSONObject): ResponseBlock? {
        return when (item.optString("type")) {
        "text" -> item.optString("text").trim().takeIf { it.isNotEmpty() }?.let { ResponseBlock.Text(it) }

        "statistic" -> {
            val title = item.optString("title").trim()
            val items = parseStatItems(item.optJSONArray("items"))
            if (title.isEmpty() || items.isEmpty()) null else ResponseBlock.StatisticCard(title, items)
        }

        "insight" -> {
            val title = item.optString("title").trim()
            if (title.isEmpty()) return null
            ResponseBlock.InsightCard(
                title = title,
                severity = item.optString("severity", "info").takeIf { it in setOf("info", "low", "medium", "high") } ?: "info",
                detail = item.optString("detail").trim(),
            )
        }

        "recommendation" -> {
            val title = item.optString("title").trim()
            if (title.isEmpty()) return null
            ResponseBlock.RecommendationCard(
                title = title,
                detail = item.optString("detail").trim(),
                requiresConfirmation = item.optBoolean("requires_confirmation", false),
            )
        }

        "comparison" -> {
            val title = item.optString("title").trim()
            val rows = ArrayList<ComparisonRow>()
            item.optJSONArray("rows")?.let { arr ->
                for (j in 0 until arr.length()) {
                    if (rows.size >= 6) break
                    val row = arr.optJSONObject(j) ?: continue
                    val label = row.optString("label").trim()
                    if (label.isEmpty()) continue
                    rows += ComparisonRow(
                        label = label,
                        left = row.optString("left").trim(),
                        right = row.optString("right").trim(),
                    )
                }
            }
            if (title.isEmpty() || rows.isEmpty()) null else ResponseBlock.ComparisonBlock(title, rows)
        }

        "chart" -> {
            val chart = item.optString("chart").trim()
            val title = item.optString("title").trim()
            if (chart !in ALLOWED_CHARTS || title.isEmpty()) null else ResponseBlock.ChartBlock(chart, title)
        }

        "follow_up" -> {
            val label = item.optString("label").trim()
            val prompt = item.optString("prompt").trim()
            if (label.isEmpty() || prompt.isEmpty()) null else ResponseBlock.FollowUpAction(label, prompt)
        }

        else -> null
        }
    }

    private fun parseStatItems(array: JSONArray?): List<StatItem> {
        if (array == null) return emptyList()
        val items = ArrayList<StatItem>()
        for (i in 0 until array.length()) {
            if (items.size >= 6) break
            val obj = array.optJSONObject(i) ?: continue
            val label = obj.optString("label").trim()
            val value = obj.optString("value").trim()
            if (label.isEmpty() || value.isEmpty()) continue
            items += StatItem(label, value)
        }
        return items
    }

    /** 找所有 ```json ... ``` 代码块在原文中的区间 [start, end) */
    private fun findJsonBlocks(text: String): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var index = 0
        while (true) {
            val open = text.indexOf("```", index)
            if (open < 0) break
            val langEnd = text.indexOf('\n', open)
            if (langEnd < 0) break
            val header = text.substring(open + 3, langEnd).trim().lowercase()
            if (header != "json" && header != "") {
                index = langEnd
                continue
            }
            val close = text.indexOf("```", langEnd)
            if (close < 0) break
            result += (open to (close + 3))
            index = close + 3
        }
        return result
    }

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }
}
