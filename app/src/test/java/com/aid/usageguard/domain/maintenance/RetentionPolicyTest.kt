package com.aid.usageguard.domain.maintenance

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.10：数据库保留策略的截止时间。 */
class RetentionPolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 10, 9, 30, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `日期类截止值`() {
        val cut = RetentionPolicy.cutoffs(now, zone)
        assertEquals(LocalDate.of(2026, 8, 12).toString(), cut.usageSessionBeforeDate) // 90 天前
        assertEquals(LocalDate.of(2026, 5, 14).toString(), cut.insightBeforeDate) // 180 天前
    }

    @Test
    fun `时间戳类截止值按当天零点`() {
        val cut = RetentionPolicy.cutoffs(now, zone)
        val expected = LocalDate.of(2026, 11, 10).minusDays(90)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, cut.usageSessionCutoffCheck()) // 见下方扩展函数
        assertTrue(cut.pendingActionBefore > cut.agentMessageBefore)
        assertEquals(200, cut.memoryKeepPerKind)
    }

    private fun RetentionCutoffs.usageSessionCutoffCheck(): Long =
        LocalDate.parse(usageSessionBeforeDate).atStartOfDay(zone).toInstant().toEpochMilli()
}
