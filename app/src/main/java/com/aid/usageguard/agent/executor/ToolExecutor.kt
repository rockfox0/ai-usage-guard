package com.aid.usageguard.agent.executor

import com.aid.usageguard.agent.llm.ToolCall
import com.aid.usageguard.agent.tools.AgentTool
import com.aid.usageguard.agent.tools.ToolArgumentException
import com.aid.usageguard.agent.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具执行器：所有「模型 → 本地函数」的调用都必须经过这里。
 *
 * 负责四件事：
 * 1. 白名单：只执行 [ToolRegistry] 里注册过的工具；
 * 2. 限额：单次任务最多 [maxCallsPerTask] 次工具调用，防止死循环/刷接口；
 * 3. 参数校验：JSON 解析 + JSON Schema（未知参数、缺少必填、类型不符）→ 错误回传模型；
 * 4. 超时与取消：单次工具 [timeoutMs] 超时；协程取消时立即抛 [CancellationException]，不吞掉。
 *
 * 执行失败不会抛给上层，而是返回 `{"error": ...}` 作为工具结果，让模型自己修正。
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxCallsPerTask: Int = DEFAULT_MAX_CALLS_PER_TASK,
    private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
) {

    /** 一次工具执行的结果（成功或失败都落在同一个结构里）。 */
    data class ToolExecution(
        val toolName: String,
        val callId: String,
        val ok: Boolean,
        /** 回传给模型的内容（JSON 字符串），超长会被截断 */
        val output: String,
        val elapsedMs: Long,
    )

    /**
     * @param usedCalls 本任务中「已经执行过」的工具调用次数（由调用方维护）
     */
    suspend fun execute(call: ToolCall, usedCalls: Int): ToolExecution {
        val started = System.currentTimeMillis()

        if (usedCalls >= maxCallsPerTask) {
            return failure(
                call,
                started,
                "已达到单次任务的工具调用上限（$maxCallsPerTask 次），请直接基于已有信息回答。",
            )
        }

        val tool = registry.find(call.name)
            ?: return failure(
                call,
                started,
                "工具「${call.name}」不在白名单内。可用工具：" + registry.names.joinToString("、"),
            )

        val arguments = try {
            JSONObject(call.arguments.ifBlank { "{}" })
        } catch (e: Exception) {
            return failure(call, started, "参数不是合法 JSON Object：${e.message?.take(120)}")
        }

        validateArguments(tool, arguments)?.let { message ->
            return failure(call, started, message)
        }

        return try {
            val data = withTimeout(timeoutMs) { tool.execute(arguments) }
            val output = truncate(data.toString())
            ToolExecution(
                toolName = call.name,
                callId = call.id,
                ok = true,
                output = output,
                elapsedMs = System.currentTimeMillis() - started,
            )
        } catch (e: TimeoutCancellationException) {
            failure(call, started, "工具执行超时（${timeoutMs}ms），请换一种更小的查询后再试。")
        } catch (e: CancellationException) {
            // Agent 任务被取消：必须继续向上抛，不能当成工具错误
            throw e
        } catch (e: ToolArgumentException) {
            failure(call, started, e.message ?: "参数不合法")
        } catch (e: Exception) {
            failure(call, started, "工具执行失败：${e.message?.take(160) ?: e.javaClass.simpleName}")
        }
    }

    /** JSON Schema 子集校验：未知参数 / 必填 / 基础类型。返回 null 表示通过。 */
    private fun validateArguments(tool: AgentTool, arguments: JSONObject): String? {
        val schema = tool.parameters
        val properties = schema.optJSONObject("properties") ?: JSONObject()

        val unknown = arguments.keys().asSequence()
            .filter { key -> !properties.has(key) }
            .toList()
        if (unknown.isNotEmpty()) {
            val allowed = properties.keys().asSequence().joinToString("、").ifEmpty { "无" }
            return "参数校验失败：不支持参数 ${unknown.joinToString("、")}；允许的参数为：$allowed。" +
                "请重新调用 ${tool.name}。"
        }

        val required = schema.optJSONArray("required")
        if (required != null) {
            for (index in 0 until required.length()) {
                val key = required.optString(index)
                if (key.isNotBlank() && (!arguments.has(key) || arguments.isNull(key))) {
                    return "参数校验失败：缺少必填参数 $key。"
                }
            }
        }

        val keys = arguments.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val property = properties.optJSONObject(key) ?: continue
            val expected = property.optString("type")
            if (expected.isBlank()) continue
            val value = arguments.opt(key)
            if (value == null || value == JSONObject.NULL) continue
            val matches = when (expected) {
                "string" -> value is String
                "integer" -> value is Int || value is Long
                "number" -> value is Number
                "boolean" -> value is Boolean
                "array" -> value is JSONArray
                "object" -> value is JSONObject
                else -> true
            }
            if (!matches) {
                return "参数校验失败：$key 应为 $expected 类型（收到 ${value.javaClass.simpleName}）。"
            }
        }
        return null
    }

    private fun failure(call: ToolCall, startedAt: Long, message: String): ToolExecution {
        val payload = JSONObject().apply {
            put("error", message)
            put("tool", call.name)
        }
        return ToolExecution(
            toolName = call.name,
            callId = call.id,
            ok = false,
            output = payload.toString(),
            elapsedMs = System.currentTimeMillis() - startedAt,
        )
    }

    private fun truncate(text: String): String =
        if (text.length <= maxOutputChars) text else text.take(maxOutputChars) + "…（输出已截断）"

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 20_000L
        const val DEFAULT_MAX_CALLS_PER_TASK: Int = 12
        const val DEFAULT_MAX_OUTPUT_CHARS: Int = 6_000
    }
}
