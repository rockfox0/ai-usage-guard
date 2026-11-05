package com.aid.usageguard.agent.plan

import com.aid.usageguard.domain.model.DataCoverage
import com.aid.usageguard.domain.model.DataStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 2：工具 ok 不等于任务完成；数据不足必须被识别。 */
class ResultEvaluatorTest {

    private fun plan(vararg caps: String) = TaskPlan(
        taskId = "t",
        goal = "分析",
        mode = TaskMode.ANALYSIS,
        steps = listOf(TaskStep("s1", "查询数据", caps.toList())),
        completionCriteria = listOf("引用真实数据"),
    )

    private fun coverage(validDays: Int) = DataCoverage(
        requestedFrom = "2026-10-01",
        requestedTo = "2026-10-30",
        availableFrom = "2026-10-23",
        availableTo = "2026-10-30",
        validDates = (1..validDays).map { "2026-10-${it.toString().padStart(2, '0')}" },
        missingDates = (validDays + 1..30).map { "2026-10-${it.toString().padStart(2, '0')}" },
        lastSyncedAt = 1L,
        status = if (validDays >= 30) DataStatus.VALID else DataStatus.PARTIAL,
        timezone = "Asia/Shanghai",
    )

    @Test
    fun `空回答要求补充`() {
        val r = ResultEvaluator.evaluate("  ", emptyList(), plan("usage_analysis"))
        assertEquals(EvaluationVerdict.NEED_MORE, r.verdict)
    }

    @Test
    fun `分析任务没调用数据工具要求补充`() {
        val r = ResultEvaluator.evaluate("我觉得还行", emptyList(), plan("usage_analysis"))
        assertEquals(EvaluationVerdict.NEED_MORE, r.verdict)
        assertTrue(r.directive.contains("get_daily_usage"))
    }

    @Test
    fun `调了工具但结论没有数字也要补充`() {
        val r = ResultEvaluator.evaluate(
            "你最近用得挺多的，建议少用一点。",
            listOf("get_usage_trend"),
            plan("usage_analysis"),
        )
        assertEquals(EvaluationVerdict.NEED_MORE, r.verdict)
    }

    @Test
    fun `有数据有数字则通过`() {
        val r = ResultEvaluator.evaluate(
            "最近 7 天日均 180 分钟，比上周增加 20%。",
            listOf("get_usage_trend"),
            plan("usage_analysis"),
        )
        assertEquals(EvaluationVerdict.OK, r.verdict)
    }

    @Test
    fun `30 天请求但只有 8 天数据要提示不完整`() {
        val r = ResultEvaluator.evaluate(
            "最近 30 天日均 200 分钟。",
            listOf("get_usage_trend"),
            plan("usage_analysis"),
            coverage = coverage(8),
            expectsMonthRange = true,
        )
        assertEquals(EvaluationVerdict.INSUFFICIENT_DATA, r.verdict)
        assertTrue(r.caveat.contains("8 天"))
    }

    @Test
    fun `模型已说明数据不足时不再重复追加`() {
        val r = ResultEvaluator.evaluate(
            "最近 30 天只有 8 天数据不完整，日均约 200 分钟。",
            listOf("get_usage_trend"),
            plan("usage_analysis"),
            coverage = coverage(8),
            expectsMonthRange = true,
        )
        assertEquals(EvaluationVerdict.OK, r.verdict)
    }

    @Test
    fun `没有计划时不因缺数据工具而拦截`() {
        val r = ResultEvaluator.evaluate("今天用了 20 分钟。", listOf("get_daily_usage"), null)
        assertEquals(EvaluationVerdict.OK, r.verdict)
    }
}
