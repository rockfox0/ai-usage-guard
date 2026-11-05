package com.aid.usageguard.agent.contract

import kotlinx.coroutines.flow.Flow

/** Agent 执行结果（AI 成功或本地降级都会落在同一个结构里） */
data class AgentOutcome(
    val text: String,
    /** AI / LOCAL / STUB */
    val source: String,
    val ok: Boolean,
    val toolCalls: List<String> = emptyList(),
    val elapsedMs: Long = 0L,
)

/** 页面来源与对话对象上下文（v1.7 方案 §7）。字段白名单，禁止携带 API Key 等凭据。 */
data class AgentContext(
    /** dashboard / apps / app_detail / report / settings / assistant */
    val entryPage: String? = null,
    /** 当前对话对象：应用包名 */
    val focusPackage: String? = null,
    val focusAppName: String? = null,
    /** 当前对话对象：范围天数（1 / 7 / 30） */
    val rangeDays: Int? = null,
    /** 当前对话对象：某一天 yyyy-MM-dd */
    val focusDate: String? = null,
) {
    fun isEmpty(): Boolean =
        entryPage == null && focusPackage == null && rangeDays == null && focusDate == null

    /** 注入系统提示词的一段可读描述（不含敏感字段） */
    fun describe(): String {
        val parts = mutableListOf<String>()
        entryPage?.let { parts += "来源页面：$it" }
        focusAppName?.let { parts += "当前对话对象应用：$it" }
        focusPackage?.let { parts += "应用包名：$it" }
        rangeDays?.let { parts += "范围：最近 $it 天" }
        focusDate?.let { parts += "日期：$it" }
        return if (parts.isEmpty()) "" else parts.joinToString("；")
    }
}

/** 统一回复事件（v1.7 方案 §8.1）：UI 只消费这些事件，不解析模型原始流 */
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ToolStarted(val name: String, val callId: String) : AgentEvent
    data class ToolFinished(
        val name: String,
        val callId: String,
        val ok: Boolean,
        val summary: String? = null,
    ) : AgentEvent

    /** 本地数据卡片：kind + 已绑定的数据 JSON（数值来自本地查询，不是模型自填） */
    data class DataBlock(val kind: String, val payloadJson: String) : AgentEvent

    /** 待用户确认的操作草案 */
    data class ActionProposed(
        val opId: String,
        val type: String,
        val message: String,
    ) : AgentEvent

    data class Completed(val outcome: AgentOutcome) : AgentEvent
    data class Failed(val message: String, val code: String? = null) : AgentEvent
    data object Cancelled : AgentEvent
}

/**
 * Agent 运行时契约：LLM + Tool Calling 的实现在 agent.runtime 包内，
 * 上层（报告页 / 通知 / WorkManager）只依赖这个接口。
 */
interface AgentRuntime {
    /** 生成某天的使用报告 */
    suspend fun generateDailyReport(date: String): AgentOutcome

    /** 生成周报 */
    suspend fun generateWeeklyReport(): AgentOutcome

    /** 与用户对话（AI 助手页），带会话历史；[context] 为页面来源/对话对象快照（方案 §7） */
    suspend fun chat(
        sessionId: String,
        userInput: String,
        context: AgentContext? = null,
    ): AgentOutcome

    /** 当前活动任务的统一事件流（同一时刻只允许一个前台任务，方案 §10.1） */
    val events: Flow<AgentEvent>

    /** 取消当前前台任务：停止后续模型/工具调用与 UI 增量更新（不误报已完成的修改） */
    suspend fun cancelCurrent()

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

    override suspend fun chat(
        sessionId: String,
        userInput: String,
        context: AgentContext?,
    ) = AgentOutcome("Agent 尚未接入，请先在设置里配置模型接口。", "LOCAL", false)

    override val events: Flow<AgentEvent> = kotlinx.coroutines.flow.emptyFlow()

    override suspend fun cancelCurrent() = Unit

    override suspend fun quickHint(appName: String, usedMinutes: Long) =
        AgentOutcome("", "LOCAL", false)

    override suspend fun isConfigured(): Boolean = false
}
