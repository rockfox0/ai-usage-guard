package com.aid.usageguard.agent.plan

import com.aid.usageguard.agent.contract.AgentContext
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.9 Phase 2：只决定"要不要规划"，简单问题必须保持 DIRECT（不额外消耗模型请求）。 */
class TaskModeClassifierTest {

    @Test
    fun `简单查询保持直答`() {
        assertEquals(TaskMode.DIRECT, TaskModeClassifier.classify("今天用了多久"))
        assertEquals(TaskMode.DIRECT, TaskModeClassifier.classify("我今天多少分"))
        assertEquals(TaskMode.DIRECT, TaskModeClassifier.classify("how many minutes today"))
    }

    @Test
    fun `多维度分析进入分析模式`() {
        assertEquals(TaskMode.ANALYSIS, TaskModeClassifier.classify("最近一周我的手机使用习惯有什么问题？"))
        assertEquals(TaskMode.ANALYSIS, TaskModeClassifier.classify("帮我分析一下最近的使用趋势"))
        assertEquals(TaskMode.ANALYSIS, TaskModeClassifier.classify("为什么最近晚上总是使用手机"))
    }

    @Test
    fun `带目标与计划的需求进入目标模式`() {
        assertEquals(
            TaskMode.GOAL,
            TaskModeClassifier.classify("分析我最近一个月的游戏使用情况，然后帮我制定限制方案"),
        )
        assertEquals(TaskMode.GOAL, TaskModeClassifier.classify("帮我制定一个晚上少玩手机的计划"))
        assertEquals(TaskMode.GOAL, TaskModeClassifier.classify("以后每天只能玩游戏 1 小时"))
    }

    @Test
    fun `应用详情页的泛问题按分析处理`() {
        val ctx = AgentContext(entryPage = "app_detail", focusPackage = "com.ss.android.ugc.aweme")
        assertEquals(TaskMode.ANALYSIS, TaskModeClassifier.classify("最近怎么样", ctx))
    }

    @Test
    fun `长问题偏向分析`() {
        val long = "我想知道".repeat(30)
        assertEquals(TaskMode.ANALYSIS, TaskModeClassifier.classify(long))
    }
}
