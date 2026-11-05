package com.aid.usageguard.ui.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.QuotaRepository
import com.aid.usageguard.data.repo.WhitelistRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.restriction.AppRestrictionController
import com.aid.usageguard.domain.quota.FocusWindow
import com.aid.usageguard.domain.quota.QuotaPolicy
import com.aid.usageguard.restriction.RestrictionMode
import com.aid.usageguard.restriction.RestrictionPolicy
import com.aid.usageguard.restriction.RuleValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 列表里的一行：可启动应用 + 今日使用分钟数 + 限制策略状态。
 * [checked] 是开关的当前状态，语义随限制模式变化（见 [AppListUiState.switchLabel]）。
 */
data class AppUsageItem(
    val packageName: String,
    val appName: String,
    val minutes: Long,
    val restricted: Boolean,
    val byDefault: Boolean,
    val whitelisted: Boolean,
    val systemProtected: Boolean,
    /** 默认限制模式：checked=在白名单；仅限制指定模式：checked=被手动限制；系统保护恒 true 且置灰 */
    val checked: Boolean,
    /** 单应用每日限额分钟（null = 未设置） */
    val dailyLimitMinutes: Int? = null,
    /** 是否处于「超额后才限制」模式（额度内不受限，行文案用「额度内可用」） */
    val quotaMode: Boolean = false,
    /** 专注时段当前是否生效（生效时非白名单应用不可用） */
    val focusActive: Boolean = false,
) {
    /** 行副标题里的状态文案 */
    val statusText: String
        get() = when {
            systemProtected -> "系统保护"
            whitelisted -> "白名单允许"
            byDefault -> "默认受限"
            restricted -> "手动限制"
            focusActive -> "专注时段不可用"
            quotaMode -> "额度内可用"
            else -> "未限制"
        }
}

data class AppListUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val query: String = "",
    /** 全部可启动应用，按今日分钟数降序 */
    val apps: List<AppUsageItem> = emptyList(),
    /** 当前限制模式（v1.8.1：额度内自由 / 始终限制 / 只限制指定） */
    val restrictionMode: RestrictionMode = RestrictionMode.QUOTA_AFTER_BUDGET,
    /** 今日剩余额度分钟（额度模式文案用） */
    val budgetRemainingMinutes: Long = 0L,
    /** 专注时段当前是否生效 */
    val focusWindowActive: Boolean = false,
    /** 专注时段文案（例如 22:00-07:00） */
    val focusWindowLabel: String = "",
    /** 白名单添加被拒/成功等一次性提示 */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    val allowedCount: Int = 0,
    val restrictedCount: Int = 0,
    val hasUsageAccess: Boolean = false,
    val error: String? = null,
) {
    val filtered: List<AppUsageItem>
        get() = if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }

    val usedCount: Int
        get() = apps.count { it.minutes > 0L }

    /** 头部文案：随限制模式切换；专注时段生效时追加提示 */
    val modeText: String
        get() {
            val base = when (restrictionMode) {
                RestrictionMode.QUOTA_AFTER_BUDGET ->
                    "额度内自由使用 · 超额后仅白名单可用（今日剩余 $budgetRemainingMinutes 分钟）"

                RestrictionMode.ALWAYS_NON_WHITELIST ->
                    "默认限制所有应用 · 已允许 $allowedCount 个"

                RestrictionMode.MANUAL_ONLY ->
                    "只限制手动指定的应用 · 已允许 $allowedCount 个"
            }
            return if (focusWindowActive) {
                "$base · 专注时段生效中（仅白名单可用）"
            } else {
                base
            }
        }

    /** 开关旁边的动作文案：额度/始终限制模式下是「允许」，只限制指定模式下是「暂停」 */
    val switchLabel: String
        get() = when (restrictionMode) {
            RestrictionMode.MANUAL_ONLY -> "暂停"
            else -> "允许"
        }
}

/**
 * App 列表 ViewModel：
 * - 应用清单来自 [UsageEventSource.launcherVisibleApps]，今日分钟数来自 [UsageRepository.todayRanking]；
 * - 限制状态由 [RestrictionPolicy] 合成：白名单（[WhitelistRepository]）+ 限制模式
 *   （[RestrictionMode]）+ 显式受限（[AppRestrictionController.observeBlocked]）+ 系统保护；
 * - 白名单与限制模式订阅 [SettingsRepository.settings] 这个 DataStore Flow，改动后列表实时刷新。
 */
