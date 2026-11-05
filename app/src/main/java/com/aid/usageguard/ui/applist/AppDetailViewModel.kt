package com.aid.usageguard.ui.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.QuotaRepository
import com.aid.usageguard.data.repo.WhitelistRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.model.AppDetailModel
import com.aid.usageguard.domain.quota.FocusWindow
import com.aid.usageguard.domain.quota.QuotaPolicy
import com.aid.usageguard.restriction.RestrictionMode
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
import java.util.Calendar

data class AppDetailUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val detail: AppDetailModel? = null,
    val error: String? = null,
    /** 当前限制模式（v1.8.1：额度内自由 / 始终限制 / 只限制指定） */
    val restrictionMode: RestrictionMode = RestrictionMode.QUOTA_AFTER_BUDGET,
    val restricted: Boolean = false,
    val byDefault: Boolean = false,
    val whitelisted: Boolean = false,
    val systemProtected: Boolean = false,
    /** 单应用每日限额（null = 不限时） */
    val quotaLimitMinutes: Int? = null,
    /** 该应用今日已用分钟 */
    val quotaUsedMinutes: Long = 0L,
    /** 今日剩余分钟（未设限额为 null） */
    val quotaRemainingMinutes: Long? = null,
    val quotaBlocked: Boolean = false,
    /** 超限说明（来自 QuotaPolicy.QuotaState.detail） */
    val quotaDetail: String = "",
    val quotaSaving: Boolean = false,
    /** 专注时段当前是否生效（生效时非白名单应用不可用） */
    val focusWindowActive: Boolean = false,
    /** 专注时段文案，例如 22:00-07:00 */
    val focusWindowLabel: String = "",
    /** 白名单添加结果提示（成功 / 被每日上限拒绝） */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
) {
    /** 开关状态：默认限制模式=是否在白名单；仅限制指定模式=是否被手动限制；系统保护恒 true 且置灰 */
    val checked: Boolean
        get() = when {
            systemProtected -> true
            restrictionMode == RestrictionMode.MANUAL_ONLY -> restricted
            else -> whitelisted
        }

    val switchLabel: String
        get() = if (restrictionMode == RestrictionMode.MANUAL_ONLY) "暂停" else "允许"

    /** 状态文案：系统保护 / 白名单允许 / 默认受限 / 手动限制 / 额度内可用 / 未限制 */
    val statusText: String
        get() = when {
            systemProtected -> "系统保护"
            whitelisted -> "白名单允许"
            byDefault -> "默认受限"
            restricted -> "手动限制"
            restrictionMode == RestrictionMode.QUOTA_AFTER_BUDGET -> "额度内可用"
            else -> "未限制"
        }

    /** 「30 分钟」/「不限时」 */
    val quotaLimitLabel: String
        get() = quotaLimitMinutes?.let { "$it 分钟" } ?: "不限时"

    /** 「今天已用 12 分钟 · 今日剩余 18 分钟」 */
    val quotaUsageLabel: String
        get() {
            val used = "今天已用 $quotaUsedMinutes 分钟"
            val remaining = quotaRemainingMinutes?.let { "今日剩余 $it 分钟" } ?: "未设限额，不会按应用时长拦截"
            return "$used · $remaining"
        }

    /** 状态下方的一句解释（专注时段生效时优先说明） */
    val statusDetail: String
        get() {
            if (focusWindowActive && !systemProtected && !whitelisted) {
                return "专注时段生效中（$focusWindowLabel，仅白名单可用）：现在打开会被拦截"
            }
            if (focusWindowActive && whitelisted) {
                return "白名单应用：专注时段内也可以正常使用"
            }
            return when {
                systemProtected -> "系统关键应用，永不被限制"
                whitelisted -> "白名单应用：始终允许使用"
                byDefault -> "默认限制模式下受限，打开开关可加入白名单"
                restricted -> "已手动限制：打开该应用会被立即拦截"
                restrictionMode == RestrictionMode.QUOTA_AFTER_BUDGET ->
                    "额度内可正常使用；今日用满额度后，只有白名单应用还能打开"
                else -> "未限制：打开右侧开关可暂停这个应用"
            }
        }
}

/**
 * App 详情 ViewModel。
 * - 使用数据来自 [UsageRepository.appDetail]（今日 / 近 7 天 / 日均 / 启动次数 / 最近使用 / 7 天分布 / 今日使用区间）；
 * - 限制状态由 [RestrictionPolicy] 合成：白名单（[WhitelistRepository]）+ 限制模式
 *   （[RestrictionMode]：额度内自由 / 始终限制非白名单 / 只限制指定）+ 显式受限
 *   （[AppRestrictionController.observeBlocked]）+ 系统保护。
 */
