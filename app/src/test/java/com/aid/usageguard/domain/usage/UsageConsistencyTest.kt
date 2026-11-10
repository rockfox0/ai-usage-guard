package com.aid.usageguard.domain.usage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.10：总分 / 各 App 合计 / 时段分布的一致性校验。 */
class UsageConsistencyTest {

    @Test
    fun `三者相等时一致`() {
        val report = UsageConsistency.verify(totalMinutes = 351, appSumMinutes = 351, slotSumMinutes = 351)
        assertTrue(report.ok)
        assertNull(report.message)
    }

    @Test
    fun `容差内的四舍五入差异算一致`() {
        assertTrue(UsageConsistency.verify(351, 352, 350).ok)
    }

    @Test
    fun `时段分布差太多时给出提示`() {
        val report = UsageConsistency.verify(totalMinutes = 351, appSumMinutes = 351, slotSumMinutes = 104)
        assertFalse(report.ok)
        assertTrue(report.message!!.contains("时段分布"))
    }

    @Test
    fun `App 合计差太多时给出提示`() {
        val report = UsageConsistency.verify(totalMinutes = 351, appSumMinutes = 300, slotSumMinutes = 351)
        assertFalse(report.ok)
        assertTrue(report.message!!.contains("App 合计"))
    }
}
