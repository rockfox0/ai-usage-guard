package com.aid.usageguard.domain.insight

import com.aid.usageguard.domain.model.DailyTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 6：本地候选检测（零 token，先在本地筛出值得分析的事件）。 */
class InsightDetectorTest {

    private fun days(vararg minutes: Long): List<DailyTotal> =
        minutes.mapIndexed { index, m -> DailyTotal(date = "2026-10-${(index + 1).toString().padStart(2, '0')}", minutes = m) }

    @Test
    fun `总时长明显上升被识别`() {
        val daily = days(100, 100, 100, 100, 100, 100, 100, 160, 160, 160, 160, 160, 160, 160)
        val candidates = InsightDetector.detect(daily, emptyMap(), emptyMap(), 0, 360, "2026-11-06")
        assertTrue(candidates.any { it.kind == InsightKind.TOTAL_INCREASE })
    }

    @Test
    fun `总时长稳定时不产生总时长候选`() {
        val daily = days(100, 100, 100, 100, 100, 100, 100, 105, 105, 105, 105, 105, 105, 105)
        val candidates = InsightDetector.detect(daily, emptyMap(), emptyMap(), 0, 360, "2026-11-06")
        assertTrue(candidates.none { it.kind == InsightKind.TOTAL_INCREASE })
    }

    @Test
    fun `单应用明显增长被识别`() {
        val candidates = InsightDetector.detect(
            dailyTotals = emptyList(),
            appLast7 = mapOf("com.tencent.mm" to 120L),
            appPrev7 = mapOf("com.tencent.mm" to 60L),
            todayMinutes = 0,
            dailyTargetMinutes = 360,
            today = "2026-11-06",
        )
        val app = candidates.firstOrNull { it.kind == InsightKind.APP_INCREASE }
        assertTrue(app != null && app.packageName == "com.tencent.mm")
    }

    @Test
    fun `时间转移被识别`() {
        val candidates = InsightDetector.detect(
            dailyTotals = emptyList(),
            appLast7 = mapOf("a" to 40L, "b" to 120L),
            appPrev7 = mapOf("a" to 120L, "b" to 40L),
            todayMinutes = 0,
            dailyTargetMinutes = 360,
            today = "2026-11-06",
        )
        assertTrue(candidates.any { it.kind == InsightKind.TIME_SHIFT })
    }

    @Test
    fun `超目标被识别`() {
        val candidates = InsightDetector.detect(emptyList(), emptyMap(), emptyMap(), 500, 360, "2026-11-06")
        assertTrue(candidates.any { it.kind == InsightKind.TARGET_EXCEEDED })
    }

    @Test
    fun `无变化时没有候选`() {
        val candidates = InsightDetector.detect(
            dailyTotals = days(100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100),
            appLast7 = emptyMap(),
            appPrev7 = emptyMap(),
            todayMinutes = 100,
            dailyTargetMinutes = 360,
            today = "2026-11-06",
        )
        assertEquals(0, candidates.size)
    }

    @Test
    fun `去重键包含日期`() {
        val candidates = InsightDetector.detect(emptyList(), emptyMap(), emptyMap(), 500, 360, "2026-11-06")
        assertTrue(candidates.first().dedupeKey.contains("2026-11-06"))
    }
}
