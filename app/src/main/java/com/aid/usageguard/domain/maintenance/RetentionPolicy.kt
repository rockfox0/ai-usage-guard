package com.aid.usageguard.domain.maintenance

import java.time.Instant
import java.time.ZoneId

/**
 * 数据库保留策略（v1.10，纯函数），避免表只增不减。
 *
 * 原则：
 * - `usage_session` 明细只保留 90 天；`app_usage_record` 聚合永久保留（趋势/周报/月报用）；
 * - 行为事件/报告/反馈/洞察保留 180 天；
 * - 对话消息保留 90 天；已完成的草案保留 30 天；监控触发记录保留 365 天；
 * - 长期记忆每种 kind 只保留最近 200 条。
 */
data class RetentionCutoffs(
    val usageSessionBeforeDate: String,
    val behaviorEventBefore: Long,
    val agentMessageBefore: Long,
    val agentReportBefore: Long,
    val agentFeedbackBefore: Long,
    val pendingActionBefore: Long,
    val monitorTriggerBefore: Long,
    val insightBeforeDate: String,
    val memoryKeepPerKind: Int,
)

object RetentionPolicy {

    const val USAGE_SESSION_DAYS = 90L
    const val BEHAVIOR_DAYS = 180L
    const val AGENT_MESSAGE_DAYS = 90L
    const val AGENT_REPORT_DAYS = 180L
    const val AGENT_FEEDBACK_DAYS = 180L
    const val PENDING_ACTION_DAYS = 30L
    const val MONITOR_TRIGGER_DAYS = 365L
    const val INSIGHT_DAYS = 180L
    const val MEMORY_KEEP_PER_KIND = 200

    fun cutoffs(now: Long, zone: ZoneId = ZoneId.systemDefault()): RetentionCutoffs {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun dateBefore(days: Long): String = today.minusDays(days).toString()
        fun tsBefore(days: Long): Long = today.minusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
        return RetentionCutoffs(
            usageSessionBeforeDate = dateBefore(USAGE_SESSION_DAYS),
            behaviorEventBefore = tsBefore(BEHAVIOR_DAYS),
            agentMessageBefore = tsBefore(AGENT_MESSAGE_DAYS),
            agentReportBefore = tsBefore(AGENT_REPORT_DAYS),
            agentFeedbackBefore = tsBefore(AGENT_FEEDBACK_DAYS),
            pendingActionBefore = tsBefore(PENDING_ACTION_DAYS),
            monitorTriggerBefore = tsBefore(MONITOR_TRIGGER_DAYS),
            insightBeforeDate = dateBefore(INSIGHT_DAYS),
            memoryKeepPerKind = MEMORY_KEEP_PER_KIND,
        )
    }
}
