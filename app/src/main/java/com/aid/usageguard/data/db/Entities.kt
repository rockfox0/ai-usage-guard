package com.aid.usageguard.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 应用基础信息 */
@Entity(tableName = "app_info")
data class AppInfoEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val firstInstallTime: Long,
    val category: String? = null,
)

/** 一次前台使用区间（UsageEvents 归并而来） */
@Entity(
    tableName = "usage_session",
    indices = [Index("date"), Index("packageName"), Index("startTime")],
)
data class UsageSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
    /** yyyy-MM-dd（本地时区） */
    val date: String,
)

/** 每日聚合 */
@Entity(
    tableName = "app_usage_record",
    primaryKeys = ["packageName", "date"],
    indices = [Index("date")],
)
data class AppUsageRecordEntity(
    val packageName: String,
    val date: String,
    val totalDuration: Long,
    val launchCount: Int,
    val lastUsedTime: Long,
)

/** 行为分析产生的事件 */
@Entity(
    tableName = "behavior_event",
    indices = [Index("date"), Index("dedupKey"), Index("timestamp")],
)
data class BehaviorEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val packageName: String?,
    val severity: String,
    val description: String,
    val timestamp: Long,
    val date: String,
    /** 去重/冷却键：同类型同应用同窗口只留一条 */
    val dedupKey: String,
)

/** AI / 本地生成的分析报告 */
@Entity(tableName = "agent_report", indices = [Index("reportType"), Index("relatedDate")])
data class AgentReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** DAILY / WEEKLY */
    val reportType: String,
    val content: String,
    val createdAt: Long,
    val relatedDate: String,
    /** AI / LOCAL */
    val source: String,
)

/** 限制规则（为 Device Owner 等未来实现预留统一结构） */
@Entity(tableName = "restriction_rule")
data class RestrictionRuleEntity(
    @PrimaryKey val packageName: String,
    val dailyLimitMinutes: Int = 0,
    val blockedStartTime: Int = -1,
    val blockedEndTime: Int = -1,
    val enabled: Boolean = true,
)

/** 通用同步状态（增量采集游标等） */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: Long,
)

/** Agent 对话消息（AgentMemory 持久化） */
@Entity(tableName = "agent_message", indices = [Index("sessionId"), Index("timestamp")])
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    /** user / assistant / tool */
    val role: String,
    val content: String,
    val timestamp: Long,
    /** 模型调用的工具名，逗号分隔，可空 */
    val toolCalls: String? = null,
)

/** 每日使用评分（时长 + 时段 + 目标对比），用于今日打分 / 每日周总结 / 历史趋势 */
@Entity(tableName = "daily_score")
data class DailyScoreEntity(
    /** yyyy-MM-dd */
    @PrimaryKey val date: String,
    val score: Int,
    val grade: String,
    val headline: String,
    val verdict: String,
    val totalMinutes: Long,
    val targetMinutes: Int,
    val lateNightMinutes: Long,
    val longestSessionMinutes: Long,
    val launchCount: Int,
    /** 时段分布，JSON: {"early":..,"morning":..,"afternoon":..,"evening":..,"night":..} */
    val slotsJson: String,
    val updatedAt: Long,
    /** 评分算法版本，便于以后标记旧版本记录 */
    val algorithmVersion: Int = 1,
    /** VALID / PARTIAL / NO_PERMISSION / NOT_SYNCED / FAILED */
    val dataStatus: String = "VALID",
)

/**
 * AI 提议的「待确认操作」（v1.7 方案 6.2）。
 * 控制类工具不再直接改设置，而是生成草案；用户确认后由本地执行器按保存参数执行。
 */
@Entity(
    tableName = "pending_action",
    indices = [Index("status"), Index("createdAt")],
)
data class PendingActionEntity(
    @PrimaryKey val opId: String,
    val taskId: String,
    /** SET_DAILY_TARGET / SET_QUIET_HOURS / SET_THEME / ... / SET_APP_RESTRICTION */
    val type: String,
    /** 目标应用包名集合（逗号分隔），无则空串 */
    val packages: String,
    val paramsJson: String,
    val beforeJson: String,
    val afterJson: String,
    /** PENDING / CONFIRMED / EXECUTED / FAILED / EXPIRED / CANCELLED */
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val confirmedAt: Long? = null,
    val executedAt: Long? = null,
    val resultJson: String? = null,
    val error: String? = null,
)

/** 单应用每日时长限额（v1.8）。minutes<=0 视为未设置 */
@Entity(tableName = "app_daily_limit")
data class AppDailyLimitEntity(
    @PrimaryKey val packageName: String,
    val dailyLimitMinutes: Int,
    val updatedAt: Long,
)

/** 长期记忆：用户画像 / 决策记录等，kind 区分（v1.9 Phase 3） */
@Entity(tableName = "agent_memory", primaryKeys = ["kind", "memKey"])
data class AgentMemoryEntity(
    val kind: String,
    val memKey: String,
    val valueJson: String,
    val confidence: Double = 1.0,
    val updatedAt: Long,
)

/** 用户长期目标（v1.9 Phase 3）：目标 + 时间段 + 状态（ACTIVE / DONE / PAUSED） */
@Entity(tableName = "agent_goal")
data class AgentGoalEntity(
    @PrimaryKey val id: String,
    val goal: String,
    val targetPeriod: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 用户反馈（v1.9 Phase 3）：拒绝/偏好信号，用于调整后续建议 */
@Entity(tableName = "agent_feedback")
data class AgentFeedbackEntity(
    @PrimaryKey val id: String,
    /** suggestion / plan / insight / general */
    val targetType: String,
    /** 关联对象 id（建议/草案/洞察），可为空 */
    val targetId: String,
    /** REJECT / PREFER / ACCEPT */
    val signal: String,
    val reason: String,
    val createdAt: Long,
)
