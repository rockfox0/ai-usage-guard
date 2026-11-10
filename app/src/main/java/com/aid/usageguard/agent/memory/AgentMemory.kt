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

    /** 追加助手回复（带本次调用的工具名 + 可选的结构化卡片 JSON） */
    suspend fun appendAssistant(
        sessionId: String,
        text: String,
        toolCalls: List<String> = emptyList(),
        structuredJson: String? = null,
    ) {
        if (text.isBlank()) return
        appendMessage(sessionId, ROLE_ASSISTANT, text, toolCalls, structuredJson)
    }

    suspend fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        toolCalls: List<String> = emptyList(),
        structuredJson: String? = null,
    ) {
        dao.insert(
            AgentMessageEntity(
                sessionId = sessionId.ifBlank { DEFAULT_SESSION_ID },
                role = role,
                content = content,
                timestamp = System.currentTimeMillis(),
                toolCalls = toolCalls.distinct().takeIf { it.isNotEmpty() }?.joinToString(","),
                structuredJson = structuredJson,
            ),
        )
    }

    /** 给 LLM 回放的历史（只含纯文本 user/assistant，最多 [maxTurns] 条） */
    suspend fun loadLlmHistory(sessionId: String, maxTurns: Int = 12): List<LlmMessage> {
        val limit = maxTurns.coerceAtLeast(0)
        if (limit == 0) return emptyList()
        // 直接查询最近 N 条（DESC LIMIT），避免「先取最旧 500 条再截尾」导致旧消息覆盖新上下文（方案 T07）
        return dao.recentHistory(sessionId.ifBlank { DEFAULT_SESSION_ID }, limit)
            .asReversed()
            .mapNotNull { entity ->
                when (entity.role) {
                    ROLE_USER -> LlmMessage.user(
                        truncateForHistory(sanitizeContent(entity.content), MAX_USER_HISTORY_CHARS),
                    )

                    ROLE_ASSISTANT -> LlmMessage.assistant(
                        truncateForHistory(sanitizeContent(entity.content), MAX_ASSISTANT_HISTORY_CHARS),
                    )
                    else -> null
                }
            }
    }

    /** 助手页展示用：某会话最近 [limit] 条消息（按时间升序） */
    suspend fun history(sessionId: String, limit: Int = 100): List<ChatMessageModel> =
        dao.recentHistory(sessionId.ifBlank { DEFAULT_SESSION_ID }, limit.coerceAtLeast(0))
            .asReversed()
            .map { it.toModel() }

    /** 跨会话的最近消息（按时间升序） */
    suspend fun recent(limit: Int = 50): List<ChatMessageModel> =
        dao.recent(limit.coerceAtLeast(0)).reversed().map { it.toModel() }

    /** 清空全部对话（「清空对话」按钮） */
    suspend fun clear() = dao.clear()

    private suspend fun entities(sessionId: String): List<AgentMessageEntity> =
        dao.history(sessionId.ifBlank { DEFAULT_SESSION_ID }, MAX_LOAD)

    /** 回放历史时逐条截断：flash 上下文有限，长回答不必原样回放 */
    private fun truncateForHistory(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…（历史消息已截断）"

    /** 修复历史数据：早期流式解析 bug 会把 JSON null 写成正文 "nullnull..." 前缀 */
    private fun sanitizeContent(text: String): String = text.replace(Regex("^(null)+"), "")

    private fun AgentMessageEntity.toModel(): ChatMessageModel = ChatMessageModel(
        id = id,
        role = role,
        content = sanitizeContent(content),
        timestamp = timestamp,
        toolCalls = toolCalls
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList(),
        structuredJson = structuredJson,
    )

    companion object {
        const val DEFAULT_SESSION_ID = "assistant"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** 单次读取上限：SQLite 的 LIMIT 参数，够用且避免一次读太多 */
        private const val MAX_LOAD = 500

        /** 回放时单条用户消息上限 */
        private const val MAX_USER_HISTORY_CHARS = 600

        /** 回放时单条助手消息上限（长回答截断，避免小上下文模型被历史淹没） */
        private const val MAX_ASSISTANT_HISTORY_CHARS = 1_200
    }
}
