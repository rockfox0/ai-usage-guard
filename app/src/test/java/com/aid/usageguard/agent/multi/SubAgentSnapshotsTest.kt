package com.aid.usageguard.agent.multi

import com.aid.usageguard.domain.model.AppUsageSummary
import com.aid.usageguard.domain.model.DailyTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 7：子 Agent 快照格式化（本地数据，紧凑、可核对）。 */
class SubAgentSnapshotsTest {

    private val daily7 = (1..7).map { DailyTotal(date = "2026-11-0$it", minutes = 100L + it) }
    private val daily30 = (1..30).map { DailyTotal(date = "2026-10-${it.toString().padStart(2, '0')}", minutes = 120L) }
    private val ranking = listOf(
        AppUsageSummary(packageName = "com.tencent.mm", appName = "微信", minutes = 300),
        AppUsageSummary(packageName = "com.douyin", appName = "抖音", minutes = 200),
    )

    @Test
    fun `有数据时快照包含日均与 Top 应用`() {
        val snapshot = SubAgentSnapshots.formatUsage(daily7, daily30, ranking, "2026-11-06")
        assertTrue(snapshot.contains("最近 7 天日均"))
        assertTrue(snapshot.contains("微信=300m"))
        assertTrue(snapshot.contains("最近 30 天日均"))
    }

    @Test
    fun `没有数据时返回空串`() {
        assertEquals("", SubAgentSnapshots.formatUsage(emptyList(), emptyList(), emptyList(), "2026-11-06"))
    }

    @Test
    fun `行为事件去重并限制条数`() {
        val events = (1..20).map { "事件 A$it" } + listOf("事件 A1", "事件 A1")
        val snapshot = SubAgentSnapshots.formatBehavior(events)
        assertTrue(snapshot.contains("【近期行为事件】"))
        assertEquals(1, snapshot.lines().count { it == "- 事件 A1" })
        assertTrue(snapshot.lines().filter { it.startsWith("- ") }.size <= 10)
    }

    @Test
    fun `空行为事件返回空串`() {
        assertEquals("", SubAgentSnapshots.formatBehavior(listOf("  ", "")))
    }
}
