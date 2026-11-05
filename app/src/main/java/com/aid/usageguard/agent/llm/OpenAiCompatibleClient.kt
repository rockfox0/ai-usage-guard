package com.aid.usageguard.agent.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容协议：`POST {base}/v1/chat/completions` + `Authorization: Bearer <key>`。
 *
 * 适用于 DeepSeek / OpenAI / 各家中转服务等 OpenAI 兼容接口。
 * 工具调用走标准的 `tools` / `tool_calls` 字段。
 *
 * 流式（v1.7 方案 §8.2）：`stream: true` + SSE `data:` 行；
 * 文本在 `choices[0].delta.content`，工具调用在 `choices[0].delta.tool_calls[]`
 * （按 `index` 关联、按 `id` 识别，`function.arguments` 分片累积）。
 */
class OpenAiCompatibleClient(
    override val modelName: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val temperature: Double = 0.4,
    private val defaultMaxTokens: Int = 1200,
) : LlmClient {

    override suspend fun complete(
        system: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        maxTokens: Int?,
    ): LlmResponse {
        val payload = buildPayload(system, messages, tools, maxTokens, stream = false)
        val raw = LlmHttp.postJson(
            url = "$baseUrl/chat/completions",
            headers = headers("application/json"),
            body = payload.toString(),
        )
        return parse(raw)
    }

    override suspend fun completeStream(
        system: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        maxTokens: Int?,
        onTextDelta: (String) -> Unit,
    ): LlmResponse {
        val payload = buildPayload(system, messages, tools, maxTokens, stream = true)

        val text = StringBuilder()
        val accumulators = mutableMapOf<Int, ToolCallAccumulator>()
        var stopReason: String? = null
        var sawChunk = false

        LlmHttp.postJsonStream(
            url = "$baseUrl/chat/completions",
            headers = headers("text/event-stream"),
            body = payload.toString(),
        ) { line ->
            val trimmed = line.trim()
            // 空行分隔事件、以 ':' 开头的是注释/心跳
            if (trimmed.isEmpty() || trimmed.startsWith(":")) return@postJsonStream
            if (!trimmed.startsWith("data:")) return@postJsonStream

            val data = trimmed.removePrefix("data:").trim()
            if (data.isEmpty()) return@postJsonStream
            if (data == "[DONE]") return@postJsonStream

            // 单条 SSE 的 data 必须是完整 JSON；不是的话是协议噪声/保活，跳过。
            // 工具参数是跨多条分片累积的，最后统一校验，这里不做半段解析。
            val root = runCatching { JSONObject(data) }.getOrNull() ?: return@postJsonStream
            sawChunk = true

            root.optJSONObject("error")?.let { error ->
                val message = error.optString("message").ifBlank { error.toString().take(200) }
                throw LlmException("接口返回错误：$message")
            }

            val choices = root.optJSONArray("choices") ?: return@postJsonStream
            val first = choices.optJSONObject(0) ?: return@postJsonStream

            first.optString("finish_reason")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { stopReason = it }

            val delta = first.optJSONObject("delta") ?: return@postJsonStream

            // 注意：工具调用分片里 content 是 JSON null，optString 会返回字符串 "null"，
            // 必须显式判类型，否则会把 "null" 当正文拼进回复。
            val content = delta.opt("content").takeIf { it is String } as String? ?: ""
            if (content.isNotEmpty()) {
                text.append(content)
                onTextDelta(content)
            }

            val toolDeltas = delta.optJSONArray("tool_calls") ?: return@postJsonStream
            for (index in 0 until toolDeltas.length()) {
                val item = toolDeltas.optJSONObject(index) ?: continue
                val slot = item.optInt("index", index)
                val acc = accumulators.getOrPut(slot) { ToolCallAccumulator() }

                item.optString("id").takeIf { it.isNotBlank() }?.let { id ->
                    if (acc.id.isBlank()) acc.id = id
                }

                val function = item.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (name.isNotEmpty()) {
                    if (acc.name.isBlank()) {
                        acc.name = name
                    } else if (name != acc.name && name.startsWith(acc.name)) {
                        // name 也可能被拆成多段（部分中转服务会这样）
                        acc.name = name
                    }
                }
                acc.arguments.append(function.optString("arguments"))
            }
        }

        if (!sawChunk) throw LlmStreamUnavailableException("流式响应为空")
        return LlmResponse(
            text = text.toString().trim(),
            toolCalls = finalizedToolCalls(accumulators),
            stopReason = stopReason,
        )
    }

    private fun headers(accept: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
        "Accept" to accept,
    )

    private fun buildPayload(
        system: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        maxTokens: Int?,
        stream: Boolean,
    ): JSONObject = JSONObject().apply {
        put("model", modelName)
        put("temperature", temperature)
        put("max_tokens", maxTokens ?: defaultMaxTokens)
        put("messages", buildMessages(system, messages))
        if (tools.isNotEmpty()) {
            put("tools", buildTools(tools))
            put("tool_choice", "auto")
        }
        if (stream) put("stream", true)
    }

    private fun buildTools(tools: List<LlmToolSpec>): JSONArray = JSONArray().apply {
        tools.forEach { tool ->
            put(
                JSONObject().apply {
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        },
                    )
                },
            )
        }
    }

    private fun buildMessages(system: String, messages: List<LlmMessage>): JSONArray =
        JSONArray().apply {
            if (system.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", system))
            }
            messages.forEach { message ->
                when (message.role) {
                    LlmMessage.ROLE_TOOL -> put(
                        JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", message.toolCallId.orEmpty())
                            put("content", message.content.ifBlank { "（无输出）" })
                        },
                    )

                    LlmMessage.ROLE_ASSISTANT -> put(
                        JSONObject().apply {
                            put("role", "assistant")
                            if (message.content.isNotBlank()) put("content", message.content)
                            if (message.toolCalls.isNotEmpty()) {
                                put(
                                    "tool_calls",
                                    JSONArray().apply {
                                        message.toolCalls.forEach { call ->
                                            put(
                                                JSONObject().apply {
                                                    put("id", call.id)
                                                    put("type", "function")
                                                    put(
                                                        "function",
                                                        JSONObject().apply {
                                                            put("name", call.name)
                                                            put("arguments", call.arguments.ifBlank { "{}" })
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        },
                    )

                    else -> put(
                        JSONObject().apply {
                            put("role", message.role.ifBlank { "user" })
                            put("content", message.content)
                        },
                    )
                }
            }
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

        val choices = root.optJSONArray("choices") ?: throw LlmException("响应缺少 choices 字段")
        if (choices.length() == 0) throw LlmException("模型没有返回任何候选结果")

        val first = choices.optJSONObject(0) ?: throw LlmException("choices[0] 格式异常")
        val message = first.optJSONObject("message") ?: throw LlmException("响应缺少 message 字段")
        val text = (message.opt("content").takeIf { it is String } as String? ?: "").trim()

        val calls = ArrayList<ToolCall>()
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val item = toolCalls.optJSONObject(index) ?: continue
                val function = item.optJSONObject("function") ?: continue
                val name = (function.opt("name").takeIf { it is String } as String? ?: "").trim()
                if (name.isEmpty()) continue
                calls.add(
                    ToolCall(
                        id = item.optString("id").ifBlank { "call_$index" },
                        name = name,
                        arguments = (function.opt("arguments").takeIf { it is String } as String? ?: "").ifBlank { "{}" },
                    ),
                )
            }
        }

        val stopReason = first.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
        return LlmResponse(text = text, toolCalls = calls, stopReason = stopReason)
    }
}
