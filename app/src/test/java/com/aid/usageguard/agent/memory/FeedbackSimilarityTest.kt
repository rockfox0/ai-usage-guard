package com.aid.usageguard.agent.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9：拒绝过的建议不应原样重复（Case 6 的判定逻辑）。 */
class FeedbackSimilarityTest {

    @Test
    fun `同义建议被判为相似`() {
        assertTrue(
            FeedbackSimilarity.isSimilar(
                "每天只玩 30 分钟",
                listOf("每天只玩 30 分钟太严格"),
            ),
        )
    }

    @Test
    fun `不同建议不误伤`() {
        assertFalse(
            FeedbackSimilarity.isSimilar(
                "设置夜间专注时段",
                listOf("每天只玩 30 分钟太严格"),
            ),
        )
    }

    @Test
    fun `空输入安全`() {
        assertFalse(FeedbackSimilarity.isSimilar("", listOf("太严格")))
        assertFalse(FeedbackSimilarity.isSimilar("建议", emptyList()))
        assertFalse(FeedbackSimilarity.isSimilar("  ", listOf("")))
    }

    @Test
    fun `过短关键词被忽略`() {
        // "30" 长度 2 应被忽略，避免任何带 30 的建议都误判
        assertFalse(FeedbackSimilarity.isSimilar("30", listOf("30 分钟太严格")))
    }

    @Test
    fun `拒绝摘要用于提示词`() {
        val summary = FeedbackSimilarity.summariseRejected(listOf("太严格", "太严格", "别提醒"))
        assertTrue(summary.contains("用户拒绝过"))
        assertTrue(summary.contains("太严格"))
        assertTrue(summary.contains("别提醒"))
    }
}
