package com.aid.usageguard.domain.usage

/**
 * 使用数据「是否该重新采集」的判定（v1.10，纯函数）。
 *
 * 背景：AI 工具只读数据库；如果最近一次采集是 30 分钟前，回答里的数字会偏小。
 * 任务开始前调用 [shouldRefresh]：超过 [minIntervalMs] 就做一次增量采集，代价很小。
 */
object UsageRefreshPolicy {

    fun shouldRefresh(now: Long, lastIngestAt: Long?, minIntervalMs: Long = 60_000L): Boolean {
        if (lastIngestAt == null || lastIngestAt <= 0L) return true
        val age = now - lastIngestAt
        return age >= minIntervalMs || age < 0L // age<0 说明时间被回拨，也重新采集
    }
}
