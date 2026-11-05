package com.aid.usageguard.agent.contract

/** Agent 执行结果（AI 成功或本地降级都会落在同一个结构里） */
data class AgentOutcome(
    val text: String,
    /** AI / LOCAL / STUB */
    val source: String,
    val ok: Boolean,
    val toolCalls: List<String> = emptyList(),
    val elapsedMs: Long = 0L,
)

/**
 * Agent 运行时契约：LLM + Tool Calling 的实现在 agent.runtime 包内，
 * 上层（报告页 / 通知 / WorkManager）只依赖这个接口。
 */
interface AgentRuntime {
    /** 生成某天的使用报告 */
    suspend fun generateDailyReport(date: String): AgentOutcome

    /** 生成周报 */
    suspend fun generateWeeklyReport(): AgentOutcome

    /** 与用户对话（AI 助手页），带会话历史 */
    suspend fun chat(sessionId: String, userInput: String): AgentOutcome

    /**
     * 使用过程中的一句话提示（陪伴模式）。
     * 不进聊天历史、不调用工具，要求 ≤30 字；失败时返回 ok=false 由上层用本地文案兜底。
     */
    suspend fun quickHint(appName: String, usedMinutes: Long): AgentOutcome

    /** 是否已配置可用的 LLM（填了 Key） */
    suspend fun isConfigured(): Boolean
}

/** 占位实现：真实 AgentLoop 接入前，让上层能编译并走本地降级 */
class StubAgentRuntime : AgentRuntime {
    override suspend fun generateDailyReport(date: String) =
        AgentOutcome("", "LOCAL", false)

    override suspend fun generateWeeklyReport() =
        AgentOutcome("", "LOCAL", false)

    override suspend fun chat(sessionId: String, userInput: String) =
        AgentOutcome("Agent 尚未接入，请先在设置里配置模型接口。", "LOCAL", false)

    override suspend fun quickHint(appName: String, usedMinutes: Long) =
        AgentOutcome("", "LOCAL", false)

    override suspend fun isConfigured(): Boolean = false
}