class AppListViewModel(
    private val usageSource: UsageEventSource,
    private val usage: UsageRepository,
    private val settings: SettingsRepository,
    private val restrictions: AppRestrictionController,
    private val quota: QuotaRepository,
    private val whitelist: WhitelistRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AppListUiState())
    val state: StateFlow<AppListUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private data class RawApp(
        val packageName: String,
        val appName: String,
        val minutes: Long,
    )

    private var rawApps: List<RawApp> = emptyList()
    private var restrictionMode: RestrictionMode = RestrictionMode.QUOTA_AFTER_BUDGET
    private var dailyTargetMinutes: Int = AppSettings.DEFAULT.dailyTargetMinutes
    private var usedTodayMinutes: Long = 0L
    private var allowedPackages: Set<String> = emptySet()
    private var explicitBlocked: Set<String> = emptySet()
    private var protectedPackages: Set<String> = emptySet()
    /** 包名 → 每日限额分钟（一次取回，避免逐行查询） */
    private var limitByPackage: Map<String, Int> = emptyMap()

    // 专注时段（生效时只有白名单应用可用）
    private var focusWindowEnabled: Boolean = false
    private var focusWindowStart: Int = 22
    private var focusWindowEnd: Int = 7

    /** 只有「始终限制非白名单」模式才用旧策略语义（非白名单默认受限） */
    private val policyRestrictByDefault: Boolean
        get() = restrictionMode == RestrictionMode.ALWAYS_NON_WHITELIST

    /** 额度/始终限制模式下开关表示「加入白名单」；只限制指定模式下表示「手动限制」 */
    private val switchUsesWhitelist: Boolean
        get() = restrictionMode != RestrictionMode.MANUAL_ONLY

    init {
        // 限制模式 + 白名单：DataStore Flow
        viewModelScope.launch {
            settings.settings
                .catch { emit(AppSettings.DEFAULT) }
                .collect { appSettings ->
                    restrictionMode = RestrictionMode.parse(appSettings.restrictionMode)
                    dailyTargetMinutes = appSettings.dailyTargetMinutes
                    allowedPackages = appSettings.allowedPackages
                    focusWindowEnabled = appSettings.focusWindowEnabled
                    focusWindowStart = appSettings.focusWindowStart
                    focusWindowEnd = appSettings.focusWindowEnd
                    publishApps()
                }
        }
        // 显式受限集合：Room Flow
        viewModelScope.launch {
            restrictions.observeBlocked().collect { blocked ->
                explicitBlocked = blocked
                publishApps()
            }
        }
        load(ingest = true)
    }

    /** 加载应用清单与今日分钟数；[ingest] 为 true 时先做一次增量采集 */
    fun load(ingest: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            try {
                val hasAccess = usage.hasUsageAccess()
                if (ingest && hasAccess) {
                    usage.refresh()
                }
                val todayMinutes = usage.todayRanking().associate { it.packageName to it.minutes }
                limitByPackage = runCatching { quota.limits() }.getOrDefault(emptyMap())
                usedTodayMinutes = runCatching { quota.todayUsage().totalMinutes }.getOrDefault(0L)
                val snapshot = withContext(Dispatchers.IO) {
                    val protected = runCatching { usageSource.launcherPackages() }.getOrDefault(emptySet())
                    val launcher = runCatching { usageSource.launcherVisibleApps() }.getOrDefault(emptyList())
                    protected to launcher
                }
                protectedPackages = snapshot.first
                rawApps = snapshot.second
                    .map { (packageName, appName) ->
                        RawApp(packageName, appName, todayMinutes[packageName] ?: 0L)
                    }
                    .sortedWith(
                        compareByDescending<RawApp> { it.minutes }
                            .thenBy { it.appName.lowercase() },
                    )
                publishApps()
                _state.update {
                    it.copy(loading = false, refreshing = false, hasUsageAccess = hasAccess, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = t.message ?: "应用列表加载失败",
                    )
                }
            }
        }
    }

    fun refresh() = load(ingest = true)

    fun dismissNotice() {
        _state.update { it.copy(notice = null, noticeIsError = false) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    /**
     * 开关动作，语义随模式变化：
     * - 额度/始终限制模式：checked = 加入/移出白名单 → [WhitelistRepository.add] / [WhitelistRepository.remove]
     *   （加入受「每天最多添加 N 个」限制，被拒时提示并回滚；移出不计次数）
     * - 仅限制指定模式：checked = 手动限制/取消限制 → [AppRestrictionController.block] / [AppRestrictionController.unblock]
     * 系统关键应用直接忽略（UI 也已置灰）。
     */
    fun setSwitch(packageName: String, checked: Boolean) {
        if (RuleValidator.isProtected(packageName, protectedPackages)) return
        if (switchUsesWhitelist) {
            if (!checked) {
                // 移出白名单：不消耗每日添加额度
                allowedPackages = allowedPackages - packageName
                publishApps()
                viewModelScope.launch {
                    runCatching { whitelist.remove(packageName) }.onFailure {
                        allowedPackages = allowedPackages + packageName
                        publishApps()
                    }
                }
                return
            }
            // 加入白名单必须走 WhitelistRepository：受「每天最多添加 N 个」限制
            viewModelScope.launch {
                _state.update { it.copy(notice = null) }
                when (val result = runCatching { whitelist.add(packageName) }.getOrNull()) {
                    is WhitelistRepository.AddResult.Added -> {
                        allowedPackages = allowedPackages + packageName
                        publishApps()
                        _state.update {
                            it.copy(
                                notice = "已加入白名单：${result.quota.describe()}",
                                noticeIsError = false,
                            )
                        }
                    }

                    WhitelistRepository.AddResult.AlreadyAllowed -> {
                        allowedPackages = allowedPackages + packageName
                        publishApps()
                    }

                    is WhitelistRepository.AddResult.Rejected -> {
                        // 超出每日添加次数：开关回滚为未选中并提示原因
                        allowedPackages = allowedPackages - packageName
                        publishApps()
                        _state.update {
                            it.copy(notice = result.reason, noticeIsError = true)
                        }
                    }

                    null -> Unit
                }
            }
        } else {
            explicitBlocked = if (checked) explicitBlocked + packageName else explicitBlocked - packageName
            publishApps()
            viewModelScope.launch {
                runCatching {
                    if (checked) restrictions.block(packageName) else restrictions.unblock(packageName)
                }.onFailure {
                    explicitBlocked = if (checked) {
                        explicitBlocked - packageName
                    } else {
                        explicitBlocked + packageName
                    }
                    publishApps()
                }
            }
        }
    }

    /** 用当前策略重算每一行的状态（不改动已加载的原始清单） */
    private fun publishApps() {
        val focusActive = focusWindowEnabled &&
            FocusWindow.isActive(
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                focusWindowStart,
                focusWindowEnd,
            )
        val items = rawApps.map { raw ->
            val policyState = RestrictionPolicy.state(
                packageName = raw.packageName,
                allowedPackages = allowedPackages,
                explicitBlocked = explicitBlocked,
                restrictByDefault = policyRestrictByDefault,
                protectedPackages = protectedPackages,
            )
            val checked = when {
                policyState.systemProtected -> true
                switchUsesWhitelist -> policyState.whitelisted
                else -> policyState.restricted
            }
            AppUsageItem(
                packageName = raw.packageName,
                appName = raw.appName,
                minutes = raw.minutes,
                restricted = policyState.restricted,
                byDefault = policyState.byDefault,
                whitelisted = policyState.whitelisted,
                systemProtected = policyState.systemProtected,
                checked = checked,
                dailyLimitMinutes = limitByPackage[raw.packageName],
                quotaMode = restrictionMode == RestrictionMode.QUOTA_AFTER_BUDGET,
                focusActive = focusActive && !policyState.whitelisted && !policyState.systemProtected,
            )
        }
        _state.update {
            it.copy(
                apps = items,
                restrictionMode = restrictionMode,
                budgetRemainingMinutes = QuotaPolicy.remainingMinutes(dailyTargetMinutes, usedTodayMinutes),
                focusWindowActive = focusActive,
                focusWindowLabel = FocusWindow.label(focusWindowStart, focusWindowEnd),
                allowedCount = items.count { item -> item.whitelisted },
                restrictedCount = items.count { item -> item.restricted },
            )
        }
    }
}
