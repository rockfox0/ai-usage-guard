package com.aid.usageguard.agent.tools

/**
 * 任务权限域（v1.7 方案 6.1 权限矩阵）。
 *
 * 同一份 [TaskScope] 同时驱动两件事，避免「提示词禁止但执行器仍可执行」：
 * 1. 模型可见工具集合（ToolRegistry.specs(scope)）；
 * 2. 执行器允许集合（ToolExecutor 独立校验 tool.kind）。
 *
 * - CHAT：用户对话，可查询 + 生成待确认草案 + 本地副作用（报告/测试通知）；
 * - REPORT：日报/周报任务，只读；
 * - BACKGROUND：后台任务（含实时提示），只读；
 * - INSIGHT：主动洞察，只读。
 */
enum class TaskScope(val label: String) {
    CHAT("对话"),
    REPORT("报告"),
    BACKGROUND("后台"),
    INSIGHT("主动洞察");

    /** 本任务域是否允许产生任何写入/副作用（草案与本地动作） */
    val canWrite: Boolean
        get() = this == CHAT
}

/** 工具可见性 / 可执行性策略（模型侧与执行器侧共用同一份判断）。 */
object ToolScopePolicy {

    fun allows(scope: TaskScope, kind: ToolKind): Boolean = when (scope) {
        TaskScope.CHAT -> true
        TaskScope.REPORT -> kind == ToolKind.QUERY
        TaskScope.BACKGROUND -> kind == ToolKind.QUERY
        TaskScope.INSIGHT -> kind == ToolKind.QUERY
    }

    /** 模型可见工具：REPORT/BACKGROUND/INSIGHT 只剩只读查询类 */
    fun visible(tools: List<AgentTool>, scope: TaskScope): List<AgentTool> =
        if (scope.canWrite) tools else tools.filter { allows(scope, it.kind) }

    fun describe(scope: TaskScope): String = when (scope) {
        TaskScope.CHAT -> "对话（可查询、可生成待确认操作）"
        TaskScope.REPORT -> "报告（只读）"
        TaskScope.BACKGROUND -> "后台（只读）"
        TaskScope.INSIGHT -> "主动洞察（只读）"
    }
}
