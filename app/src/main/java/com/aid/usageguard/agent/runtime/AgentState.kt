package com.aid.usageguard.agent.runtime

/** Agent 任务所处阶段（供日志、UI 观察；不影响对外契约）。 */
enum class AgentPhase {
    IDLE,
    THINKING,
    CALLING_TOOL,
    DONE,
    FAILED,
    CANCELLED,
}

/**
 * 一次 Agent 执行的运行时状态快照。
 *
 * AgentLoop 不对外暴露可变全局状态，只在本轮任务内更新这个快照；
 * 助手页通过 `AgentLoop.state` 读取，用来显示「正在思考 / 正在调用工具」。
 */
data class AgentState(
    val sessionId: String = "",
    val phase: AgentPhase = AgentPhase.IDLE,
    /** 当前第几轮推理（从 1 开始） */
    val round: Int = 0,
    val maxRounds: Int = AgentLoop.MAX_ROUNDS,
    /** 本任务中已经尝试过的工具名（按调用顺序，可能重复） */
    val toolCalls: List<String> = emptyList(),
    val lastError: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    val finished: Boolean
        get() = phase == AgentPhase.DONE || phase == AgentPhase.FAILED || phase == AgentPhase.CANCELLED

    fun finish(phase: AgentPhase, error: String? = lastError): AgentState = copy(
        phase = phase,
        lastError = error,
        finishedAt = System.currentTimeMillis(),
    )
}
