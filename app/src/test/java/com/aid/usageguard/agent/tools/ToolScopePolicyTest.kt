package com.aid.usageguard.agent.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 方案 6.1 / T08-T09：任务权限矩阵（模型可见集合与执行器允许集合共用同一判断）。
 */
class ToolScopePolicyTest {

    @Test
    fun `对话域允许查询、草案与本地副作用`() {
        ToolKind.entries.forEach { kind ->
            assertTrue("CHAT 应允许 $kind", ToolScopePolicy.allows(TaskScope.CHAT, kind))
        }
    }

    @Test
    fun `报告域只允许只读查询`() {
        assertTrue(ToolScopePolicy.allows(TaskScope.REPORT, ToolKind.QUERY))
        assertFalse(ToolScopePolicy.allows(TaskScope.REPORT, ToolKind.PROPOSE))
        assertFalse(ToolScopePolicy.allows(TaskScope.REPORT, ToolKind.CONTROL))
    }

    @Test
    fun `后台与洞察域只允许只读查询`() {
        listOf(TaskScope.BACKGROUND, TaskScope.INSIGHT).forEach { scope ->
            assertTrue(ToolScopePolicy.allows(scope, ToolKind.QUERY))
            assertFalse(ToolScopePolicy.allows(scope, ToolKind.PROPOSE))
            assertFalse(ToolScopePolicy.allows(scope, ToolKind.CONTROL))
        }
    }

    @Test
    fun `只有对话域可以写入`() {
        assertTrue(TaskScope.CHAT.canWrite)
        assertFalse(TaskScope.REPORT.canWrite)
        assertFalse(TaskScope.BACKGROUND.canWrite)
        assertFalse(TaskScope.INSIGHT.canWrite)
    }
}
