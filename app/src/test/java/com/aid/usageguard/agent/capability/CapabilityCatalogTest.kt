package com.aid.usageguard.agent.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 1：能力目录必须完整覆盖 34 个已注册工具，且每个工具只属于一个能力。 */
class CapabilityCatalogTest {

    /** 与 ToolRegistry 当前注册的 34 个业务工具保持一致（新增工具时必须同步这里与目录） */
    private val expectedTools = setOf(
        // usage_analysis (6)
        "get_daily_usage", "get_weekly_usage", "get_usage_ranking",
        "get_app_usage_history", "get_usage_trend", "get_behavior_events",
        // score_review (1)
        "get_usage_score",
        // quota_control (4)
        "get_quota_status", "set_daily_target", "set_daily_budget_enforced", "set_app_daily_limit",
        // restriction_control (7)
        "get_whitelist", "set_whitelist", "set_whitelist_daily_limit", "set_app_restriction",
        "propose_restriction", "set_restriction_mode", "set_restrict_mode",
        // focus_control (3)
        "set_focus_window", "set_nudge", "set_quiet_hours",
        // notification_control (4)
        "set_notification_enabled", "set_notification_channels", "set_reminder_style", "send_test_notification",
        // persona_settings (3)
        "get_user_preferences", "set_ai_persona", "set_ai_analysis_enabled",
        // report_management (3)
        "generate_usage_report", "generate_report_now", "get_pending_actions",
        // memory_management (5)
        "save_memory", "list_memory", "save_goal", "list_goals", "record_feedback",
        // system_maintenance (3)
        "get_app_settings", "refresh_usage_data", "set_theme_mode",
    )

    @Test
    fun `能力目录覆盖全部工具且无遗漏`() {
        val covered = CapabilityCatalog.capabilities.flatMap { it.toolNames }.toSet()
        assertEquals("能力目录与注册工具不一致", expectedTools, covered)
        assertTrue("未归类工具：${CapabilityCatalog.unknownTools(expectedTools)}",
            CapabilityCatalog.unknownTools(expectedTools).isEmpty())
    }

    @Test
    fun `工具不会重复归入多个能力`() {
        val all = CapabilityCatalog.capabilities.flatMap { it.toolNames }
        assertEquals("存在重复归类：$all", all.size, all.toSet().size)
    }

    @Test
    fun `能力 id 唯一且可查询`() {
        val ids = CapabilityCatalog.capabilities.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertNotNull("能力 $id 查不到", CapabilityCatalog.byId(id))
            assertTrue(CapabilityCatalog.toolsOf(id).isNotEmpty())
        }
    }

    @Test
    fun `工具到能力的映射正确`() {
        assertEquals("usage_analysis", CapabilityCatalog.capabilityOf("get_usage_trend"))
        assertEquals("restriction_control", CapabilityCatalog.capabilityOf("set_whitelist"))
        assertEquals("quota_control", CapabilityCatalog.capabilityOf("set_app_daily_limit"))
        assertEquals("memory_management", CapabilityCatalog.capabilityOf("save_goal"))
        assertNull(CapabilityCatalog.capabilityOf("discover_capabilities"))
        assertNull(CapabilityCatalog.capabilityOf("load_capability"))
    }
}
