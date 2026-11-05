package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.Container
import com.aid.usageguard.data.repo.ActionDraftRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * 待确认操作查询工具（v1.7 方案 §6.2 / §8.1）。
 *
 * 只读：读取 `pending_action` 表中 PENDING 状态的草案，用于聊天里回答
 * 「我还有什么待确认的操作 / 刚才那个改设置生效了吗」。
 * 报告 / 后台 scope 也允许调用（QUERY），但不会触发确认或执行。
 */
class PendingActionTools(
    /** 延迟取 Container，避免 AppWiring 增加注入参数 */
    private val actionDraftsProvider: () -> ActionDraftRepository = { Container.actionDrafts },
) {

    val all: List<AgentTool> = listOf(GetPendingActionsTool())

    private inner class GetPendingActionsTool : AgentTool {
        override val name: String = "get_pending_actions"
        override val description: String =
            "查询待确认操作草案（opId/类型/参数/前后值/状态/过期时间）。只读，不确认不执行。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "limit" to JsonSchema.integer("最多返回多少条，1-20，默认 10", minimum = 1, maximum = 20),
            ),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val limit = (arguments.intArg("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

            val drafts = runCatching { actionDraftsProvider() }.getOrElse {
                throw ToolArgumentException("待确认操作服务尚未就绪，请稍后再试。")
            }
            val pending = runCatching { drafts.pending(limit) }.getOrElse {
                throw ToolArgumentException("读取待确认操作失败：${it.message?.take(120) ?: "未知错误"}")
            }

            val now = System.currentTimeMillis()
            val actions = JSONArray()
            pending.forEach { item ->
                actions.put(
                    JSONObject().apply {
                        put("opId", item.opId)
                        put("type", item.type)
                        put("packages", JSONArray(parsePackages(item.packages)))
                        put("params", parseJson(item.paramsJson))
                        put("before", parseJson(item.beforeJson))
                        put("after", parseJson(item.afterJson))
                        put("status", item.status)
                        put("createdAt", item.createdAt)
                        put("expiresAt", item.expiresAt)
                        put("expiresInMinutes", ((item.expiresAt - now) / 60_000L).coerceAtLeast(0L))
                    },
                )
            }

            return JSONObject().apply {
                put("executed", false)
                put("count", pending.size)
                put("actions", actions)
                put(
                    "message",
                    if (pending.isEmpty()) {
                        "当前没有待确认的操作。"
                    } else {
                        "当前有 ${pending.size} 个待确认操作（草案 5 分钟内有效，需在应用内确认后才会生效）。"
                    },
                )
                put("settingsUrlHint", CONFIRM_HINT)
            }
        }
    }

    private fun parsePackages(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseJson(raw: String): JSONObject =
        runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())

}

// 文件级常量（放在类外，避免 inner class 里 companion 作用域解析歧义）
private const val DEFAULT_LIMIT = 10
private const val MAX_LIMIT = 20

/** 与 ControlTools 保持一致的确认提示 */
private const val CONFIRM_HINT = "可在应用内确认（草案 5 分钟内有效）"
