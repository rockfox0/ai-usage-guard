package com.aid.usageguard.domain.aggregate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 方案 T01/T03：跨午夜与窗口边界的每日聚合。
 */
class DailyAggregatorTest {

    private fun ts(hour: Int, minute: Int, dayOffset: Int = 0): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
        cal.add(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.add(java.util.Calendar.MINUTE, minute)
        return cal.timeInMillis
    }

    private val dayStart = ts(0, 0, 0)
    private val dayEnd = dayStart + 24 * 60 * 60 * 1000L

    @Test
    fun `跨午夜的会话在两天分别统计实际重叠时长`() {
        // 23:50 -> 次日 00:20，共 30 分钟
        val session = SessionRange("com.demo.app", ts(23, 50), ts(0, 20, 1))

        val first = DailyAggregator.aggregate(listOf(session), dayStart, dayEnd)
        val second = DailyAggregator.aggregate(
            listOf(session),
            dayEnd,
            dayEnd + 24 * 60 * 60 * 1000L,
        )

        assertEquals(1, first.size)
        assertEquals(10 * 60_000L, first[0].durationMs)   // 23:50-24:00
        assertEquals(1, first[0].launchCount)             // 启动次数归属开始日
        assertEquals(1, second.size)
        assertEquals(20 * 60_000L, second[0].durationMs)  // 00:00-00:20
        assertEquals(0, second[0].launchCount)            // 拆分不增加启动次数
        assertEquals(
            30 * 60_000L,
            first.sumOf { it.durationMs } + second.sumOf { it.durationMs },
        )
    }

    @Test
    fun `窗口前开始窗口内结束的会话只计重叠部分`() {
        val windowStart = ts(10, 0)
        val windowEnd = ts(11, 0)
        val session = SessionRange("p", ts(9, 30), ts(10, 20))
        val slices = DailyAggregator.aggregate(listOf(session), windowStart, windowEnd)
        assertEquals(20 * 60_000L, slices.single().durationMs)
            .also { } // 10:00-10:20
    }

    @Test
    fun `跨午夜的会话仍被视为一次连续会话`() {
        // 原会话区间不变，聚合只切分统计口径
        val session = SessionRange("p", ts(23, 50), ts(0, 20, 1))
        assertEquals(30 * 60_000L, session.endTime - session.startTime)
        assertTrue(session.endTime > session.startTime)
    }

    @Test
    fun `无重叠会话不产生切片`() {
        val session = SessionRange("p", ts(1, 0, 1), ts(2, 0, 1))
        assertTrue(DailyAggregator.aggregate(listOf(session), dayStart, dayEnd).isEmpty())
    }

    @Test
    fun `同一天多次启动按次数累计`() {
        val sessions = listOf(
            SessionRange("p", ts(9, 0), ts(9, 10)),
            SessionRange("p", ts(15, 0), ts(15, 5)),
        )
        val slice = DailyAggregator.aggregate(sessions, dayStart, dayEnd).single()
        assertEquals(2, slice.launchCount)
        assertEquals(15 * 60_000L, slice.durationMs)
        assertEquals(ts(15, 5), slice.lastUsedMs)
    }
}
