package com.aid.usageguard.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具结果 → 人类可读摘要（v1.9.3）。
 *
 * 修复：轮次/预算耗尽时 `fallbackFromTools` 以前直接把工具 JSON 原文拼进对话，
 * 用户会看到 `{"executed":false,"requiresConfirmation":true,...}` 这种原始数据。
 * 这里把 JSON 转成简短中文要点；解析失败也只给一句"已获取数据"，绝不回显原始 JSON。
 */
object ToolEvidence {

    /** 一次工具调用的可读摘要（无可用信息返回 null） */
    fun summarise(output: String, maxChars: Int = 240): String? {
        if (output.isBlank()) return null
        val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return null
        val message = parsed.optString("message").trim()
        if (message.isNotEmpty()) return message.take(maxChars)
        val error = parsed.optString("error").trim()
        if (error.isNotEmpty()) return "执行失败：${error.take(maxChars)}"

        val parts = ArrayList<String>()
        val keys = parsed.keys()
        while (keys.hasNext() && parts.size < 4) {
            val key = keys.next()
            if (key in IGNORED_KEYS) continue
            when (val value = parsed.opt(key)) {
                is String -> if (value.isNotBlank() && value.length <= 40) parts += "$key: $value"
                is Number, is Boolean -> parts += "$key: $value"
                is JSONArray -> parts += "$key: ${value.length()} 项"
                is JSONObject -> Unit
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString("；").take(maxChars)
    }

    /** 轮次/预算耗尽时的兜底正文：说明已停止 + 已查到的要点（不含原始 JSON） */
    fun fallbackText(toolNames: List<String>, outputs: List<String>, reason: String): String = buildString {
        append("（$reason，先给你已查到的信息）\n")
        if (toolNames.isNotEmpty()) {
            append("已查询：").append(toolNames.distinct().joinToString("、")).append('\n')
        }
        val lines = outputs.mapNotNull { summarise(it) }.distinct().take(4)
        if (lines.isEmpty()) {
            append("这次没有拿到可展示的数据，可以再问我一次，或把范围说具体一点。")
        } else {
            lines.forEach { append("- ").append(it).append('\n') }
            append("如果需要完整结论，可以再问我一次（我会重新查询并整理）。")
        }
    }.trim()

    private val IGNORED_KEYS = setOf(
        "opId", "type", "status", "expiresAt", "before", "after", "changed", "params",
        "requiresConfirmation", "autoExecuted", "success",
    )
}
