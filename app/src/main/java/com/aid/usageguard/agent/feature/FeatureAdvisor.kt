package com.aid.usageguard.agent.feature

import com.aid.usageguard.agent.capability.CapabilityCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 功能顾问（v1.9 Phase 6）：把「还缺什么功能」变成结构化提案。
 *
 * 约束：只能提出「用现有数据能力可以做的功能」；必须写清需要哪些指标/能力；
 * 不能声称可以修改自身 APK（真正新增原生能力仍需开发者发版）。
 */
data class FeatureProposal(
    val title: String,
    val problem: String,
    val benefit: String,
    val requiredMetrics: List<String>,
    val requiredCapabilities: List<String>,
    /** P1 / P2 / P3 */
    val complexity: String,
    /** HIGH / MEDIUM / LOW */
    val priority: String,
)

object FeatureAdvisor {

    const val MAX_TITLE = 40
    const val MAX_TEXT = 400
    val COMPLEXITY = setOf("P1", "P2", "P3")
    val PRIORITIES = setOf("HIGH", "MEDIUM", "LOW")

    /** 校验并返回错误信息（null 表示通过） */
    fun validate(
        title: String,
        problem: String,
        requiredMetrics: List<String>,
        requiredCapabilities: List<String>,
        complexity: String,
        priority: String,
    ): String? {
        if (title.isBlank()) return "title 不能为空"
        if (title.length > MAX_TITLE) return "title 最长 $MAX_TITLE 字"
        if (problem.isBlank()) return "problem 不能为空（要说明用户遇到的具体问题）"
        if (requiredMetrics.isEmpty()) return "required_metrics 至少一项（这个功能依赖哪些数据）"
        if (requiredCapabilities.isEmpty()) return "required_capabilities 至少一项（复用了哪些现有能力）"
        val unknown = requiredCapabilities.filter { it !in CapabilityCatalog.ids }
        if (unknown.isNotEmpty()) return "未知能力：${unknown.joinToString("、")}；可用：${CapabilityCatalog.ids.joinToString("、")}"
        if (complexity !in COMPLEXITY) return "complexity 只能是 P1 / P2 / P3"
        if (priority !in PRIORITIES) return "priority 只能是 HIGH / MEDIUM / LOW"
        return null
    }

    fun toJson(proposal: FeatureProposal): String = JSONObject()
        .put("title", proposal.title)
        .put("problem", proposal.problem)
        .put("benefit", proposal.benefit.take(MAX_TEXT))
        .put("requiredMetrics", JSONArray(proposal.requiredMetrics))
        .put("requiredCapabilities", JSONArray(proposal.requiredCapabilities))
        .put("complexity", proposal.complexity)
        .put("priority", proposal.priority)
        .toString()

    fun fromJson(raw: String): FeatureProposal? = runCatching {
        val obj = JSONObject(raw)
        val title = obj.optString("title").trim()
        val problem = obj.optString("problem").trim()
        if (title.isEmpty() || problem.isEmpty()) return null
        FeatureProposal(
            title = title,
            problem = problem,
            benefit = obj.optString("benefit").trim(),
            requiredMetrics = obj.optJSONArray("requiredMetrics")?.toStringList().orEmpty(),
            requiredCapabilities = obj.optJSONArray("requiredCapabilities")?.toStringList().orEmpty(),
            complexity = obj.optString("complexity", "P2"),
            priority = obj.optString("priority", "MEDIUM"),
        )
    }.getOrNull()

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).trim().takeIf { s -> s.isNotEmpty() } }
}
