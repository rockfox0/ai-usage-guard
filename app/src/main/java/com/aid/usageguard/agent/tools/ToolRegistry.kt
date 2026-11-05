package com.aid.usageguard.agent.tools

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

    fun specs(): List<LlmToolSpec> = tools.map { tool ->
        LlmToolSpec(name = tool.name, description = tool.description, parameters = tool.parameters)
    }
}
