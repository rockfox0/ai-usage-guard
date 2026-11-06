package com.aid.usageguard.agent.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9：主动洞察每天最多 1 条，关闭开关后完全不分析（避免打扰）。 */
class ProactiveThrottleTest {

    @Test
    fun `关闭时跳过`() {
        assertTrue(ProactiveThrottle.shouldSkip(enabled = false, 0, null, "2026-11-06"))
    }

    @Test
    fun `今天已有一条时跳过`() {
        assertTrue(ProactiveThrottle.shouldSkip(enabled = true, todayInsightCount = 1, null, "2026-11-06"))
    }

    @Test
    fun `今天没有洞察时允许生成`() {
        assertFalse(ProactiveThrottle.shouldSkip(enabled = true, todayInsightCount = 0, null, "2026-11-06"))
    }

    @Test
    fun `最新洞察日期是今天时跳过`() {
        assertTrue(
            ProactiveThrottle.shouldSkip(enabled = true, 0, latestInsightDate = "2026-11-06", today = "2026-11-06"),
        )
        assertFalse(
            ProactiveThrottle.shouldSkip(enabled = true, 0, latestInsightDate = "2026-11-05", today = "2026-11-06"),
        )
    }
}
