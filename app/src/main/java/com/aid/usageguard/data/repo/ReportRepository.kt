package com.aid.usageguard.data.repo

import com.aid.usageguard.agent.contract.AgentOutcome
import com.aid.usageguard.agent.contract.AgentRuntime
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.db.AgentReportEntity
import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.model.ReportModel
import com.aid.usageguard.domain.report.LocalReportGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 报告仓储：优先走 Agent（LLM + Tools），失败/未配置时降级为本地规则报告。
 */
class ReportRepository(
    private val db: AppDatabase,
    private val usage: UsageRepository,
    private val behavior: BehaviorRepository,
    private val settings: SettingsRepository,
    private val agentProvider: () -> AgentRuntime,
) {

    suspend fun generateDaily(date: String = UsageEventSource.todayDate()): ReportModel =
        withContext(Dispatchers.IO) {
            val current = settings.current()
            var content: String
            var source = "LOCAL"
            var toolCalls: List<String> = emptyList()

            val outcome: AgentOutcome? = if (current.aiAnalysisEnabled) {
                runCatching { agentProvider().generateDailyReport(date) }.getOrNull()
            } else {
                null
            }

            if (outcome != null && outcome.ok && outcome.text.isNotBlank()) {
                content = outcome.text
                source = "AI"
                toolCalls = outcome.toolCalls
            } else {
                content = LocalReportGenerator.daily(usage, behavior, current, date)
            }
            if (toolCalls.isNotEmpty()) {
                content = content + "\n\n— 本次 Agent 调用的工具：" + toolCalls.joinToString("、")
            }
            persist(ReportModel.TYPE_DAILY, content, source, date)
        }

    suspend fun generateWeekly(): ReportModel = withContext(Dispatchers.IO) {
        val current = settings.current()
        val date = UsageEventSource.todayDate()
        var content: String
        var source = "LOCAL"
        var toolCalls: List<String> = emptyList()

        val outcome: AgentOutcome? = if (current.aiAnalysisEnabled) {
            runCatching { agentProvider().generateWeeklyReport() }.getOrNull()
        } else {
            null
        }
        if (outcome != null && outcome.ok && outcome.text.isNotBlank()) {
            content = outcome.text
            source = "AI"
            toolCalls = outcome.toolCalls
        } else {
            content = LocalReportGenerator.weekly(usage, current)
        }
        if (toolCalls.isNotEmpty()) {
            content = content + "\n\n— 本次 Agent 调用的工具：" + toolCalls.joinToString("、")
        }
        persist(ReportModel.TYPE_WEEKLY, content, source, date)
    }

    suspend fun latest(type: String): ReportModel? = withContext(Dispatchers.IO) {
        db.agentReportDao().latest(type)?.toModel()
    }

    suspend fun recent(limit: Int = 30): List<ReportModel> = withContext(Dispatchers.IO) {
        db.agentReportDao().recent(limit).map { it.toModel() }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { db.agentReportDao().clear() }

    private suspend fun persist(type: String, content: String, source: String, date: String): ReportModel {
        val entity = AgentReportEntity(
            reportType = type,
            content = content,
            createdAt = System.currentTimeMillis(),
            relatedDate = date,
            source = source,
        )
        val id = db.agentReportDao().insert(entity)
        return ReportModel(
            id = id,
            reportType = type,
            content = content,
            createdAt = entity.createdAt,
            relatedDate = date,
            source = source,
        )
    }

    private fun AgentReportEntity.toModel() = ReportModel(
        id = id,
        reportType = reportType,
        content = content,
        createdAt = createdAt,
        relatedDate = relatedDate,
        source = source,
    )

    fun formatTime(ts: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ts))
}
