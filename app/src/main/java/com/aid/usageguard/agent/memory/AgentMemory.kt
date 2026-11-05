package com.aid.usageguard.agent.memory

import com.aid.usageguard.agent.llm.LlmMessage
import com.aid.usageguard.data.db.AgentMessageDao
import com.aid.usageguard.data.db.AgentMessageEntity
import com.aid.usageguard.domain.model.ChatMessageModel

/**
 * Agent 对话记忆：把对话历史读写到 Room 的 `agent_message` 表。
 *
 * 约定：
 * - 只有 user / assistant 两种角色会落库（tool 消息属于单次任务的中间过程，不回放，
 *   否则会产生「孤儿 tool_call」导致 OpenAI / Anthropic 接口报错）；
 * - assistant 消息会把本次调用的工具名以逗号分隔存进 `toolCalls` 字段，供助手页展示；
 * - 读取的「最近 N 条」用「先按时间升序取一批，再 takeLast」实现（DAO 只提供升序 + LIMIT）。
 */
class AgentMemory(private val dao: AgentMessageDao) {

    /** 追加用户消息 */
    suspend fun appendUser(sessionId: String, text: String) {
        appendMessage(sessionId, ROLE_USER, text, emptyList())
    }

    /** 追加助手回复（带本次调用的工具名） */
    suspend fun appendAssistant(sessionId: String, text: String, toolCalls: List<String> = emptyList()) {
        if (text.isBlank()) return
        appendMessage(sessionId, ROLE_ASSISTANT, text, toolCalls)
    }

    suspend fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        toolCalls: List<String> = emptyList(),
    ) {
        dao.insert(
            AgentMessageEntity(
                sessionId = sessionId.ifBlank { DEFAULT_SESSION_ID },
                role = role,
                content = content,
                timestamp = System.currentTimeMillis(),
                toolCalls = toolCalls.distinct().takeIf { it.isNotEmpty() }?.joinToString(","),
            ),
        )
    }

    /** 给 LLM 回放的历史（只含纯文本 user/assistant，最多 [maxTurns] 条） */
    suspend fun loadLlmHistory(sessionId: String, maxTurns: Int = 12): List<LlmMessage> =
        entities(sessionId)
            .takeLast(maxTurns.coerceAtLeast(0))
            .mapNotNull { entity ->
                when (entity.role) {
                    ROLE_USER -> LlmMessage.user(entity.content)
                    ROLE_ASSISTANT -> LlmMessage.assistant(entity.content)
                    else -> null
                }
            }

    /** 助手页展示用：某会话最近 [limit] 条消息（按时间升序） */
    suspend fun history(sessionId: String, limit: Int = 100): List<ChatMessageModel> =
        entities(sessionId).takeLast(limit.coerceAtLeast(0)).map { it.toModel() }

    /** 跨会话的最近消息（按时间升序） */
    suspend fun recent(limit: Int = 50): List<ChatMessageModel> =
        dao.recent(limit.coerceAtLeast(0)).reversed().map { it.toModel() }

    /** 清空全部对话（「清空对话」按钮） */
    suspend fun clear() = dao.clear()

    private suspend fun entities(sessionId: String): List<AgentMessageEntity> =
        dao.history(sessionId.ifBlank { DEFAULT_SESSION_ID }, MAX_LOAD)

    private fun AgentMessageEntity.toModel(): ChatMessageModel = ChatMessageModel(
        id = id,
        role = role,
        content = content,
        timestamp = timestamp,
        toolCalls = toolCalls
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList(),
    )

    companion object {
        const val DEFAULT_SESSION_ID = "assistant"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** 单次读取上限：SQLite 的 LIMIT 参数，够用且避免一次读太多 */
        private const val MAX_LOAD = 500
    }
}
