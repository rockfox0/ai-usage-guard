package com.aid.usageguard.agent.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 6：功能提案校验（能力必须存在、指标/收益必填、复杂度/优先级白名单）。 */
class FeatureAdvisorTest {

    private val valid = FeatureProposal(
        title = "无意识启动监控",
        problem = "经常短时间反复打开某个 App，但自己没有意识到",
        benefit = "发现高频低时长的碎片化使用",
        requiredMetrics = listOf("启动次数", "会话时长"),
        requiredCapabilities = listOf("usage_analysis"),
        complexity = "P2",
        priority = "HIGH",
    )

    @Test
    fun `合法提案通过校验`() {
        assertNull(
            FeatureAdvisor.validate(
                valid.title, valid.problem, valid.requiredMetrics,
                valid.requiredCapabilities, valid.complexity, valid.priority,
            ),
        )
    }

    @Test
    fun `未知能力被拒绝`() {
        val error = FeatureAdvisor.validate("标题", "问题", listOf("指标"), listOf("not_a_capability"), "P1", "LOW")
        assertNotNull(error)
        assertTrue(error!!.contains("未知能力"))
    }

    @Test
    fun `缺指标或缺问题被拒绝`() {
        assertNotNull(FeatureAdvisor.validate("标题", "问题", emptyList(), listOf("usage_analysis"), "P1", "LOW"))
        assertNotNull(FeatureAdvisor.validate("标题", "", listOf("指标"), listOf("usage_analysis"), "P1", "LOW"))
    }

    @Test
    fun `复杂度与优先级必须合法`() {
        assertNotNull(FeatureAdvisor.validate("标题", "问题", listOf("指标"), listOf("usage_analysis"), "P9", "LOW"))
        assertNotNull(FeatureAdvisor.validate("标题", "问题", listOf("指标"), listOf("usage_analysis"), "P1", "URGENT"))
    }

    @Test
    fun `JSON 往返一致`() {
        val json = FeatureAdvisor.toJson(valid)
        val parsed = FeatureAdvisor.fromJson(json)
        assertNotNull(parsed)
        assertEquals(valid.title, parsed!!.title)
        assertEquals(valid.requiredMetrics, parsed.requiredMetrics)
        assertEquals(valid.requiredCapabilities, parsed.requiredCapabilities)
        assertEquals("HIGH", parsed.priority)
    }

    @Test
    fun `坏 JSON 返回 null`() {
        assertNull(FeatureAdvisor.fromJson("not json"))
        assertNull(FeatureAdvisor.fromJson("""{"problem":"缺标题"}"""))
    }
}
