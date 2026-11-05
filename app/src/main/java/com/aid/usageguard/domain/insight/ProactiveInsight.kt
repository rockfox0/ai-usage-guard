package com.aid.usageguard.domain.insight

/** 主动洞察类型（v1.9 Phase 6） */
enum class InsightKind(val label: String) {
    TOTAL_INCREASE("总使用时长上升"),
    APP_INCREASE("单个应用明显增加"),
    TIME_SHIFT("时间在应用之间转移"),
    TARGET_EXCEEDED("超出每日目标"),
}

/** 本地规则发现的候选事件（还没经过 LLM 润色） */
data class InsightCandidate(
    val kind: InsightKind,
    /** 去重键：同类型同主体同一天只生成一次 */
    val dedupeKey: String,
    /** 简短事实描述（给 LLM 与去重日志用，不直接展示给用户） */
    val fact: String,
    val packageName: String? = null,
    val deltaPercent: Int = 0,
)

/** 落库/展示用的主动洞察 */
data class ProactiveInsight(
    val id: String,
    val date: String,
    val kind: InsightKind,
    val title: String,
    val detail: String,
    val suggestion: String,
    val createdAt: Long = System.currentTimeMillis(),
    val read: Boolean = false,
)
