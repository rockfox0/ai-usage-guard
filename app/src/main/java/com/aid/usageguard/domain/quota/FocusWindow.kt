package com.aid.usageguard.domain.quota

/**
 * 专注时段（v1.8.2）：在指定时间段内，只允许白名单（与系统保护）应用使用。
 *
 * - 支持跨午夜（如 22:00-07:00）；
 * - 起止小时相同视为「全天生效」；
 * - 与额度无关：时段内即使额度还没用完，非白名单应用也会被拦。
 */
object FocusWindow {

    /** 当前小时是否落在 [startHour, endHour) 窗口内（支持跨午夜） */
    fun isActive(nowHour: Int, startHour: Int, endHour: Int): Boolean {
        val start = startHour.coerceIn(0, 23)
        val end = endHour.coerceIn(0, 23)
        val hour = nowHour.coerceIn(0, 23)
        if (start == end) return true // 起止相同：全天生效
        return if (start < end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }

    fun label(startHour: Int, endHour: Int): String =
        "%02d:00-%02d:00".format(startHour.coerceIn(0, 23), endHour.coerceIn(0, 23))
}