class AppDetailViewModel(
    private val packageName: String,
    private val usageSource: UsageEventSource,
    private val usage: UsageRepository,
    private val settings: SettingsRepository,
    private val restrictions: AppRestrictionController,
    private val quota: QuotaRepository,
    private val whitelist: WhitelistRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AppDetailUiState())
    val state: StateFlow<AppDetailUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private var restrictionMode: RestrictionMode = RestrictionMode.QUOTA_AFTER_BUDGET
    private var allowedPackages: Set<String> = emptySet()
    private var explicitBlocked: Set<String> = emptySet()
    private var protectedPackages: Set<String> = emptySet()

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
                    allowedPackages = appSettings.allowedPackages
                    focusWindowEnabled = appSettings.focusWindowEnabled
                    focusWindowStart = appSettings.focusWindowStart
                    focusWindowEnd = appSettings.focusWindowEnd
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
                refreshQuota()
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
     * 保存单应用每日限额（[minutes] <= 0 表示清除限额）。
     * 限额优先级高于白名单：设置后即使应用在白名单里，超了当天也会被拦截。
     */
    fun setQuota(minutes: Int) {
        if (_state.value.quotaSaving) return
        _state.update { it.copy(quotaSaving = true) }
        viewModelScope.launch {
            runCatching { quota.setLimit(packageName, minutes) }
            refreshQuota()
            _state.update { it.copy(quotaSaving = false) }
        }
    }

    /** 重新计算该应用的额度状态（今日已用 / 剩余 / 是否已被拦截） */
    private suspend fun refreshQuota() {
        val quotaState = runCatching {
            quota.status(packageName, protectedPackages = protectedPackages)
        }.getOrNull()
        _state.update {
            it.copy(
                quotaLimitMinutes = quotaState?.appLimitMinutes,
                quotaUsedMinutes = quotaState?.appUsedMinutes ?: 0L,
                quotaRemainingMinutes = QuotaPolicy.appRemainingMinutes(
                    quotaState?.appLimitMinutes,
                    quotaState?.appUsedMinutes ?: 0L,
                ),
                quotaBlocked = quotaState?.blocked == true,
                quotaDetail = quotaState?.detail.orEmpty(),
            )
        }
    }

    /**
     * 开关动作，语义随模式变化：
     * - 额度/始终限制模式：checked = 加入/移出白名单 → [WhitelistRepository.add] / [WhitelistRepository.remove]
     *   （加入受「每天最多添加 N 个」限制，被拒时提示并回滚；移出不计次数）
     * - 仅限制指定模式：checked = 手动限制/取消限制 → [AppRestrictionController.block] / [AppRestrictionController.unblock]
     * 系统关键应用直接忽略（UI 也已置灰）。
     */
    fun setSwitch(checked: Boolean) {
        if (RuleValidator.isProtected(packageName, protectedPackages)) return
        if (switchUsesWhitelist) {
            if (!checked) {
                // 移出白名单：不消耗每日添加额度
                allowedPackages = allowedPackages - packageName
                publishRestriction()
                viewModelScope.launch {
                    runCatching { whitelist.remove(packageName) }.onFailure {
                        allowedPackages = allowedPackages + packageName
                        publishRestriction()
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
                        publishRestriction()
                        _state.update {
                            it.copy(
                                notice = "已加入白名单：${result.quota.describe()}",
                                noticeIsError = false,
                            )
                        }
                    }

                    WhitelistRepository.AddResult.AlreadyAllowed -> {
                        allowedPackages = allowedPackages + packageName
                        publishRestriction()
                    }

                    is WhitelistRepository.AddResult.Rejected -> {
                        // 超出每日添加次数：开关回滚为未选中并提示原因
                        allowedPackages = allowedPackages - packageName
                        publishRestriction()
                        _state.update {
                            it.copy(notice = result.reason, noticeIsError = true)
                        }
                    }

                    null -> Unit
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

    fun dismissNotice() {
        _state.update { it.copy(notice = null, noticeIsError = false) }
    }

    /** 用当前策略重算限制状态 */
    private fun publishRestriction() {
        val focusActive = focusWindowEnabled &&
            FocusWindow.isActive(
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                focusWindowStart,
                focusWindowEnd,
            )
        val policyState = RestrictionPolicy.state(
            packageName = packageName,
            allowedPackages = allowedPackages,
            explicitBlocked = explicitBlocked,
            restrictByDefault = policyRestrictByDefault,
            protectedPackages = protectedPackages,
        )
        _state.update {
            it.copy(
                restrictionMode = restrictionMode,
                focusWindowActive = focusActive,
                focusWindowLabel = FocusWindow.label(focusWindowStart, focusWindowEnd),
                restricted = policyState.restricted,
                byDefault = policyState.byDefault,
                whitelisted = policyState.whitelisted,
                systemProtected = policyState.systemProtected,
            )
        }
    }
}
