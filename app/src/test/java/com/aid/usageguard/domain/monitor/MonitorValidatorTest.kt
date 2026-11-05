package com.aid.usageguard.domain.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 5：监控规则校验（AI 生成/用户手写都必须先过这里）。 */
class MonitorValidatorTest {

    private fun rule(
        name: String = "工作日微信 3 小时",
        packageName: String? = "com.tencent.mm",
        metric: MonitorMetric = MonitorMetric.FOREGROUND_DURATION,
        threshold: Long = 180,
        schedule: MonitorSchedule = MonitorSchedule.WEEKDAYS,
        enabled: Boolean = true,
    ) = MonitorDefinition(
        id = "r1",
        name = name,
        packageName = packageName,
        metric = metric,
        operator = MonitorOperator.GREATER_THAN,
        threshold = threshold,
        schedule = schedule,
        enabled = enabled,
    )

    @Test
    fun `合法时长规则通过`() {
        assertEquals(MonitorValidator.Result.Ok, MonitorValidator.validate(rule(), emptyList()))
    }

    @Test
    fun `合法启动次数规则通过`() {
        val r = rule(metric = MonitorMetric.LAUNCH_COUNT, threshold = 30)
        assertEquals(MonitorValidator.Result.Ok, MonitorValidator.validate(r, emptyList()))
    }

    @Test
    fun `全部应用只支持使用时长`() {
        val r = rule(packageName = null, metric = MonitorMetric.LAUNCH_COUNT, threshold = 30)
        assertTrue(MonitorValidator.validate(r, emptyList()) is MonitorValidator.Result.Rejected)
    }

    @Test
    fun `阈值越界被拒绝`() {
        assertTrue(MonitorValidator.validate(rule(threshold = 3), emptyList()) is MonitorValidator.Result.Rejected)
        assertTrue(MonitorValidator.validate(rule(threshold = 2000), emptyList()) is MonitorValidator.Result.Rejected)
        val launch = rule(metric = MonitorMetric.LAUNCH_COUNT, threshold = 0)
        assertTrue(MonitorValidator.validate(launch, emptyList()) is MonitorValidator.Result.Rejected)
    }

    @Test
    fun `同应用同指标重复被拒绝`() {
        val existing = listOf(rule())
        assertTrue(MonitorValidator.validate(rule(), existing) is MonitorValidator.Result.Rejected)
    }

    @Test
    fun `最多五条启用规则`() {
        val existing = (1..5).map { rule(name = "规则$it", packageName = "pkg$it") }
        assertTrue(MonitorValidator.validate(rule(packageName = "new.pkg"), existing) is MonitorValidator.Result.Rejected)
    }

    @Test
    fun `名称过长被拒绝`() {
        assertTrue(
            MonitorValidator.validate(rule(name = "太长".repeat(20)), emptyList()) is MonitorValidator.Result.Rejected,
        )
    }

    @Test
    fun `描述包含周期 应用 阈值`() {
        val text = MonitorValidator.describe(rule()) { "微信" }
        assertTrue(text.contains("工作日"))
        assertTrue(text.contains("微信"))
        assertTrue(text.contains("180 分钟"))
        assertTrue(text.contains("超过"))
    }
}
