package com.aid.usageguard.agent.context

import com.aid.usageguard.agent.memory.AgentMemory
import com.aid.usageguard.domain.model.ChatMessageModel

/**
 * 上下文工程（v1.9 Phase 3）。
 *
 * 现状：只给模型最近 N 条原始消息 → 长对话会丢目标、重复工具结论、token 膨胀。
 * 这里做「本地压缩」：
 * - 最近 [keepRecent] 条保持原样（由 AgentMemory.loadLlmHistory 负责）；
 * - 更早的消息抽成一段「更早对话摘要」（只保留用户关注点，去重、截断）；
 * - 摘要注入系统提示词，不额外消耗模型请求。
 */
class ContextEngine(private val memory: AgentMemory) {

    /** 更早对话的摘要（无更早消息时返回空串） */
    suspend fun conversationSummary(
        sessionId: String,
        keepRecent: Int = 12,
        maxChars: Int = 500,
    ): String {
        val all = runCatching { memory.history(sessionId, limit = keepRecent + 40) }.getOrDefault(emptyList())
        if (all.size <= keepRecent) return ""
        val older = all.dropLast(keepRecent)
        return ConversationCompressor.summarise(older, maxChars)
    }
}

/** 纯函数压缩器（单测覆盖）：只保留用户消息里的关注点，去重并限长。 */
object ConversationCompressor {

    fun summarise(older: List<ChatMessageModel>, maxChars: Int = 500): String {
        if (older.isEmpty()) return ""
        val topics = LinkedHashSet<String>()
        older.asReversed().forEach { msg ->
            if (msg.role != "user") return@forEach
            val text = msg.content
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.length < 4) return@forEach
            // 一句一个关注点：截断到 40 字，避免长文占满上下文
            topics += text.take(40)
            if (topics.size >= 6) return@forEach
        }
        if (topics.isEmpty()) return ""
        val joined = topics.joinToString("；")
        return if (joined.length <= maxChars) joined else joined.take(maxChars) + "…"
    }
}
