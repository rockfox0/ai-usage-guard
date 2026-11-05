package com.aid.usageguard.agent.tools

import com.aid.usageguard.agent.capability.CapabilityCatalog
import com.aid.usageguard.agent.llm.LlmToolSpec

/**
 * 工具白名单注册表。
 *
 * 模型只能调用这里注册过的工具（找不到即报错回传）；
 * 不存在「按名字反射执行」或「任意代码执行」的入口。
 */
class ToolRegistry(tools: List<AgentTool>) {

    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "工具名重复：${tools.map { it.name }}" }
    }

    /** 注册的工具（顺序稳定，用于展示与 schema 生成） */
    val tools: List<AgentTool> = tools.toList()

    val names: Set<String> get() = byName.keys

    fun find(name: String): AgentTool? = byName[name]

    /** 所有工具的 schema（不做 scope 过滤，仅供调试） */
    fun specs(): List<LlmToolSpec> = tools.map { tool -> tool.toSpec() }

    /** 按任务权限域过滤后的工具 schema（发给模型的就是这一份） */
    fun specs(scope: TaskScope): List<LlmToolSpec> =
        ToolScopePolicy.visible(tools, scope).map { tool -> tool.toSpec() }

    /**
     * 按「已加载能力 + 元工具」生成模型可见 schema（v1.9 Phase 1 动态能力发现）。
     * 仍然受 TaskScope 过滤：REPORT/BACKGROUND/INSIGHT 里写工具不会因为加载能力而可见。
     */
    fun specsForCapabilities(
        capabilities: Set<String>,
        scope: TaskScope,
        alwaysInclude: Set<String> = emptySet(),
    ): List<LlmToolSpec> = tools
        .filter { tool ->
            ToolScopePolicy.allows(scope, tool.kind) &&
                (tool.name in alwaysInclude || CapabilityCatalog.capabilityOf(tool.name) in capabilities)
        }
        .map { tool -> tool.toSpec() }

    /** 执行器用：该工具在当前任务域是否允许执行 */
    fun allows(toolName: String, scope: TaskScope): Boolean =
        find(toolName)?.let { ToolScopePolicy.allows(scope, it.kind) } ?: false

    private fun AgentTool.toSpec(): LlmToolSpec =
        LlmToolSpec(name = name, description = description, parameters = parameters)
}
