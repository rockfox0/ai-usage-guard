package com.aid.usageguard.agent.proactive

import com.aid.usageguard.domain.insight.InsightCandidate
import com.aid.usageguard.domain.insight.InsightKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 6：主动洞察 JSON 解析（非法内容整体丢弃，由调用方退化成本地事实）。 */
class ProactiveParserTest {

    private val candidate = InsightCandidate(
        kind = InsightKind.TOTAL_INCREASE,
        dedupeKey = "total:2026-11-06",
        fact = "最近 7 天日均 180 分钟，前 7 天 120 分钟",
    )

    @Test
    fun `合法 JSON 解析出洞察`() {
        val insight = ProactiveParser.parse(
            """{"title":"晚间使用变多","detail":"最近一周日均比上周多 60 分钟，主要在 22 点后。","suggestion":"试试 22 点后把短视频应用移出首屏。"}""",
            candidate,
            "2026-11-06",
        )
        assertNotNull(insight)
        assertEquals("晚间使用变多", insight!!.title)
        assertEquals("2026-11-06", insight.date)
        assertEquals(InsightKind.TOTAL_INCREASE, insight.kind)
        assertTrue(insight.detail.contains("60 分钟"))
    }

    @Test
    fun `带代码围栏也能解析`() {
        val insight = ProactiveParser.parse(
            "```json\n{\"title\":\"使用增加\",\"detail\":\"日均增加 30 分钟。\",\"suggestion\":\"设个提醒\"}\n```",
            candidate,
            "2026-11-06",
        )
        assertNotNull(insight)
    }

    @Test
    fun `缺标题或坏 JSON 返回 null`() {
        assertNull(ProactiveParser.parse("not json", candidate, "2026-11-06"))
        assertNull(ProactiveParser.parse("""{"detail":"只有详情"}""", candidate, "2026-11-06"))
        assertNull(ProactiveParser.parse("""{"title":"只有标题"}""", candidate, "2026-11-06"))
    }

    @Test
    fun `超长字段被截断`() {
        val long = "很长的内容".repeat(100)
        val insight = ProactiveParser.parse(
            """{"title":"$long","detail":"$long","suggestion":"$long"}""",
            candidate,
            "2026-11-06",
        )
        assertNotNull(insight)
        assertTrue(insight!!.title.length <= 40)
        assertTrue(insight.detail.length <= 240)
    }
}
