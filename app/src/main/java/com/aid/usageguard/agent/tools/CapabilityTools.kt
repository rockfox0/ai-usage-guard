package com.aid.usageguard.agent.tools

import com.aid.usageguard.agent.capability.CapabilityCatalog
import com.aid.usageguard.agent.capability.CapabilityDiscovery
import org.json.JSONArray
import org.json.JSONObject

/**
 * 能力发现元工具（v1.9 Phase 1）。
 *
 * - `discover_capabilities(query)`：按用户目标搜索能力，返回能力 id / 名称 / 工具数（只读）；
 * - `load_capability(capability_id)`：请求加载某能力的工具，AgentLoop 在下一轮把该能力的工具加入可见集合。
 *
 * 这两个工具本身永远是 QUERY：不写任何用户数据；只影响「本任务后续可见的工具」。
 */
class CapabilityTools {

    val all: List<AgentTool> = listOf(DiscoverCapabilitiesTool(), LoadCapabilityTool())

    companion object {
        const val DISCOVER = "discover_capabilities"
        const val LOAD = "load_capability"

        /** 永远是模型可见的元工具（不随能力加载变化） */
        val META_TOOLS: Set<String> = setOf(DISCOVER, LOAD)
    }

    private inner class DiscoverCapabilitiesTool : AgentTool {
        override val name: String = DISCOVER
        override val kind: ToolKind = ToolKind.QUERY
        override val description: String =
            "按用户目标搜索可用能力（返回能力 id/名称/工具数）。需要某个能力的工具时，先 discover 再 load_capability。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "query" to JsonSchema.string("用户目标或关键词，例如「夜间游戏分析」「白名单」"),
            ),
            required = listOf("query"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val query = arguments.stringArg("query")?.trim().orEmpty()
            if (query.isEmpty()) throw ToolArgumentException("缺少必填参数 query")
            val caps = CapabilityDiscovery.discover(query)
            return JSONObject().apply {
                put("query", query)
                put("count", caps.size)
                put("capabilities", JSONArray().apply {
                    caps.forEach { cap ->
                        put(
                            JSONObject()
                                .put("id", cap.id)
                                .put("name", cap.name)
                                .put("description", cap.description)
                                .put("toolCount", cap.toolNames.size),
                        )
                    }
                })
                put("summary", CapabilityDiscovery.summarise(caps))
                put("hint", "挑一个能力后调用 load_capability(capability_id)，下一轮即可使用它的工具。")
            }
        }
    }

    private inner class LoadCapabilityTool : AgentTool {
        override val name: String = LOAD
        override val kind: ToolKind = ToolKind.QUERY
        override val description: String =
            "加载一个能力的工具（下一轮生效）。只能加载 discover_capabilities 返回的 id；写操作仍需用户确认。"
        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "capability_id" to JsonSchema.string(
                    "能力 id，例如 usage_analysis、quota_control",
                    enum = CapabilityCatalog.ids,
                ),
            ),
            required = listOf("capability_id"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val id = arguments.stringArg("capability_id")?.trim().orEmpty()
            if (id.isEmpty()) throw ToolArgumentException("缺少必填参数 capability_id")
            val cap = CapabilityCatalog.byId(id)
                ?: throw ToolArgumentException(
                    "未知能力「$id」。可用能力：${CapabilityCatalog.ids.joinToString("、")}",
                )
            return JSONObject().apply {
                // AgentLoop 依赖这个字段把能力加入本任务可见集合（下一轮生效）
                put("loadedCapability", cap.id)
                put("name", cap.name)
                put("tools", JSONArray(cap.toolNames))
                put("message", "已加载「${cap.name}」：${cap.toolNames.size} 个工具将在下一轮可用。写操作仍会先生成待确认草案。")
            }
        }
    }
}
