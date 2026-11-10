package com.aid.usageguard.domain.usage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.10：AI 任务开始前的数据新鲜度判定。 */
class UsageRefreshPolicyTest {

    @Test
    fun `从未采集过需要刷新`() {
        assertTrue(UsageRefreshPolicy.shouldRefresh(now = 1_000_000L, lastIngestAt = null))
        assertTrue(UsageRefreshPolicy.shouldRefresh(now = 1_000_000L, lastIngestAt = 0L))
    }

    @Test
    fun `超过阈值需要刷新`() {
        assertTrue(UsageRefreshPolicy.shouldRefresh(now = 1_000_000L, lastIngestAt = 900_000L, minIntervalMs = 60_000L))
    }

    @Test
    fun `阈值内不刷新`() {
        assertFalse(UsageRefreshPolicy.shouldRefresh(now = 1_000_000L, lastIngestAt = 970_000L, minIntervalMs = 60_000L))
    }

    @Test
    fun `时间回拨也触发刷新`() {
        assertTrue(UsageRefreshPolicy.shouldRefresh(now = 1_000_000L, lastIngestAt = 1_100_000L))
    }
}
