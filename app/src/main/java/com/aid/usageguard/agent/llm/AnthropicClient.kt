package com.aid.usageguard.agent.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages 协议：`POST {base}/v1/messages` +
 * `x-api-key: <key>` + `anthropic-version: 2023-06-01`。
 *
 * 与 OpenAI 的差别：
 * - system 是顶层字段，不在 messages 里；
 * - 工具调用是 content block（tool_use / tool_result），tool_result 必须放在 user 消息里；
 * - 连续的 tool_result 需要合并进同一条 user 消息。
 */
class AnthropicClient(
    override val modelName: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val defaultMaxTokens: Int = 1200,
    private val temperature: Double = 0.4,
) : LlmClient {

    override suspend fun complete(
        system: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        maxTokens: Int?,
    ): LlmResponse {
        val payload = JSONObject().apply {
            put("model", modelName)
            put("max_tokens", maxTokens ?: defaultMaxTokens)
            put("temperature", temperature)
            if (system.isNotBlank()) put("system", system)
            put("messages", buildMessages(messages))
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    JSONArray().apply {
                        tools.forEach { tool ->
                            put(
                                JSONObject().apply {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("input_schema", tool.parameters)
                                },
                            )
                        }
                    },
                )
            }
        }

        val raw = LlmHttp.postJson(
            url = "$baseUrl/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
            body = payload.toString(),
        )
        return parse(raw)
    }

    /** 把中立消息序列化成一串 content block 形式的 user/assistant 消息。 */
    private fun buildMessages(messages: List<LlmMessage>): JSONArray {
        val turns = ArrayList<JSONObject>()

        fun lastWithRole(role: String): JSONObject? {
            val last = turns.lastOrNull() ?: return null
            return if (last.optString("role") == role) last else null
        }

        fun blocksOf(turn: JSONObject): JSONArray {
            val existing = turn.optJSONArray("content")
            if (existing != null) return existing
            val created = JSONArray()
            turn.put("content", created)
            return created
        }

        fun appendBlock(role: String, block: JSONObject) {
            val turn = lastWithRole(role) ?: JSONObject().apply {
                put("role", role)
                put("content", JSONArray())
            }.also { turns.add(it) }
            blocksOf(turn).put(block)
        }

        fun textBlock(text: String): JSONObject =
            JSONObject().put("type", "text").put("text", text)

        fun toolUseBlock(call: ToolCall): JSONObject = JSONObject().apply {
            put("type", "tool_use")
            put("id", call.id)
            put("name", call.name)
            put("input", parseToolInput(call.arguments))
        }

        messages.forEach { message ->
            when (message.role) {
                LlmMessage.ROLE_SYSTEM -> Unit // system 走顶层字段

                LlmMessage.ROLE_TOOL -> appendBlock(
                    "user",
                    JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", message.toolCallId.orEmpty())
                        put("content", message.content.ifBlank { "（无输出）" })
                    },
                )

                LlmMessage.ROLE_ASSISTANT -> {
                    if (message.content.isNotBlank()) {
                        appendBlock("assistant", textBlock(message.content))
                    }
                    message.toolCalls.forEach { call -> appendBlock("assistant", toolUseBlock(call)) }
                }

                else -> if (message.content.isNotBlank()) {
                    appendBlock("user", textBlock(message.content))
                }
            }
        }

        // Anthropic 要求第一条消息必须是 user；历史窗口可能从 assistant 开始。
        while (turns.isNotEmpty() && turns.first().optString("role") == "assistant") {
            turns.removeAt(0)
        }
        return JSONArray().apply { turns.forEach { put(it) } }
    }

    private fun parseToolInput(arguments: String): JSONObject = try {
        JSONObject(arguments.ifBlank { "{}" })
    } catch (e: Exception) {
        JSONObject()
    }

    private fun parse(raw: String): LlmResponse {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw LlmException("响应不是合法 JSON：${raw.take(160).replace('\n', ' ')}", e)
        }

        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { error.toString().take(200) }
            throw LlmException("接口返回错误：$message")
        }

        val content = root.optJSONArray("content") ?: throw LlmException("响应缺少 content 字段")
        val text = StringBuilder()
        val calls = ArrayList<ToolCall>()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> {
                    val name = block.optString("name").trim()
                    if (name.isNotEmpty()) {
                        val input = block.opt("input") as? JSONObject
                        calls.add(
                            ToolCall(
                                id = block.optString("id").ifBlank { "toolu_$index" },
                                name = name,
                                arguments = input?.toString() ?: "{}",
                            ),
                        )
                    }
                }

                else -> Unit // thinking / 其它 block 忽略
            }
        }

        val stopReason = root.optString("stop_reason").takeIf { it.isNotBlank() && it != "null" }
        return LlmResponse(text = text.toString().trim(), toolCalls = calls, stopReason = stopReason)
    }
}
