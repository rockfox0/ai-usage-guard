package com.aid.usageguard.domain.aggregate

/**
 * 每日聚合的纯逻辑：把「真实会话」切成按本地日期的统计切片。
 *
 * 规则（对应 v1.7 方案 T01/T03）：
 * - 跨午夜的会话在两天各计其实际重叠时长，总时长不变，但**不会**因为拆分而增加启动次数；
 * - 启动次数按会话开始时间归属当天；
 * - 最后使用时间按与当天重叠部分的结束时间计算。
 */
data class SessionRange(
    val packageName: String,
    val startTime: Long,
    val endTime: Long,
)

data class DaySlice(
    val packageName: String,
    val durationMs: Long,
    val launchCount: Int,
    val lastUsedMs: Long,
)

object DailyAggregator {

    /** 统计 [dayStart, dayEnd) 这一天内的应用使用切片。 */
    fun aggregate(
        sessions: List<SessionRange>,
        dayStart: Long,
        dayEnd: Long,
    ): List<DaySlice> {
        if (dayEnd <= dayStart) return emptyList()
        val acc = LinkedHashMap<String, MutableSlice>()
        for (session in sessions) {
            val end = session.endTime.coerceAtMost(dayEnd)
            val start = session.startTime.coerceAtLeast(dayStart)
            val overlap = end - start
            val startedToday = session.startTime >= dayStart && session.startTime < dayEnd
            if (overlap <= 0 && !startedToday) continue
            val slice = acc.getOrPut(session.packageName) { MutableSlice() }
            if (overlap > 0) {
                slice.duration += overlap
                slice.lastUsed = maxOf(slice.lastUsed, end)
            }
            if (startedToday) slice.launches += 1
        }
        return acc.map { (pkg, slice) ->
            DaySlice(
                packageName = pkg,
                durationMs = slice.duration,
                launchCount = slice.launches,
                lastUsedMs = slice.lastUsed,
            )
        }.sortedByDescending { it.durationMs }
    }

    private class MutableSlice {
        var duration: Long = 0L
        var launches: Int = 0
        var lastUsed: Long = 0L
    }
}
