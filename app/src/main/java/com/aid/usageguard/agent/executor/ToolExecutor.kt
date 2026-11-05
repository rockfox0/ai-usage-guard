package com.aid.usageguard.agent.executor

import com.aid.usageguard.agent.llm.ToolCall
import com.aid.usageguard.agent.tools.AgentTool
import com.aid.usageguard.agent.tools.TaskScope
import com.aid.usageguard.agent.tools.ToolArgumentException
import com.aid.usageguard.agent.tools.ToolRegistry
import com.aid.usageguard.agent.tools.ToolScopePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 工具执行器：所有「模型 → 本地函数」的调用都必须经过这里。
 *
 * 负责（v1.7 方案 6.1：执行器独立验证，不依赖提示词 / 模型自报）：
 * 1. 白名单：只执行 [ToolRegistry] 里注册过的工具；
 * 2. 任务权限：按 [TaskScope] + [ToolKind][com.aid.usageguard.agent.tools.ToolKind] 独立校验，
 *    报告 / 后台 / 洞察任务只能跑只读工具（QUERY）；
 * 3. 限额：单次任务最多 [maxCallsPerTask] 次工具调用；
 * 4. 参数校验：JSON 解析 + JSON Schema（未知字段、必填、类型、枚举、范围、数组元素，
 *    以及 date / app_ref 业务格式）；
 * 5. 超时与取消：单次工具超时可被调用方按任务剩余预算收紧；取消时立即抛
 *    [CancellationException]，不吞掉。
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
     * @param scope 当前任务权限域；执行器会独立校验工具是否允许执行
     * @param timeoutMsOverride 按任务剩余预算收紧后的超时（null 用默认）
     */
    suspend fun execute(
        call: ToolCall,
        usedCalls: Int,
        scope: TaskScope = TaskScope.CHAT,
        timeoutMsOverride: Long? = null,
        /**
         * 当前任务已加载/可见的工具名（v1.9 Phase 1 能力门禁）。
         * 非 null 时，未加载能力的工具即使已注册也会被拒绝执行；
         * 仅靠「不下发 schema」不足以防模型凭历史工具名直接调用。
         */
        allowedToolNames: Set<String>? = null,
    ): ToolExecution {
        val started = System.currentTimeMillis()
        val effectiveTimeout = (timeoutMsOverride ?: timeoutMs).coerceAtLeast(1L)

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

        // 能力门禁：CHAT 域只允许「已加载能力 + 元工具」里的工具，防止模型绕过 schema 直接调
        if (allowedToolNames != null && call.name !in allowedToolNames) {
            return failure(
                call,
                started,
                "工具「${call.name}」所属能力尚未加载。请先调用 discover_capabilities 搜索、load_capability 加载后再调用。",
            )
        }

        // 任务权限：执行器独立拦截；报告/后台即使模型硬编调用也会被拒绝
        if (!ToolScopePolicy.allows(scope, tool.kind)) {
            return failure(
                call,
                started,
                "当前任务权限（${scope.label}）不允许调用「${tool.name}」（${tool.kind}）；" +
                    "报告/后台任务只能使用只读查询工具（QUERY）。",
            )
        }

        val arguments = try {
            JSONObject(call.arguments.ifBlank { "{}" })
        } catch (e: Exception) {
            return failure(call, started, "参数不是合法 JSON Object：${e.message?.take(120)}")
        }

        validateArguments(tool, arguments)?.let { message ->
            return failure(call, started, message)
        }

        return try {
            val data = withTimeout(effectiveTimeout) { tool.execute(arguments) }
            val output = truncate(data.toString())
            ToolExecution(
                toolName = call.name,
                callId = call.id,
                ok = true,
                output = output,
                elapsedMs = System.currentTimeMillis() - started,
            )
        } catch (e: TimeoutCancellationException) {
            failure(call, started, "工具执行超时（${effectiveTimeout}ms），请换一种更小的查询后再试。")
        } catch (e: CancellationException) {
            // Agent 任务被取消：必须继续向上抛，不能当成工具错误
            throw e
        } catch (e: ToolArgumentException) {
            failure(call, started, e.message ?: "参数不合法")
        } catch (e: Exception) {
            failure(call, started, "工具执行失败：${e.message?.take(160) ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // JSON Schema 子集校验（独立于提示词）
    // ------------------------------------------------------------------

    /** 返回 null 表示通过；否则返回给模型看的错误说明。 */
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
            val value = arguments.opt(key)
            if (value == null || value == JSONObject.NULL) continue

            if (expected.isNotBlank()) {
                typeError(expected, value, key)?.let { return it }
            }

            when (expected) {
                "string" -> {
                    if (value !is String) continue
                    enumError(property, value, key)?.let { return it }
                    formatError(property.optString("format"), value, key)?.let { return it }
                }

                "integer", "number" -> {
                    val number = (value as? Number)?.toDouble() ?: continue
                    (property.opt("minimum") as? Number)?.let {
                        if (number < it.toDouble()) return "参数校验失败：$key 不能小于 ${it}。"
                    }
                    (property.opt("maximum") as? Number)?.let {
                        if (number > it.toDouble()) return "参数校验失败：$key 不能大于 ${it}。"
                    }
                }

                "array" -> {
                    val array = value as? JSONArray ?: continue
                    val items = property.optJSONObject("items") ?: continue
                    val itemType = items.optString("type")
                    for (index in 0 until array.length()) {
                        val element = array.opt(index)
                        if (itemType.isNotBlank()) {
                            typeError(itemType, element, "$key[$index]")?.let { return it }
                        }
                        if (element is String) {
                            enumError(items, element, "$key[$index]")?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun typeError(expected: String, value: Any?, key: String): String? {
        val matches = when (expected) {
            "string" -> value is String
            "integer" -> value is Int || value is Long
            "number" -> value is Number
            "boolean" -> value is Boolean
            "array" -> value is JSONArray
            "object" -> value is JSONObject
            else -> true
        }
        return if (matches) {
            null
        } else {
            "参数校验失败：$key 应为 $expected 类型（收到 ${value?.javaClass?.simpleName ?: "null"}）。"
        }
    }

    private fun enumError(schema: JSONObject, value: String, key: String): String? {
        val enumValues = schema.optJSONArray("enum") ?: return null
        val allowed = (0 until enumValues.length())
            .mapNotNull { enumValues.optString(it).takeIf { text -> text.isNotBlank() } }
        if (allowed.isEmpty() || value in allowed) return null
        return "参数校验失败：$key 只能是 ${allowed.joinToString(" / ")}，收到「$value」。"
    }

    /** 业务格式：date（yyyy-MM-dd 且真实存在）/ app_ref（包名或应用名，非空且无控制字符）。 */
    private fun formatError(format: String, value: String, key: String): String? = when (format) {
        "date" -> {
            val parsed = runCatching { LocalDate.parse(value) }.getOrNull()
            if (parsed == null) "参数校验失败：$key 需要 yyyy-MM-dd 格式的有效日期，收到「$value」。" else null
        }

        "app_ref" -> {
            val trimmed = value.trim()
            when {
                trimmed.isEmpty() -> "参数校验失败：$key 不能为空。"
                trimmed.length > 120 -> "参数校验失败：$key 过长（最多 120 字符）。"
                trimmed.any { it.isISOControl() } -> "参数校验失败：$key 含非法字符。"
                else -> null
            }
        }

        else -> null
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
