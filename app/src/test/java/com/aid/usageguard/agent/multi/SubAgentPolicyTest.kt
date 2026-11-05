package com.aid.usageguard.agent.multi

import com.aid.usageguard.agent.plan.TaskMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 7：只有复杂分析 + 预算充足才并行子 Agent（简单问题不启动多 Agent）。 */
class SubAgentPolicyTest {

    @Test
    fun `复杂分析且预算充足时启用`() {
        assertTrue(
            SubAgentPolicy.shouldFanOut(
                TaskMode.ANALYSIS,
                remainingModelRequests = 6,
                input = "帮我分析最近 30 天的使用习惯有什么问题",
            ),
        )
    }

    @Test
    fun `简单问题不启用`() {
        assertFalse(
            SubAgentPolicy.shouldFanOut(TaskMode.DIRECT, 6, "今天用了多久"),
        )
    }

    @Test
    fun `预算不足不启用`() {
        assertFalse(
            SubAgentPolicy.shouldFanOut(
                TaskMode.ANALYSIS,
                remainingModelRequests = 4,
                input = "全面分析最近一个月的使用习惯",
            ),
        )
    }

    @Test
    fun `目标型任务不启用`() {
        assertFalse(
            SubAgentPolicy.shouldFanOut(
                TaskMode.GOAL,
                6,
                "帮我制定一个减少夜间游戏的计划",
            ),
        )
    }

    @Test
    fun `长问题即使关键词弱也启用`() {
        assertTrue(
            SubAgentPolicy.shouldFanOut(
                TaskMode.ANALYSIS,
                5,
                "我想了解一下".repeat(10),
            ),
        )
    }
}
