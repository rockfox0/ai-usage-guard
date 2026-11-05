package com.aid.usageguard.agent.capability

import com.aid.usageguard.agent.contract.AgentContext

/**
 * 能力发现（v1.9 Phase 1）：
 *
 * - 模式 A（本地快速路由）：[initialCapabilities] 按用户输入关键词 + 页面上下文，先给出一小组能力；
 * - 模式 B（模型主动发现）：模型可调用 `discover_capabilities` 搜索能力、`load_capability` 加载，
 *   加载后由 AgentLoop 在下一轮把该能力的工具加入可见集合。
 *
 * 本地路由只做「缩小初始集合」，不做最终判断：没命中时回退到常用能力 + 元工具，模型仍可自行发现。
 */
object CapabilityDiscovery {

    /** 没命中关键词时的默认能力：使用分析 + 评分（覆盖大多数"最近怎么样"类问题） */
    private val DEFAULT_CAPABILITIES = listOf("usage_analysis", "score_review")

    /** 初始加载的能力集合（CHAT 域专用） */
    fun initialCapabilities(input: String, context: AgentContext?): List<String> {
        val matched = mutableListOf<String>()
        val text = input.lowercase()

        CapabilityCatalog.capabilities.forEach { cap ->
            if (cap.keywords.any { kw -> text.contains(kw.lowercase()) }) matched += cap.id
        }

        // 页面/对象上下文：正在看某个应用详情 → 必然需要使用分析
        if (context?.focusPackage != null || context?.entryPage == "app_detail") {
            matched += "usage_analysis"
        }
        if (context?.entryPage == "report") {
            matched += "report_management"
        }
        if (matched.isEmpty()) matched += DEFAULT_CAPABILITIES

        return matched.distinct().take(4)
    }

    /** 按查询词搜索能力：命中关键词或示例；无命中时返回全部能力的摘要（便于模型挑选） */
    fun discover(query: String, context: AgentContext? = null): List<CapabilityDescriptor> {
        val text = (query + " " + (context?.focusAppName ?: "")).lowercase().trim()
        if (text.isBlank()) return CapabilityCatalog.capabilities
        val hits = CapabilityCatalog.capabilities.filter { cap ->
            cap.keywords.any { text.contains(it.lowercase()) } ||
                cap.examples.any { text.contains(it.lowercase()) } ||
                cap.name.contains(query.trim()) ||
                text.contains(cap.id)
        }
        return hits.ifEmpty { CapabilityCatalog.capabilities }
    }

    /** 给模型看的简表（能力 id + 名称 + 一句话 + 工具数），控制 token */
    fun summarise(caps: List<CapabilityDescriptor>): String =
        caps.joinToString("\n") { cap ->
            "- ${cap.id}（${cap.name}）：${cap.description} [${cap.toolNames.size} 个工具]"
        }
}
