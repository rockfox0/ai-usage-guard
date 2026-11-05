package com.aid.usageguard.ui.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.model.AppDetailModel
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

data class AppDetailUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val detail: AppDetailModel? = null,
    val error: String? = null,
    /** 当前限制模式：true=默认限制所有应用（白名单除外） */
    val restrictByDefault: Boolean = true,
    val restricted: Boolean = false,
    val byDefault: Boolean = false,
    val whitelisted: Boolean = false,
    val systemProtected: Boolean = false,
) {
    /** 开关状态：默认限制模式=是否在白名单；仅限制指定模式=是否被手动限制；系统保护恒 true 且置灰 */
    val checked: Boolean
        get() = when {
            systemProtected -> true
            restrictByDefault -> whitelisted
            else -> restricted
        }

    val switchLabel: String
        get() = if (restrictByDefault) "允许" else "暂停"

    /** 状态文案：系统保护 / 白名单允许 / 默认受限 / 手动限制 / 未限制 */
    val statusText: String
        get() = when {
            systemProtected -> "系统保护"
            whitelisted -> "白名单允许"
            byDefault -> "默认受限"
            restricted -> "手动限制"
            else -> "未限制"
        }

    /** 状态下方的一句解释 */
    val statusDetail: String
        get() = when {
            systemProtected -> "系统关键应用，永不被限制"
            whitelisted -> "白名单应用：始终允许使用"
            byDefault -> "默认限制模式下受限，打开开关可加入白名单"
            restricted -> "已手动限制：打开该应用会被立即拦截"
            else -> "未限制：打开右侧开关可暂停这个应用"
        }
}

/**
 * App 详情 ViewModel。
 * - 使用数据来自 [UsageRepository.appDetail]（今日 / 近 7 天 / 日均 / 启动次数 / 最近使用 / 7 天分布 / 今日使用区间）；
 * - 限制状态由 [RestrictionPolicy] 合成：白名单（[SettingsRepository.setAllowed]）+ 默认限制模式
 *   （[AppSettings.restrictByDefault]）+ 显式受限（[AppRestrictionController.observeBlocked]）+ 系统保护。
 */
class AppDetailViewModel(
    private val packageName: String,
    private val usageSource: UsageEventSource,
    private val usage: UsageRepository,
    private val settings: SettingsRepository,
    private val restrictions: AppRestrictionController,
) : ViewModel() {

    private val _state = MutableStateFlow(AppDetailUiState())
    val state: StateFlow<AppDetailUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

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
                    publishRestriction()
                }
        }
        // 显式受限集合：Room Flow
        viewModelScope.launch {
            restrictions.observeBlocked().collect { blocked ->
                explicitBlocked = blocked
                publishRestriction()
            }
        }
        load(ingest = false)
    }

    /** [ingest] 为 true 时先做一次增量采集（手动刷新） */
    fun load(ingest: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            try {
                if (ingest && usage.hasUsageAccess()) {
                    usage.refresh()
                }
                if (protectedPackages.isEmpty()) {
                    protectedPackages = withContext(Dispatchers.IO) {
                        runCatching { usageSource.launcherPackages() }.getOrDefault(emptySet())
                    }
                }
                val detail = usage.appDetail(packageName)
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        detail = detail,
                        error = null,
                    )
                }
                publishRestriction()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = t.message ?: "应用详情加载失败",
                    )
                }
            }
        }
    }

    fun refresh() = load(ingest = true)

    /**
     * 开关动作，语义随模式变化：
     * - 默认限制模式：checked = 加入/移出白名单 → [SettingsRepository.setAllowed]
     * - 仅限制指定模式：checked = 手动限制/取消限制 → [AppRestrictionController.block] / [AppRestrictionController.unblock]
     * 系统关键应用直接忽略（UI 也已置灰）。
     */
    fun setSwitch(checked: Boolean) {
        if (RuleValidator.isProtected(packageName, protectedPackages)) return
        if (restrictByDefault) {
            allowedPackages = if (checked) allowedPackages + packageName else allowedPackages - packageName
            publishRestriction()
            viewModelScope.launch {
                runCatching { settings.setAllowed(packageName, checked) }.onFailure {
                    allowedPackages = if (checked) {
                        allowedPackages - packageName
                    } else {
                        allowedPackages + packageName
                    }
                    publishRestriction()
                }
            }
        } else {
            explicitBlocked = if (checked) explicitBlocked + packageName else explicitBlocked - packageName
            publishRestriction()
            viewModelScope.launch {
                runCatching {
                    if (checked) restrictions.block(packageName) else restrictions.unblock(packageName)
                }.onFailure {
                    explicitBlocked = if (checked) {
                        explicitBlocked - packageName
                    } else {
                        explicitBlocked + packageName
                    }
                    publishRestriction()
                }
            }
        }
    }

    /** 用当前策略重算限制状态 */
    private fun publishRestriction() {
        val policyState = RestrictionPolicy.state(
            packageName = packageName,
            allowedPackages = allowedPackages,
            explicitBlocked = explicitBlocked,
            restrictByDefault = restrictByDefault,
            protectedPackages = protectedPackages,
        )
        _state.update {
            it.copy(
                restrictByDefault = restrictByDefault,
                restricted = policyState.restricted,
                byDefault = policyState.byDefault,
                whitelisted = policyState.whitelisted,
                systemProtected = policyState.systemProtected,
            )
        }
    }
}
