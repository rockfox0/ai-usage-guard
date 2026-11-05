package com.aid.usageguard.agent.memory

import com.aid.usageguard.agent.capability.CapabilityCatalog
import com.aid.usageguard.data.db.AgentFeedbackEntity
import com.aid.usageguard.data.db.AgentGoalEntity
import com.aid.usageguard.data.db.AgentMemoryEntity
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 3：记忆摘要渲染（纯函数，不依赖 Room）。 */
class MemoryPromptTest {

    private fun profile(key: String, value: String) = AgentMemoryEntity(
        kind = "profile",
        memKey = key,
        valueJson = """{"value":"$value"}""",
        updatedAt = 1L,
    )

    private fun goal(text: String, period: String = "") = AgentGoalEntity(
        id = "g1",
        goal = text,
        targetPeriod = period,
        status = "ACTIVE",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun feedback(signal: String, reason: String) = AgentFeedbackEntity(
        id = "f1",
        targetType = "suggestion",
        targetId = "",
        signal = signal,
        reason = reason,
        createdAt = 1L,
    )

    @Test
    fun `无记忆时不输出段落`() {
        assertTrue(MemoryEngine.renderPromptSection(emptyList(), emptyList(), emptyList()).isBlank())
    }

    @Test
    fun `记忆摘要包含偏好 目标 拒绝项`() {
        val section = MemoryEngine.renderPromptSection(
            profiles = listOf(profile("tone", "温和")),
            goals = listOf(goal("减少夜间游戏", "22:00-06:00")),
            feedback = listOf(feedback("REJECT", "每天只玩30分钟太严格"), feedback("PREFER", "更喜欢温和提醒")),
        )
        assertTrue(section.contains("tone=温和"))
        assertTrue(section.contains("减少夜间游戏"))
        assertTrue(section.contains("22:00-06:00"))
        assertTrue(section.contains("太严格"))
        assertTrue(section.contains("不要原样重复被拒绝过的方案"))
    }

    @Test
    fun `记忆能力在能力目录中可被发现`() {
        val cap = CapabilityCatalog.byId("memory_management")
        assertTrue(cap != null && cap.toolNames.contains("save_goal"))
    }
}
