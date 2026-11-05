package com.aid.usageguard.agent.tools

import com.aid.usageguard.agent.capability.CapabilityCatalog
import com.aid.usageguard.agent.feature.FeatureAdvisor
import com.aid.usageguard.agent.feature.FeatureProposal
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.repo.InsightRepository
import com.aid.usageguard.data.repo.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主动洞察 & 功能提案工具（v1.9 Phase 6）。
 * 洞察本身由 [com.aid.usageguard.agent.proactive.ProactiveAgent] 在后台生成，这里只读；
 * 功能提案由模型结构化输出后落库（kind=feature），供后续产品迭代参考。
 */
class InsightTools(
    private val insightsProvider: () -> InsightRepository = { Container.insights },
    private val memoryProvider: () -> MemoryRepository = { Container.memory },
) {

    val all: List<AgentTool> = listOf(
        GetProactiveInsightsTool(),
        ProposeFeatureTool(),
        ListFeatureProposalsTool(),
    )

    private inner class GetProactiveInsightsTool : AgentTool {
        override val name: String = "get_proactive_insights"
        override val kind: ToolKind = ToolKind.QUERY
        override val description: String = "查看主动分析生成的洞察（最近 10 条：标题、事实、建议）"
        override val parameters: JSONObject = JsonSchema.objectSchema(emptyList(), emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val insights = runCatching { insightsProvider().recent(10) }.getOrDefault(emptyList())
            return JSONObject().apply {
                put("count", insights.size)
                put("insights", JSONArray().apply {
                    insights.forEach { item ->
                        put(
                            JSONObject()
                                .put("date", item.date)
                                .put("kind", item.kind.name)
                                .put("title", item.title)
                                .put("detail", item.detail)
                                .put("suggestion", item.suggestion),
                        )
                    }
                })
            }
        }
    }

    private inner class ProposeFeatureTool : AgentTool {
        override val name: String = "propose_feature"
        override val kind: ToolKind = ToolKind.CONTROL
        override val description: String =
            "把用户想要的「新功能」整理成结构化提案（问题/收益/所需指标/所需能力/复杂度/优先级）。" +
                "只在用户问「还缺什么功能 / 希望有 X 功能」时使用。"
        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "title" to JsonSchema.string("功能名，≤40 字"),
                "problem" to JsonSchema.string("要解决的具体问题"),
                "benefit" to JsonSchema.string("预期收益（可空）"),
                "required_metrics" to JsonSchema.stringArray("依赖哪些数据指标"),
                "required_capabilities" to JsonSchema.stringArray(
                    "复用哪些现有能力 id",
                    enum = CapabilityCatalog.ids,
                ),
                "complexity" to JsonSchema.string("实现复杂度", enum = FeatureAdvisor.COMPLEXITY.toList()),
                "priority" to JsonSchema.string("优先级", enum = FeatureAdvisor.PRIORITIES.toList()),
            ),
            required = listOf("title", "problem", "required_metrics", "required_capabilities", "complexity", "priority"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val title = arguments.stringArg("title")?.trim().orEmpty()
            val problem = arguments.stringArg("problem")?.trim().orEmpty()
            val benefit = arguments.stringArg("benefit")?.trim().orEmpty()
            val metrics = arguments.stringListArg("required_metrics")
            val caps = arguments.stringListArg("required_capabilities")
            val complexity = arguments.stringArg("complexity")?.trim()?.uppercase().orEmpty()
            val priority = arguments.stringArg("priority")?.trim()?.uppercase().orEmpty()

            FeatureAdvisor.validate(title, problem, metrics, caps, complexity, priority)?.let {
                throw ToolArgumentException(it)
            }
            val proposal = FeatureProposal(
                title = title,
                problem = problem,
                benefit = benefit,
                requiredMetrics = metrics,
                requiredCapabilities = caps,
                complexity = complexity,
                priority = priority,
            )
            val repo = runCatching { memoryProvider() }.getOrElse {
                throw ToolArgumentException("记忆服务尚未就绪，请稍后再试")
            }
            repo.putMemory("feature", java.util.UUID.randomUUID().toString(), FeatureAdvisor.toJson(proposal))
            return JSONObject().apply {
                put("ok", true)
                put("proposal", FeatureAdvisor.toJson(proposal))
                put("message", "已记录功能提案：${proposal.title}（${proposal.priority}/${proposal.complexity}）")
            }
        }
    }

    private inner class ListFeatureProposalsTool : AgentTool {
        override val name: String = "list_feature_proposals"
        override val kind: ToolKind = ToolKind.QUERY
        override val description: String = "查看已记录的功能提案"
        override val parameters: JSONObject = JsonSchema.objectSchema(emptyList(), emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val memories = runCatching { memoryProvider().memories("feature") }.getOrDefault(emptyList())
            val proposals = memories.mapNotNull { FeatureAdvisor.fromJson(it.valueJson) }
            return JSONObject().apply {
                put("count", proposals.size)
                put("proposals", JSONArray().apply {
                    proposals.forEach { p -> put(JSONObject(FeatureAdvisor.toJson(p))) }
                })
            }
        }
    }
}
