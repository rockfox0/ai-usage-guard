package com.aid.usageguard.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aid.usageguard.agent.contract.AgentRuntime
import com.aid.usageguard.data.repo.BehaviorRepository
import com.aid.usageguard.data.repo.ScoreRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.model.AppUsageSummary
import com.aid.usageguard.domain.model.DailyTotal
import com.aid.usageguard.domain.quota.FocusWindow
import com.aid.usageguard.domain.score.UsageScore
import com.aid.usageguard.restriction.RestrictionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/** 今日使用分布里的一个切片（Top N 之外的 App 汇总成「其他」） */
data class UsageShare(
    val label: String,
    val minutes: Long,
    /** 占今日总时长的比例 0f..1f */
    val share: Float,
)

/**
 * Dashboard 的完整 UI 状态。
 * 所有数字都来自 [UsageRepository] / [BehaviorRepository] 的真实查询，没有任何占位数据。
 */
data class DashboardUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val todayMinutes: Long = 0L,
    val targetMinutes: Long = 0L,
    /** 今日使用最长的 5 个 App（按分钟降序） */
    val topApps: List<AppUsageSummary> = emptyList(),
    /** 今日使用分布（Top 8 + 其他） */
    val distribution: List<UsageShare> = emptyList(),
    /** 最近 7 天（含今天）每日总时长 */
    val weekTotals: List<DailyTotal> = emptyList(),
    /** 今日评分（分数 / 等级 / 词条 / 时段分布）；读取失败为 null，不影响其它卡片 */
    val score: UsageScore? = null,
    /** 限制模式（v1.8.1：额度内自由使用 / 始终限制 / 只限制指定） */
    val restrictionMode: RestrictionMode = RestrictionMode.QUOTA_AFTER_BUDGET,
    /** 专注时段当前是否生效（生效时只有白名单应用可用） */
    val focusWindowActive: Boolean = false,
    val anomalyCount: Int = 0,
    val agentConfigured: Boolean = false,
    val error: String? = null,
) {
    /** 每日目标完成比例（0f..1f，超目标后仍封顶 1f，超出的部分用 isOverGoal 提示） */
    val goalProgress: Float
        get() = if (targetMinutes <= 0L) 0f else (todayMinutes.toFloat() / targetMinutes.toFloat()).coerceIn(0f, 1f)

    val isOverGoal: Boolean
        get() = targetMinutes > 0L && todayMinutes > targetMinutes

    /** 额度用完后是否只剩白名单可用（「超额后才限制」模式） */
    val afterBudgetOnlyWhitelist: Boolean
        get() = restrictionMode == RestrictionMode.QUOTA_AFTER_BUDGET

    /** 已授权但今天确实没有任何使用记录 */
    val isEmpty: Boolean
        get() = !loading && error == null && hasUsageAccess && todayMinutes <= 0L && topApps.isEmpty()

    val weekAverageMinutes: Long
        get() = if (weekTotals.isEmpty()) 0L else weekTotals.sumOf { it.minutes } / weekTotals.size
}

/**
 * Dashboard ViewModel：
 * - 刷新时先 [UsageRepository.refresh]（增量采集系统 UsageEvents），再查询，保证展示的是最新真实数据；
 * - 每日目标跟随 DataStore 的 [SettingsRepository.settings] Flow 实时变化；
 * - Agent 状态来自 [AgentRuntime.isConfigured]（suspend，只在 ViewModel 里调用）。
 */
class DashboardViewModel(
    private val usage: UsageRepository,
    private val scoreRepository: ScoreRepository,
    private val behavior: BehaviorRepository,
    private val settings: SettingsRepository,
    private val agentRuntime: AgentRuntime,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        // 使用目标走 DataStore Flow，设置页改动后首页自动跟随
        viewModelScope.launch {
            settings.settings
                .catch { emit(AppSettings.DEFAULT) }
                .collect { appSettings ->
                    val focusActive = appSettings.focusWindowEnabled &&
                        FocusWindow.isActive(
                            Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                            appSettings.focusWindowStart,
                            appSettings.focusWindowEnd,
                        )
                    _state.update {
                        it.copy(
                            targetMinutes = appSettings.dailyTargetMinutes.toLong(),
                            restrictionMode = RestrictionMode.parse(appSettings.restrictionMode),
                            focusWindowActive = focusActive,
                        )
                    }
                }
        }
        refresh()
    }

    /** 刷新：先采集再查询。并发调用会被忽略，避免重复 ingest。 */
    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            try {
                val hasAccess = usage.hasUsageAccess()
                if (hasAccess) {
                    usage.refresh()
                }
                val today = usage.todayTotalMinutes()
                val ranking = usage.todayRanking()
                val week = usage.weekTotals()
                // 评分读取失败不应影响其它卡片
                val todayScore = runCatching { scoreRepository.todayScore() }.getOrNull()
                val anomalies = runCatching { behavior.countToday() }.getOrDefault(0)
                val configured = runCatching { agentRuntime.isConfigured() }.getOrDefault(false)
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        hasUsageAccess = hasAccess,
                        todayMinutes = today,
                        topApps = ranking.filter { app -> app.minutes > 0L }.take(5),
                        distribution = buildShares(ranking, today),
                        weekTotals = week,
                        score = todayScore,
                        anomalyCount = anomalies,
                        agentConfigured = configured,
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = t.message ?: "使用数据加载失败",
                    )
                }
            }
        }
    }

    private fun buildShares(ranking: List<AppUsageSummary>, totalMinutes: Long): List<UsageShare> {
        if (totalMinutes <= 0L) return emptyList()
        val used = ranking.filter { it.minutes > 0L }
        if (used.isEmpty()) return emptyList()
        val total = totalMinutes.toFloat()
        val head = used.take(8)
        val rest = used.drop(8)
        val shares = head.map { app ->
            UsageShare(
                label = app.appName,
                minutes = app.minutes,
                share = (app.minutes / total).coerceIn(0f, 1f),
            )
        }.toMutableList()
        if (rest.isNotEmpty()) {
            val otherMinutes = rest.sumOf { it.minutes }
            shares.add(
                UsageShare(
                    label = "其他 ${rest.size} 个应用",
                    minutes = otherMinutes,
                    share = (otherMinutes / total).coerceIn(0f, 1f),
                ),
            )
        }
        return shares
    }
}
