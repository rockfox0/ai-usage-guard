package com.aid.usageguard.ui.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.restriction.AppRestrictionController
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
) {
    /** 行副标题里的状态文案 */
    val statusText: String
        get() = when {
            systemProtected -> "系统保护"
            whitelisted -> "白名单允许"
            byDefault -> "默认受限"
            restricted -> "手动限制"
            else -> "未限制"
        }
}

data class AppListUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val query: String = "",
    /** 全部可启动应用，按今日分钟数降序 */
    val apps: List<AppUsageItem> = emptyList(),
    /** 当前限制模式：true=默认限制所有应用（白名单除外） */
    val restrictByDefault: Boolean = true,
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

    /** 头部文案：随限制模式切换 */
    val modeText: String
        get() = if (restrictByDefault) {
            "默认限制所有应用 · 已允许 $allowedCount 个"
        } else {
            "仅限制指定应用 · 已受限 $restrictedCount 个"
        }

    /** 开关旁边的动作文案 */
    val switchLabel: String
        get() = if (restrictByDefault) "允许" else "暂停"
}

/**
 * App 列表 ViewModel：
 * - 应用清单来自 [UsageEventSource.launcherVisibleApps]，今日分钟数来自 [UsageRepository.todayRanking]；
 * - 限制状态由 [RestrictionPolicy] 合成：白名单（[SettingsRepository.setAllowed]）+ 默认限制开关
 *   （[AppSettings.restrictByDefault]）+ 显式受限（[AppRestrictionController.observeBlocked]）+ 系统保护；
 * - 白名单与限制模式订阅 [SettingsRepository.settings] 这个 DataStore Flow，改动后列表实时刷新。
 */
class AppListViewModel(
    private val usageSource: UsageEventSource,
    private val usage: UsageRepository,
    private val settings: SettingsRepository,
    private val restrictions: AppRestrictionController,
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
    private var restrictByDefault: Boolean = AppSettings.DEFAULT.restrictByDefault
    private var allowedPackages: Set<String> = emptySet()
    private var explicitBlocked: Set<String> = emptySet()
    private var protectedPackages: Set<String> = emptySet()

    init {
        // 限制模式 + 白名单：DataStore Flow
        viewModelScope.launch {
            settings.settings
                .catch { emit(AppSettings.DEFAULT) }
                .collect { appSettings ->
                    restrictByDefault = appSettings.restrictByDefault
                    allowedPackages = appSettings.allowedPackages
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

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    /**
     * 开关动作，语义随模式变化：
     * - 默认限制模式：checked = 加入/移出白名单 → [SettingsRepository.setAllowed]
     * - 仅限制指定模式：checked = 手动限制/取消限制 → [AppRestrictionController.block] / [AppRestrictionController.unblock]
     * 系统关键应用直接忽略（UI 也已置灰）。
     */
    fun setSwitch(packageName: String, checked: Boolean) {
        if (RuleValidator.isProtected(packageName, protectedPackages)) return
        if (restrictByDefault) {
            allowedPackages = if (checked) allowedPackages + packageName else allowedPackages - packageName
            publishApps()
            viewModelScope.launch {
                runCatching { settings.setAllowed(packageName, checked) }.onFailure {
                    // 失败回滚；最终仍以 DataStore Flow 的值为准
                    allowedPackages = if (checked) {
                        allowedPackages - packageName
                    } else {
                        allowedPackages + packageName
                    }
                    publishApps()
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
        val items = rawApps.map { raw ->
            val policyState = RestrictionPolicy.state(
                packageName = raw.packageName,
                allowedPackages = allowedPackages,
                explicitBlocked = explicitBlocked,
                restrictByDefault = restrictByDefault,
                protectedPackages = protectedPackages,
            )
            val checked = when {
                policyState.systemProtected -> true
                restrictByDefault -> policyState.whitelisted
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
            )
        }
        _state.update {
            it.copy(
                apps = items,
                restrictByDefault = restrictByDefault,
                allowedCount = items.count { item -> item.whitelisted },
                restrictedCount = items.count { item -> item.restricted },
            )
        }
    }
}
