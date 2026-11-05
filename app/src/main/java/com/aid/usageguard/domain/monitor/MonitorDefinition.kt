package com.aid.usageguard.domain.monitor

/**
 * 用户自定义监控规则（v1.9 Phase 5）。
 *
 * AI 只负责「把自然语言变成规则 + 解释」，本地引擎负责校验、定时计算、判断阈值与通知。
 * 指标必须来自实际采集能力（前台时长 / 启动次数），不允许虚构 Android 拿不到的数据。
 */
enum class MonitorMetric(val label: String, val unit: String) {
    /** 前台使用时长（应用维度或全设备维度），阈值单位：分钟 */
    FOREGROUND_DURATION("使用时长", "分钟"),

    /** 启动次数，阈值单位：次 */
    LAUNCH_COUNT("启动次数", "次"),
}

enum class MonitorOperator(val symbol: String) {
    GREATER_THAN(">"),
    GREATER_EQUAL(">="),
    LESS_THAN("<"),
}

enum class MonitorSchedule(val label: String) {
    EVERY_DAY("每天"),
    WEEKDAYS("工作日"),
    WEEKENDS("周末");

    /** 当天是否生效（v1.9 Phase 5/7：Engine 与 Benchmark 共用同一份判断） */
    fun matches(day: java.time.DayOfWeek): Boolean = when (this) {
        EVERY_DAY -> true
        WEEKDAYS -> day != java.time.DayOfWeek.SATURDAY && day != java.time.DayOfWeek.SUNDAY
        WEEKENDS -> day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY
    }
}

enum class MonitorAction {
    /** 触发后发本地通知 */
    NOTIFY,
}

/**
 * @param packageName null 表示「全部应用合计」（仅 FOREGROUND_DURATION 支持）
 * @param threshold 分钟数（FOREGROUND_DURATION）或次数（LAUNCH_COUNT）
 * @param timeWindow 目前固定 DAILY（按天汇总）；保留字段给后续时间段监控
 */
data class MonitorDefinition(
    val id: String,
    val name: String,
    val packageName: String?,
    val metric: MonitorMetric,
    val operator: MonitorOperator,
    val threshold: Long,
    val schedule: MonitorSchedule,
    val action: MonitorAction = MonitorAction.NOTIFY,
    val timeWindow: String = "DAILY",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 一次触发记录（用于去重与历史） */
data class MonitorTriggerResult(
    val ruleId: String,
    val ruleName: String,
    val packageName: String?,
    val metric: MonitorMetric,
    val value: Long,
    val threshold: Long,
    val message: String,
)
