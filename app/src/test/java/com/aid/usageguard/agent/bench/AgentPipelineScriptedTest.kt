package com.aid.usageguard.agent.bench

import com.aid.usageguard.agent.plan.EvaluationVerdict
import com.aid.usageguard.agent.plan.ResultEvaluator
import com.aid.usageguard.agent.plan.TaskMode
import com.aid.usageguard.agent.plan.TaskModeClassifier
import com.aid.usageguard.agent.plan.TaskPlanner
import com.aid.usageguard.agent.response.ResponseBlock
import com.aid.usageguard.agent.response.ResponseParser
import com.aid.usageguard.domain.model.DataCoverage
import com.aid.usageguard.domain.model.DataStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本化端到端管线（v1.9 验收补充）：
 * 用固定的「模型输出脚本」串起 分类 → 规划 → 评估 → 结构化回复 的真实代码路径，
 * 证明多阶段管线能协同工作（不依赖网络/设备）。
 */
class AgentPipelineScriptedTest {

    private val input = "分析一下我最近一个月的使用习惯，找出三个问题，然后制定一个改善计划"

    private val plannerScript = """
        ```json
        {"goal":"分析最近一月并给出改善计划","steps":[
          {"step_id":"s1","description":"查询近 30 天总量与趋势","capabilities":["usage_analysis"],"requires_data":true},
          {"step_id":"s2","description":"找出主要问题","capabilities":["usage_analysis","score_review"],"requires_data":true},
          {"step_id":"s3","description":"给出改善计划","capabilities":["quota_control"],"requires_data":false}],
         "completion_criteria":["引用真实数字","指出 3 个问题","给出可执行建议"]}
        ```
    """.trimIndent()

    private fun scriptedFinalAnswer(): String = """
        最近 30 天只有 9 天有记录，以下只基于已有数据：日均 180 分钟，比上月增加 25%。

        ```json
        {"title":"本月概览","blocks":[
          {"type":"statistic","title":"关键数字","items":[{"label":"日均","value":"180 分钟"},{"label":"深夜天数","value":"7 天"}]},
          {"type":"insight","title":"夜间使用偏高","severity":"medium","detail":"22 点后占 35%"},
          {"type":"recommendation","title":"设置夜间专注时段","detail":"22:00-06:00 只允许白名单","requires_confirmation":true},
          {"type":"follow_up","label":"设个监控","prompt":"工作日微信超过 3 小时提醒我"}]}
        ```
    """.trimIndent()

    @Test
    fun `复杂任务从分类到结构化回复的完整管线`() {
        // 1) 意图/模式
        val mode = TaskModeClassifier.classify(input)
        assertEquals(TaskMode.GOAL, mode)

        // 2) 规划（脚本化模型输出）
        val plan = TaskPlanner.parse(plannerScript, mode)
        assertNotNull("计划应解析成功", plan)
        assertEquals(3, plan!!.steps.size)
        assertTrue("计划指令应包含完成条件", TaskPlanner.directive(plan).contains("完成条件"))
        assertTrue("含 control 能力应标记需确认", plan.requiresConfirmation)

        // 3) 评估：第一版模型只给空话 → 必须打回补充
        val weak = ResultEvaluator.evaluate("你最近用得比较多，建议少用一点。", listOf("get_usage_trend"), plan)
        assertEquals(EvaluationVerdict.NEED_MORE, weak.verdict)
        assertTrue("打回时必须给出补充指令", weak.directive.isNotBlank())

        // 4) 数据不足：30 天请求但只有 9 天有效数据 → 必须提示
        val coverage = DataCoverage(
            requestedFrom = "2026-10-01",
            requestedTo = "2026-10-30",
            availableFrom = "2026-10-22",
            availableTo = "2026-10-30",
            validDates = (22..30).map { "2026-10-$it" },
            missingDates = (1..21).map { "2026-10-$it" },
            lastSyncedAt = 1L,
            status = DataStatus.PARTIAL,
            timezone = "Asia/Shanghai",
        )
        val insufficient = ResultEvaluator.evaluate(
            "最近 30 天日均 180 分钟。",
            listOf("get_usage_trend", "get_usage_score"),
            plan,
            coverage = coverage,
            expectsMonthRange = true,
        )
        assertEquals(EvaluationVerdict.INSUFFICIENT_DATA, insufficient.verdict)
        assertTrue(insufficient.caveat.contains("9 天"))

        // 5) 最终回答带结构化块 → 解析、剥离 JSON、保留正文
        val extracted = ResponseParser.extract(scriptedFinalAnswer())
        assertNotNull(extracted)
        assertEquals(4, extracted!!.response.blocks.size)
        assertTrue(extracted.response.blocks[0] is ResponseBlock.StatisticCard)
        assertTrue(extracted.response.blocks[2] is ResponseBlock.RecommendationCard)
        assertTrue("正文应保留数据说明", extracted.text.contains("只有 9 天有记录"))
        assertFalse("正文不应残留 JSON", extracted.text.contains("```json"))

        // 6) 评估通过（有数字、已说明数据缺口）
        val ok = ResultEvaluator.evaluate(
            extracted.text,
            listOf("get_usage_trend", "get_usage_score"),
            plan,
            coverage = coverage,
            expectsMonthRange = true,
        )
        assertEquals(EvaluationVerdict.OK, ok.verdict)
    }
}
