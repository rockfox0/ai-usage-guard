package com.aid.usageguard.agent.context

import com.aid.usageguard.domain.model.ChatMessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 3：更早对话压缩（去重、限长、只取用户关注点）。 */
class ConversationCompressorTest {

    private fun user(text: String) = ChatMessageModel(role = "user", content = text, timestamp = 1L)
    private fun assistant(text: String) = ChatMessageModel(role = "assistant", content = text, timestamp = 1L)

    @Test
    fun `空历史返回空串`() {
        assertEquals("", ConversationCompressor.summarise(emptyList()))
    }

    @Test
    fun `只取用户消息并去重`() {
        val summary = ConversationCompressor.summarise(
            listOf(
                user("今天用了多久"),
                assistant("今天用了 20 分钟"),
                user("今天用了多久"),
                user("帮我分析最近一周"),
            ),
        )
        assertEquals(1, summary.split("今天用了多久").size - 1)
        assertTrue(summary.contains("帮我分析最近一周"))
    }

    @Test
    fun `摘要长度被限制`() {
        val many = (1..20).map { user("关注点".repeat(30) + it) }
        val summary = ConversationCompressor.summarise(many, maxChars = 120)
        assertTrue(summary.length <= 121)
    }

    @Test
    fun `过短消息被忽略`() {
        assertEquals("", ConversationCompressor.summarise(listOf(user("嗯"), user("好"))))
    }
}
