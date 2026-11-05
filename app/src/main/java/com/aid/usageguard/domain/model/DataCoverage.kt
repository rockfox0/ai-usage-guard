package com.aid.usageguard.domain.model

/** 数据覆盖状态（v1.7 方案 4.2）：区分「有效 / 部分有效 / 无权限 / 未同步 / 失败」 */
enum class DataStatus(val label: String) {
    VALID("有效"),
    PARTIAL("部分有效"),
    NO_PERMISSION("无使用情况权限"),
    NOT_SYNCED("尚未同步"),
    FAILED("采集失败"),
}

/**
 * 某次查询的数据覆盖信息：请求范围 vs 实际可用范围。
 * 不允许把缺失日期补零后参与行为结论（方案 4.2）。
 */
data class DataCoverage(
    val requestedFrom: String,
    val requestedTo: String,
    val availableFrom: String?,
    val availableTo: String?,
    val validDates: List<String>,
    val missingDates: List<String>,
    val lastSyncedAt: Long?,
    val status: DataStatus,
    val timezone: String,
) {
    val hasAnyData: Boolean get() = validDates.isNotEmpty()

    /** 给 AI / UI 的一句话描述 */
    fun describe(): String = when (status) {
        DataStatus.NO_PERMISSION -> "没有使用情况访问权限，无法读取统计数据"
        DataStatus.NOT_SYNCED -> "尚未完成首次同步，暂时没有统计数据"
        DataStatus.FAILED -> "上次采集失败，数据可能不完整"
        DataStatus.PARTIAL -> "请求 $requestedFrom ~ $requestedTo，实际只有 ${validDates.size} 天有效数据" +
            if (missingDates.isNotEmpty()) "（缺少 ${missingDates.size} 天）" else ""
        DataStatus.VALID -> "数据完整：$requestedFrom ~ $requestedTo（${validDates.size} 天）"
    }
}
