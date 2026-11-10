package com.aid.usageguard.domain.model

/** 单个 App 的今日使用摘要 */
data class AppUsageSummary(
    val packageName: String,
    val appName: String,
    val minutes: Long,
    val lastUsed: Long = 0L,
    val launchCount: Int = 0,
)

/** 某一天的总使用时长 */
data class DailyTotal(
    val date: String,
    val minutes: Long,
)

/** 一次使用区间 */
data class SessionSlice(
    val startTime: Long,
    val endTime: Long,
    val minutes: Long,
)

/** App 详情页数据 */
data class AppDetailModel(
    val packageName: String,
    val appName: String,
    val todayMinutes: Long,
    val weekMinutes: Long,
    val averageMinutes: Long,
    val launchCount: Int,
    val lastUsed: Long,
    val daily: List<DailyTotal>,
    val sessions: List<SessionSlice>,
)

enum class BehaviorEventType {
    USAGE_INCREASE,      // 今日明显高于 7 日均值
    LONG_SESSION,        // 单次连续使用过长
    LATE_NIGHT,          // 深夜使用
    FREQUENT_LAUNCH,     // 短时间频繁打开
    OVER_TOTAL_TARGET,   // 总时长超目标
    MULTI_DAY_GROWTH;    // 连续多日增长

    companion object {
        fun from(value: String): BehaviorEventType =
            entries.firstOrNull { it.name == value } ?: USAGE_INCREASE
    }
}

enum class BehaviorSeverity { LOW, MEDIUM, HIGH }

data class BehaviorEventModel(
    val id: Long = 0,
    val eventType: BehaviorEventType,
    val packageName: String?,
    val severity: BehaviorSeverity,
    val description: String,
    val timestamp: Long,
    val date: String,
)

data class ReportModel(
    val id: Long = 0,
    val reportType: String,
    val content: String,
    val createdAt: Long,
    val relatedDate: String,
    val source: String,
) {
    companion object {
        const val TYPE_DAILY = "DAILY"
        const val TYPE_WEEKLY = "WEEKLY"
    }
}

data class ChatMessageModel(
    val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long,
    val toolCalls: List<String> = emptyList(),
    /** v1.10：该条回复的结构化卡片 JSON（重进助手页可还原） */
    val structuredJson: String? = null,
)

fun Long.toMinutes(): Long = this / 60_000L
