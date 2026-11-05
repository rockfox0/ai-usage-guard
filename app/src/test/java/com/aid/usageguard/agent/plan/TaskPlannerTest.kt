package com.aid.usageguard.agent.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 2：计划解析必须严格校验，失败要能回退 DIRECT（返回 null），绝不能卡住用户。 */
class TaskPlannerTest {

    private val validJson = """
        ```json
        {
          "goal": "分析最近 30 天并给出改善计划",
          "steps": [
            {"step_id":"s1","description":"查询近 30 天使用趋势","capabilities":["usage_analysis"],"requires_data":true},
            {"step_id":"s2","description":"挑出 3 个主要问题","capabilities":["usage_analysis","score_review"],"requires_data":true},
            {"step_id":"s3","description":"生成改善建议","capabilities":["quota_control"],"requires_data":false}
          ],
          "completion_criteria":["引用真实分钟数","指出 3 个问题","给出可执行建议"]
        }
        ```
    """.trimIndent()

    @Test
    fun `合法计划可以解析`() {
        val plan = TaskPlanner.parse(validJson, TaskMode.GOAL)
        assertNotNull(plan)
        plan!!
        assertEquals(3, plan.steps.size)
        assertEquals("分析最近 30 天并给出改善计划", plan.goal)
        assertEquals(listOf("usage_analysis"), plan.steps[0].capabilities)
        assertEquals(3, plan.completionCriteria.size)
        assertTrue("含 control 能力应标记需要确认", plan.requiresConfirmation)
    }

    @Test
    fun `未知能力被丢弃`() {
        val json = """{"goal":"x","steps":[{"description":"查数据","capabilities":["not_a_capability","usage_analysis"]}]}"""
        val plan = TaskPlanner.parse(json, TaskMode.ANALYSIS)
        assertNotNull(plan)
        assertEquals(listOf("usage_analysis"), plan!!.steps[0].capabilities)
    }

    @Test
    fun `步骤数量被限制在上限内`() {
        val steps = (1..9).joinToString(",") {
            """{"description":"步骤$it","capabilities":["usage_analysis"]}"""
        }
        val plan = TaskPlanner.parse("""{"goal":"x","steps":[$steps]}""", TaskMode.ANALYSIS)
        assertNotNull(plan)
        assertTrue(plan!!.steps.size <= TaskPlanner.MAX_STEPS)
    }

    @Test
    fun `空步骤或非 JSON 返回 null 以回退直答`() {
        assertNull(TaskPlanner.parse("这不是 JSON", TaskMode.ANALYSIS))
        assertNull(TaskPlanner.parse("""{"goal":"x","steps":[]}""", TaskMode.ANALYSIS))
        assertNull(TaskPlanner.parse("""{"goal":"x"}""", TaskMode.ANALYSIS))
    }

    @Test
    fun `纯只读计划不需要确认`() {
        val json = """{"goal":"分析","steps":[{"description":"查趋势","capabilities":["usage_analysis"]}]}"""
        val plan = TaskPlanner.parse(json, TaskMode.ANALYSIS)!!
        assertFalse(plan.requiresConfirmation)
    }

    @Test
    fun `计划指令包含目标与完成条件`() {
        val plan = TaskPlanner.parse(validJson, TaskMode.GOAL)!!
        val directive = TaskPlanner.directive(plan)
        assertTrue(directive.contains(plan.goal))
        assertTrue(directive.contains("完成条件"))
        assertTrue(directive.contains("待确认草案"))
    }
}
