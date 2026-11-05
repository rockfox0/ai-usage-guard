package com.aid.usageguard.agent.plan

import com.aid.usageguard.agent.capability.CapabilityCatalog
import com.aid.usageguard.agent.capability.CapabilityDiscovery
import com.aid.usageguard.agent.contract.AgentContext
import org.json.JSONObject

/**
 * 任务规划器（v1.9 Phase 2）：
 * - [systemPrompt]/[userPrompt] 构造「一次模型请求产出 JSON 计划」的提示词（不额外多轮）；
 * - [parse] 是纯函数：去代码围栏、取 JSON、逐项校验（步数 ≤5、能力 id 必须存在、至少 1 步），
 *   失败返回 null，调用方回退到 DIRECT 单循环（绝不因规划失败卡住用户）。
 */
object TaskPlanner {

    const val MAX_STEPS = 3

    fun systemPrompt(): String = buildString {
        append("你是手机使用管理 Agent 的任务规划器。根据用户目标，输出一个 JSON 执行计划，不要输出任何解释。\n")
        append("要求：\n")
        append("1. 步骤 1-5 步，每步一句话、可执行、尽量互不重复；\n")
        append("2. 每步标注所需能力 id（只能从下面清单里选，可多选）；\n")
        append("3. requiresData=true 表示该步必须基于本地工具返回的数据；\n")
        append("4. 给出 1-4 条完成条件（用于自检是否真的回答了用户）；\n")
        append("5. 计划里不要包含「已修改设置」这类动作结论——写操作只生成待确认草案，由用户确认。\n")
        append("可用能力：\n")
        append(CapabilityDiscovery.summarise(CapabilityCatalog.capabilities))
        append("\n\n输出格式（严格 JSON，中文内容）：\n")
        append("""{"goal":"...","steps":[{"step_id":"s1","description":"...","capabilities":["usage_analysis"],"requires_data":true}],"completion_criteria":["..."]}""")
    }

    fun userPrompt(input: String, context: AgentContext?): String = buildString {
        append("用户目标：").append(input).append('\n')
        val ctx = context?.describe()
        if (!ctx.isNullOrBlank()) append("当前上下文：").append(ctx).append('\n')
        append("请输出 JSON 计划。")
    }

    /** 执行时注入给主循环的计划指令（作为一条 user 消息） */
    fun directive(plan: TaskPlan): String = buildString {
        append("【执行计划（内部，不要原样复述）】\n")
        append("目标：").append(plan.goal).append('\n')
        plan.steps.forEachIndexed { index, step ->
            append(index + 1).append(". ").append(step.description)
            if (step.capabilities.isNotEmpty()) append("（能力：").append(step.capabilities.joinToString("、")).append("）")
            append('\n')
        }
        if (plan.completionCriteria.isNotEmpty()) {
            append("完成条件：").append(plan.completionCriteria.joinToString("；")).append('\n')
        }
        append("按步骤执行，每步先调用所需工具获取真实数据；数据不足要说明，不要编造。写操作只生成待确认草案。\n")
        append("全部完成后，用一段简洁中文给出最终结论（可含要点列表）。")
    }

    /** 纯解析 + 校验：失败返回 null（调用方回退 DIRECT）。 */
    fun parse(raw: String, mode: TaskMode, taskId: String = "plan"): TaskPlan? {
        val json = extractJson(raw) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val goal = obj.optString("goal").trim().ifBlank { "完成用户目标" }
        val stepsArray = obj.optJSONArray("steps") ?: return null
        val validCapabilities = CapabilityCatalog.ids.toSet()

        val steps = ArrayList<TaskStep>()
        for (i in 0 until stepsArray.length()) {
            if (steps.size >= MAX_STEPS) break
            val item = stepsArray.optJSONObject(i) ?: continue
            val desc = item.optString("description").trim()
            if (desc.isEmpty()) continue
            val caps = ArrayList<String>()
            item.optJSONArray("capabilities")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val id = arr.optString(j).trim()
                    if (id in validCapabilities) caps += id
                }
            }
            steps += TaskStep(
                stepId = item.optString("step_id").trim().ifBlank { "s${i + 1}" },
                description = desc,
                capabilities = caps.distinct(),
                requiresData = item.optBoolean("requires_data", true),
            )
        }
        if (steps.isEmpty()) return null

        val criteria = ArrayList<String>()
        obj.optJSONArray("completion_criteria")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { criteria += it }
            }
        }
        val needsControl = steps.any { step ->
            step.capabilities.any { cap ->
                CapabilityCatalog.byId(cap)?.category == "control"
            }
        }
        return TaskPlan(
            taskId = taskId,
            goal = goal,
            mode = mode,
            steps = steps,
            completionCriteria = criteria.take(4),
            requiresConfirmation = needsControl,
        )
    }

    /** 去掉 ```json 围栏并截取最外层 JSON 对象 */
    private fun extractJson(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").trim()
            val end = text.lastIndexOf("```")
            if (end >= 0) text = text.substring(0, end).trim()
        }
        val start = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (start < 0 || last <= start) return null
        return text.substring(start, last + 1)
    }
}
