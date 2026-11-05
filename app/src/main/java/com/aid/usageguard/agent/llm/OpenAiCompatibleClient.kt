package com.aid.usageguard.agent.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容协议：`POST {base}/v1/chat/completions` + `Authorization: Bearer <key>`。
 *
 * 适用于 DeepSeek / OpenAI / 各家中转服务等 OpenAI 兼容接口。
 * 工具调用走标准的 `tools` / `tool_calls` 字段。
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
        val payload = JSONObject().apply {
            put("model", modelName)
            put("temperature", temperature)
            put("max_tokens", maxTokens ?: defaultMaxTokens)
            put("messages", buildMessages(system, messages))
            if (tools.isNotEmpty()) {
                put("tools", buildTools(tools))
                put("tool_choice", "auto")
            }
        }

        val raw = LlmHttp.postJson(
            url = "$baseUrl/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
            body = payload.toString(),
        )
        return parse(raw)
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
        val text = message.optString("content", "").trim()

        val calls = ArrayList<ToolCall>()
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val item = toolCalls.optJSONObject(index) ?: continue
                val function = item.optJSONObject("function") ?: continue
                val name = function.optString("name").trim()
                if (name.isEmpty()) continue
                calls.add(
                    ToolCall(
                        id = item.optString("id").ifBlank { "call_$index" },
                        name = name,
                        arguments = function.optString("arguments").ifBlank { "{}" },
                    ),
                )
            }
        }

        val stopReason = first.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
        return LlmResponse(text = text, toolCalls = calls, stopReason = stopReason)
    }
}
