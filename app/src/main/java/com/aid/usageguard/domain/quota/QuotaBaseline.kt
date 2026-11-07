package com.aid.usageguard.domain.quota

/**
 * 守护进程内的额度基线合并（v1.9.4，纯函数）。
 *
 * 背景：GuardService 在启动时从数据库读一次「今日已用」，之后靠前台切换/20 秒 ticker 累加。
 * 一旦服务被 OEM 冻结、或数据库同步晚于实际使用，内部计数会**低于**真实已用时长，
 * 用户就会遇到「已经 62 分钟了、限制是 60 分钟，却还能继续用」。
 *
 * 解决：每次向数据库要一次今日统计，和内部累计取 **max**（数据库只增不减，不会回退）。
 */
object QuotaBaseline {

    /** 总时长取最大值（分钟） */
    fun mergeTotalMinutes(internalMinutes: Long, databaseMinutes: Long): Long =
        maxOf(internalMinutes.coerceAtLeast(0L), databaseMinutes.coerceAtLeast(0L))

    /** 各应用时长逐项取最大值 */
    fun mergePerApp(
        internal: Map<String, Long>,
        database: Map<String, Long>,
    ): Map<String, Long> {
        if (database.isEmpty()) return internal
        val merged = HashMap<String, Long>(maxOf(internal.size, database.size))
        (internal.keys + database.keys).forEach { pkg ->
            merged[pkg] = maxOf(internal[pkg] ?: 0L, database[pkg] ?: 0L)
        }
        return merged
    }
}
