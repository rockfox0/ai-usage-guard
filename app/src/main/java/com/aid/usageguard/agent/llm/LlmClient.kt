package com.aid.usageguard.agent.llm

import org.json.JSONObject

/**
 * 模型发起的一次工具调用。
 *
 * @param id 协议侧的调用 id（OpenAI 的 tool_call id / Anthropic 的 tool_use id），
 *           工具结果必须带回同一个 id。
 * @param name 工具名（必须在 ToolRegistry 白名单内）。
 * @param arguments 模型给出的参数 JSON 字符串，尚未校验。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * 与具体协议无关的对话消息。
 * OpenAI / Anthropic 两个客户端各自负责把 [LlmMessage] 序列化成自己的 wire format。
 */
data class LlmMessage(
    val role: String,
    val content: String = "",
    /** 仅 assistant 消息：本轮的 tool_calls */
    val toolCalls: List<ToolCall> = emptyList(),
    /** 仅 tool 消息：对应的调用 id */
    val toolCallId: String? = null,
    /** 仅 tool 消息：工具名（Anthropic 不需要，仅用于日志/排查） */
    val toolName: String? = null,
) {
    val isToolResult: Boolean get() = role == ROLE_TOOL

    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"

        fun system(text: String): LlmMessage = LlmMessage(ROLE_SYSTEM, text)

        fun user(text: String): LlmMessage = LlmMessage(ROLE_USER, text)

        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()): LlmMessage =
            LlmMessage(ROLE_ASSISTANT, text, toolCalls)

        fun tool(callId: String?, name: String?, output: String): LlmMessage =
            LlmMessage(ROLE_TOOL, output, toolCallId = callId, toolName = name)
    }
}

/** 发给模型的工具声明（JSON Schema 直接复用本地工具定义）。 */
data class LlmToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

/** 模型一次响应（文本 + 可能的工具调用）。 */
data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val stopReason: String? = null,
) {
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()
}

/** LLM 协议客户端。实现必须把网络/HTTP/解析错误包成 [LlmException]。 */
interface LlmClient {
    /** 仅用于日志和界面提示 */
    val modelName: String

    /**
     * 发起一次非流式补全。
     *
     * @param system 系统提示词（Anthropic 走顶层 system 字段，OpenAI 走 system 消息）
     * @param messages 历史消息（不含 system）
     * @param tools 可用工具；为空表示不启用 function calling
     * @param maxTokens 覆盖本次请求的 max_tokens；null 用客户端默认值
     *                  （quickHint 这类一句话任务会传 80，避免生成过长内容）
     */
    suspend fun complete(
        system: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        maxTokens: Int? = null,
    ): LlmResponse
}

/** 模型接口调用失败（网络 / HTTP 状态 / 响应解析）。 */
class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 规范化用户填写的 BaseURL：
 * `https://api.deepseek.com` → `https://api.deepseek.com/v1`
 * `https://api.deepseek.com/v1` → 原样
 * `https://host/v1/chat/completions` → `https://host/v1`
 * `https://host/v1/messages` → `https://host/v1`
 */
fun normalizeApiBase(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val stripped = trimmed
        .removeSuffix("/chat/completions")
        .removeSuffix("/messages")
        .trimEnd('/')
    return if (stripped.endsWith("/v1")) stripped else "$stripped/v1"
}
