package com.aid.usageguard.agent.capability

import com.aid.usageguard.agent.contract.AgentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 1：本地能力路由（只是缩小初始集合，命中不了回退常用能力，模型仍可 discover/load）。 */
class CapabilityDiscoveryTest {

    private fun route(input: String, context: AgentContext? = null) =
        CapabilityDiscovery.initialCapabilities(input, context)

    @Test
    fun `使用时长类问题命中使用分析`() {
        assertTrue(route("今天用了多久").contains("usage_analysis"))
        assertTrue(route("哪个 App 用得最多").contains("usage_analysis"))
        assertTrue(route("最近 30 天趋势怎么样").contains("usage_analysis"))
    }

    @Test
    fun `评分问题命中评分能力`() {
        assertTrue(route("我今天多少分").contains("score_review"))
    }

    @Test
    fun `额度问题命中额度能力`() {
        assertTrue(route("今天还剩多少额度").contains("quota_control"))
        assertTrue(route("给微信设每天 30 分钟限额").contains("quota_control"))
    }

    @Test
    fun `白名单与限制问题命中限制能力`() {
        assertTrue(route("把微信加入白名单").contains("restriction_control"))
        assertTrue(route("以后只限制指定的应用").contains("restriction_control"))
    }

    @Test
    fun `专注时段问题命中专注能力`() {
        assertTrue(route("设置专注时段，晚上只能用白名单").contains("focus_control"))
    }

    @Test
    fun `报告问题命中报告能力`() {
        assertTrue(route("给我一份周报").contains("report_management"))
        assertTrue(route("我还有什么待确认的操作").contains("report_management"))
    }

    @Test
    fun `页面上下文里有当前应用时必有使用分析`() {
        val ctx = AgentContext(entryPage = "app_detail", focusPackage = "com.tencent.mm", focusAppName = "微信")
        assertTrue(route("最近怎么样", ctx).contains("usage_analysis"))
    }

    @Test
    fun `没有命中关键词时回退常用能力且不为空`() {
        val caps = route("hello there")
        assertTrue(caps.isNotEmpty())
        assertTrue(caps.contains("usage_analysis"))
        assertTrue(caps.size <= 4)
    }

    @Test
    fun `discover 无命中时返回全部能力摘要`() {
        val all = CapabilityDiscovery.discover("zzzz-unknown")
        assertEquals(CapabilityCatalog.capabilities.size, all.size)
        assertTrue(CapabilityDiscovery.summarise(all).contains("usage_analysis"))
    }

    @Test
    fun `discover 能按关键词命中`() {
        val caps = CapabilityDiscovery.discover("夜间使用")
        assertTrue(caps.any { it.id == "usage_analysis" || it.id == "focus_control" })
    }
}
