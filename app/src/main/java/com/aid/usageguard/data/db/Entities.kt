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
)
