package com.aid.usageguard.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9.3：轮次/预算耗尽时的兜底文案，绝不回显原始工具 JSON。 */
class ToolEvidenceTest {

    @Test
    fun `优先使用 message 字段`() {
        val summary = ToolEvidence.summarise("""{"message":"每日目标已设为 350 分钟","opId":"x","executed":true}""")
        assertTrue(summary!!.contains("每日目标已设为 350 分钟"))
    }

    @Test
    fun `失败时给出错误原因`() {
        val summary = ToolEvidence.summarise("""{"ok":false,"error":"白名单今日添加已达上限"}""")
        assertTrue(summary!!.contains("白名单今日添加已达上限"))
    }

    @Test
    fun `无 message 时提取关键字段且不含原始花括号`() {
        val summary = ToolEvidence.summarise(
            """{"totalMinutes":314,"remainingMinutes":46,"budgetMinutes":360,"opId":"x"}""",
        )
        assertTrue(summary!!.contains("totalMinutes"))
        assertTrue(summary.contains("314"))
        assertFalse("不应包含原始 JSON 花括号", summary.contains("{"))
    }

    @Test
    fun `坏 JSON 或空内容返回 null`() {
        assertNull(ToolEvidence.summarise(""))
        assertNull(ToolEvidence.summarise("not json"))
        assertNull(ToolEvidence.summarise("""{"opId":"x","requiresConfirmation":true}"""))
    }

    @Test
    fun `兜底正文包含原因 工具名 与重试提示`() {
        val text = ToolEvidence.fallbackText(
            listOf("get_daily_usage", "get_quota_status"),
            listOf("""{"message":"今天已用 19 分钟"}""", """{"message":"剩余 341 分钟"}"""),
            "已达到本次任务的最大推理轮次 4 轮",
        )
        assertTrue(text.contains("最大推理轮次"))
        assertTrue(text.contains("get_daily_usage"))
        assertTrue(text.contains("今天已用 19 分钟"))
        assertTrue(text.contains("再问我一次"))
        assertFalse(text.contains("{"))
    }

    @Test
    fun `没有任何证据时给出可执行提示`() {
        val text = ToolEvidence.fallbackText(emptyList(), emptyList(), "本次任务已达到资源预算上限")
        assertTrue(text.contains("资源预算上限"))
        assertTrue(text.contains("再问我一次"))
    }
}
