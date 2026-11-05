package com.aid.usageguard.ui.report

import androidx.lifecycle.ViewModel
import com.aid.usageguard.data.repo.ReportRepository
import com.aid.usageguard.data.repo.ScoreRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.model.ReportModel
import com.aid.usageguard.domain.score.UsageScore
import com.aid.usageguard.domain.score.WeekScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 报告页 UI 状态 */
data class ReportUiState(
    val loading: Boolean = true,
    /** 正在生成的类型：[ReportModel.TYPE_DAILY] / [ReportModel.TYPE_WEEKLY]，null 表示空闲 */
    val generating: String? = null,
    val latestDaily: ReportModel? = null,
    val latestWeekly: ReportModel? = null,
    val history: List<ReportModel> = emptyList(),
    /** 设置里的 AI 开关，用于顶部提示 */
    val aiEnabled: Boolean = true,
    val message: String? = null,
    /** 顶部「今日评分」卡（Container.score.persist() 顺带落库） */
    val todayScore: UsageScore? = null,
    /** 顶部「本周总结」卡（Container.score.week()） */
    val weekScore: WeekScore? = null,
)

/**
 * 报告页 ViewModel：生成日报/周报 + 最新/历史列表 + 清空。
 *
 * 生成走 [ReportRepository]：AI 可用就出 AI 报告，失败/未配置则本地规则兜底，
 * 结果里的 `source` 字段（AI / LOCAL）就是页面上「来源标签」的数据源。
 */
class ReportViewModel(
    private val reports: ReportRepository,
    private val settings: SettingsRepository,
    private val scores: ScoreRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        reload(showLoading = true)
    }

    fun refresh() = reload(showLoading = true)

    /** 生成一类报告；生成期间按钮置灰，避免重复点击打爆 AI 接口 */
    fun generate(type: String) {
        if (_state.value.generating != null) return
        scope.launch {
            _state.update { it.copy(generating = type, message = null) }
            val report = runCatching {
                if (type == ReportModel.TYPE_WEEKLY) reports.generateWeekly() else reports.generateDaily()
            }.getOrNull()
            _state.update { it.copy(generating = null) }
            if (report == null) {
                _state.update { it.copy(message = "生成失败：请稍后重试（报告会优先走 AI，失败时降级本地规则）") }
                return@launch
            }
            reload(showLoading = false)
            _state.update {
                it.copy(
                    message = if (report.source == SOURCE_AI) {
                        "已生成 ${typeLabel(report.reportType)}（AI 分析）"
                    } else {
                        "已生成 ${typeLabel(report.reportType)}（本地规则）"
                    },
                )
            }
        }
    }

    /** 清空全部报告（仓储只提供整体清空） */
    fun deleteAll() {
        scope.launch {
            runCatching { reports.deleteAll() }
            reload(showLoading = false)
            _state.update { it.copy(message = "已清空全部报告") }
        }
    }

    fun formatTime(ts: Long): String = reports.formatTime(ts)

    private fun reload(showLoading: Boolean) {
        scope.launch {
            if (showLoading) _state.update { it.copy(loading = true) }
            val history = runCatching { reports.recent(HISTORY_LIMIT) }.getOrDefault(emptyList())
            val daily = runCatching { reports.latest(ReportModel.TYPE_DAILY) }.getOrNull()
            val weekly = runCatching { reports.latest(ReportModel.TYPE_WEEKLY) }.getOrNull()
            val cfg = runCatching { settings.current() }.getOrNull()
            // 刷新/生成报告后同时刷新评分：persist() 返回今日评分（顺带写入 daily_score 表），
            // week() 聚合最近 7 天，供顶部「今日评分 / 本周总结」两张卡片使用
            val todayScore = runCatching { scores.persist() }.getOrNull()
            val weekScore = runCatching { scores.week() }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    history = history,
                    latestDaily = daily,
                    latestWeekly = weekly,
                    aiEnabled = cfg?.aiAnalysisEnabled ?: it.aiEnabled,
                    todayScore = todayScore,
                    weekScore = weekScore,
                )
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    companion object {
        const val SOURCE_AI = "AI"
        const val SOURCE_LOCAL = "LOCAL"
        private const val HISTORY_LIMIT = 40
        private const val TYPE_WEEKLY_LABEL = "周报"
        private const val TYPE_DAILY_LABEL = "日报"

        fun typeLabel(type: String): String =
            if (type == ReportModel.TYPE_WEEKLY) TYPE_WEEKLY_LABEL else TYPE_DAILY_LABEL
    }
}
