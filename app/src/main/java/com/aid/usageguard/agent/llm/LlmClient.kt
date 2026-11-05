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

    /**
     * 流式补全（v1.7 方案 §8.2）：边收边通过 [onTextDelta] 回调增量文本，
     * 返回值与 [complete] 相同（完整文本 + 累积好的工具调用），上层逻辑不需要区分流式/非流式。
     *
     * 约定：
     * - 只回调**可见文本**，不回调模型内部推理；
     * - 工具调用参数必须等到流正常结束后才完整解析，半段 JSON 直接判失败、绝不执行；
     * - 网络分片、多个工具交错分片、结束标记、错误事件、超时与取消由实现处理；
     * - 流式不可用（服务不支持 / 建连失败 / 空流）抛 [LlmStreamUnavailableException]，
     *   上层只有在「本任务还没有任何副作用」时才允许降级为非流式重试一次。
     *
     * 默认实现直接退化成非流式，兼容未来新增的客户端。
     */
    suspend fun completeStream(
        system: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        maxTokens: Int? = null,
        onTextDelta: (String) -> Unit,
    ): LlmResponse {
        val response = complete(system, messages, tools, maxTokens)
        if (response.text.isNotBlank()) onTextDelta(response.text)
        return response
    }
}

/** 模型接口调用失败（网络 / HTTP 状态 / 响应解析）。 */
open class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 流式不可用：服务不支持流式 / 建连失败 / 空流。
 * 只有这类错误才允许上层在「本任务无副作用」时降级为非流式重试一次；
 * 流中途断线属于 [LlmException]，不得自动重放任务。
 */
class LlmStreamUnavailableException(message: String, cause: Throwable? = null) : LlmException(message, cause)

/**
 * 流式工具调用的分片累加器：按 provider 的 index（或 id）关联，
 * 把 `id` / `name` / `arguments` 的多个分片拼回完整调用。
 */
internal class ToolCallAccumulator {
    var id: String = ""
    var name: String = ""
    val arguments: StringBuilder = StringBuilder()
}

/**
 * 把累积好的分片转换成 [ToolCall]。
 * 参数必须是完整 JSON：半段/非法 JSON 直接抛 [LlmException]，绝不把残缺参数交给执行器。
 */
internal fun finalizedToolCalls(accumulators: Map<Int, ToolCallAccumulator>): List<ToolCall> {
    val result = ArrayList<ToolCall>()
    accumulators.toSortedMap().forEach { (index, acc) ->
        val name = acc.name.trim()
        if (name.isEmpty()) return@forEach
        val arguments = acc.arguments.toString().ifBlank { "{}" }
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
            ?: throw LlmException("模型返回的工具参数不是完整 JSON（$name），本次响应已丢弃，不执行任何操作。")
        result.add(
            ToolCall(
                id = acc.id.ifBlank { "call_$index" },
                name = name,
                arguments = parsed.toString(),
            ),
        )
    }
    return result
}

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
