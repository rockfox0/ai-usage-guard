package com.aid.usageguard.ui.settings

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aid.usageguard.data.Access
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.repo.WhitelistRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.domain.monitor.MonitorValidator
import com.aid.usageguard.domain.quota.FocusWindow
import com.aid.usageguard.restriction.RestrictionMode
import com.aid.usageguard.service.AccessibilityRestorer
import com.aid.usageguard.service.KeepAliveGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val settingsRepo = Container.settings
    private val db = Container.db
    private val usageSource = Container.usageSource

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings.DEFAULT,
    )

    val permissionState = MutableStateFlow(PermissionState())
    val message = MutableStateFlow<String?>(null)

    /** 今日总使用分钟（「每日使用额度」卡展示已用/剩余；null = 尚未读取） */
    val quotaUsedMinutes = MutableStateFlow<Long?>(null)

    /** 白名单每日添加额度（今日已添加 / 剩余；null = 尚未读取） */
    val whitelistQuota = MutableStateFlow<WhitelistRepository.Quota?>(null)

    /** 守护保活状态（v1.8.3） */
    val keepAlive = MutableStateFlow(KeepAliveState())

    /** 我的监控（v1.9 Phase 5：本地引擎每 15 分钟检查的自定义规则） */
    val monitors = MutableStateFlow(MonitorsState())

    /** AI 主动洞察（v1.9 Phase 6：每天最多 1 条） */
    val insights = MutableStateFlow(InsightsState())

    fun refreshPermissions() {
        val context = getApplication<Application>()
        permissionState.value = PermissionState(
            hasUsageAccess = accessSafe(context) { usageSource.hasUsageAccess() },
            accessibilityOn = accessSafe(context) { Access.accessibilityGranted(context) },
            notificationsEnabled = accessSafe(context) { notificationsEnabled(context) },
        )
    }

    /** 读取今日总使用分钟（额度卡用） */
    fun refreshQuotaUsage() {
        viewModelScope.launch {
            val snapshot = runCatching { Container.quota.todayUsage() }.getOrNull()
            quotaUsedMinutes.value = snapshot?.totalMinutes
        }
    }

    /** 读取守护保活相关状态：无障碍 / 自恢复授权 / 电池优化白名单 */
    fun refreshKeepAlive() {
        val context = getApplication<Application>()
        keepAlive.value = KeepAliveState(
            accessibilityOn = accessSafe(context) { AccessibilityRestorer.isEnabled(context) },
            writePermission = accessSafe(context) { AccessibilityRestorer.hasWritePermission(context) },
            ignoringBatteryOptimizations = accessSafe(context) {
                KeepAliveGuide.isIgnoringBatteryOptimizations(context)
            },
        )
    }

    /** 申请忽略电池优化（系统确认框） */
    fun requestIgnoreBatteryOptimizations() {
        val context = getApplication<Application>()
        runCatching { KeepAliveGuide.requestIgnoreBatteryOptimizations(context) }
        refreshKeepAlive()
    }

    /** 打开自启动 / 后台运行设置（vivo 优先，其它厂商回退应用详情） */
    fun openAutoStartSettings() {
        val context = getApplication<Application>()
        runCatching { KeepAliveGuide.openAutoStartSettings(context) }
        refreshKeepAlive()
    }

    /** 打开系统无障碍设置 */
    fun openKeepAliveAccessibilitySettings() {
        val context = getApplication<Application>()
        runCatching { KeepAliveGuide.openAccessibilitySettings(context) }
        refreshKeepAlive()
    }

    /** 授予自恢复权限（WRITE_SECURE_SETTINGS）的一次性 adb 命令 */
    fun adbGrantCommand(): String = KeepAliveGuide.adbGrantCommand()

    /** 加载监控规则与最近触发（全部 runCatching 兜底，DB 未就绪也不崩） */
    fun refreshMonitors() {
        viewModelScope.launch {
            val rules = runCatching { Container.monitors.all() }.getOrDefault(emptyList())
            val triggers = runCatching { Container.monitors.recentTriggers(3) }.getOrDefault(emptyList())
            val ruleUis = withContext(Dispatchers.IO) {
                rules.map { rule ->
                    MonitorRuleUi(
                        id = rule.id,
                        name = rule.name,
                        description = runCatching {
                            MonitorValidator.describe(rule) { pkg -> Container.usageSource.appNameOf(pkg) }
                        }.getOrDefault(rule.name),
                        enabled = rule.enabled,
                    )
                }
            }
            val triggerUis = triggers.take(3).map { trigger ->
                MonitorTriggerUi(
                    dateLabel = trigger.date.takeLast(5),
                    ruleName = trigger.ruleName,
                    value = trigger.value,
                    threshold = trigger.threshold,
                    unit = if (trigger.message.contains("次")) "次" else "分钟",
                )
            }
            monitors.value = MonitorsState(loading = false, rules = ruleUis, triggers = triggerUis)
        }
    }

    /** 启用/停用监控规则（用户主动操作，不走草案） */
    fun setMonitorEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { Container.monitors.setEnabled(id, enabled) }
            refreshMonitors()
        }
    }

    /** 删除监控规则（用户主动操作，不走草案） */
    fun deleteMonitor(id: String) {
        viewModelScope.launch {
            runCatching { Container.monitors.delete(id) }
            message.value = "已删除该监控规则"
            refreshMonitors()
        }
    }

    /** 加载最近 AI 洞察（最多 5 条，runCatching 兜底） */
    fun refreshInsights() {
        viewModelScope.launch {
            val list = runCatching { Container.insights.recent(5) }.getOrDefault(emptyList())
            val items = list.take(5).map { insight ->
                InsightUi(
                    id = insight.id,
                    title = insight.title,
                    dateLabel = insight.date.takeLast(5),
                    detail = insight.detail,
                    suggestion = insight.suggestion,
                )
            }
            insights.value = InsightsState(loading = false, items = items)
        }
    }

    /** 主动分析开关（v1.9 Phase 6） */
    fun setNotifyProactive(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsRepo.setNotifyProactive(enabled) }
            message.value = if (enabled) "已开启主动分析" else "已关闭主动分析：后台不再生成洞察"
            refreshInsights()
        }
    }

    /** AI 操作权限（v1.9.2）：true = 写操作自动执行；false = 每次确认 */
    fun setAutoExecuteWrites(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsRepo.setAutoExecuteWrites(enabled) }
            message.value = if (enabled) {
                "已开启自动执行：AI 的写操作会立即生效"
            } else {
                "已改为每次确认：AI 的写操作需你点确认"
            }
        }
    }

    /** 规划模型（可选，v1.9 Phase 7）：留空表示与默认模型相同 */
    fun saveAiPlannerModel(model: String) {
        viewModelScope.launch {
            runCatching { settingsRepo.setAiPlannerModel(model.trim()) }
            message.value = "规划模型已保存"
        }
    }

    /** 读取白名单添加额度（今日已添加 / 还可添加） */
    fun refreshWhitelistQuota() {
        viewModelScope.launch {
            whitelistQuota.value = runCatching { Container.whitelist.quota() }.getOrNull()
        }
    }

    /** 白名单每日添加上限：1/2/3 个，0 = 不限 */
    fun setWhitelistDailyLimit(limit: Int) = viewModelScope.launch {
        settingsRepo.setWhitelistDailyLimit(limit)
        refreshWhitelistQuota()
        message.value = if (limit <= 0) {
            "白名单每天添加数量不限"
        } else {
            "白名单每天最多添加 $limit 个应用"
        }
    }

    /** 专注时段：时段内只能用白名单应用（与额度无关） */
    fun setFocusWindow(enabled: Boolean, startHour: Int, endHour: Int) = viewModelScope.launch {
        settingsRepo.setFocusWindow(enabled, startHour, endHour)
        message.value = if (enabled) {
            "专注时段已开启：${FocusWindow.label(startHour, endHour)} 只能用白名单应用"
        } else {
            "专注时段已关闭"
        }
    }

    fun saveAiConfig(baseUrl: String, apiKey: String, model: String, format: String) {
        viewModelScope.launch {
            settingsRepo.setAiConfig(baseUrl, apiKey, model, format)
            message.value = "AI 接口配置已保存"
        }
    }

    fun setDailyTarget(minutes: Int) = viewModelScope.launch { settingsRepo.setDailyTarget(minutes) }

    fun setQuietHours(start: Int, end: Int) = viewModelScope.launch { settingsRepo.setQuietHours(start, end) }

    fun setNotificationEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepo.setNotificationEnabled(enabled) }

    fun setAiAnalysisEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAiAnalysisEnabled(enabled) }

    fun setReminderStyle(style: String) = viewModelScope.launch { settingsRepo.setReminderStyle(style) }

    fun setThemeMode(mode: String) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }

    fun setAiPersona(nickname: String, tone: String, focus: Set<String>, customPrompt: String) =
        viewModelScope.launch {
            settingsRepo.setAiPersona(nickname, tone, focus, customPrompt)
            message.value = "AI 个性化已保存"
        }

    fun setRealtimeNudgeEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setRealtimeNudgeEnabled(enabled)
    }

    fun setNudgeIntervalMinutes(minutes: Int) = viewModelScope.launch {
        settingsRepo.setNudgeIntervalMinutes(minutes)
    }

    fun setNudgeUseAi(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setNudgeUseAi(enabled)
    }

    fun setRestrictByDefault(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setRestrictByDefault(enabled)
    }

    /** v1.8.1：限制模式三选一 */
    fun setRestrictionMode(mode: RestrictionMode) = viewModelScope.launch {
        settingsRepo.setRestrictionMode(mode)
        message.value = "已切换为「${mode.label}」：${mode.description}"
    }

    /** v1.8：超出每日额度后是否拦截非白名单应用 */
    fun setDailyBudgetEnforced(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setDailyBudgetEnforced(enabled)
        message.value = if (enabled) {
            "已开启：超出每日额度后拦截非白名单应用"
        } else {
            "已关闭：全局额度只做提醒，不拦截应用"
        }
    }

    fun setNotifyDailyReminder(enabled: Boolean) = viewModelScope.launch { settingsRepo.setNotifyDailyReminder(enabled) }

    fun setNotifyGoalExceeded(enabled: Boolean) = viewModelScope.launch { settingsRepo.setNotifyGoalExceeded(enabled) }

    fun setNotifyAnomaly(enabled: Boolean) = viewModelScope.launch { settingsRepo.setNotifyAnomaly(enabled) }

    fun setNotifyDailySummary(enabled: Boolean) = viewModelScope.launch { settingsRepo.setNotifyDailySummary(enabled) }

    fun setNotifyWeeklySummary(enabled: Boolean) = viewModelScope.launch { settingsRepo.setNotifyWeeklySummary(enabled) }

    fun clearUsageHistory() = viewModelScope.launch {
        runCatching {
            db.usageSessionDao().clear()
            db.appUsageRecordDao().clear()
        }
        message.value = "已清空使用记录"
    }

    fun clearBehaviorEvents() = viewModelScope.launch {
        runCatching { db.behaviorEventDao().clear() }
        message.value = "已清空行为事件"
    }

    fun clearReports() = viewModelScope.launch {
        runCatching { Container.reports.deleteAll() }
        message.value = "已清空 AI 报告"
    }

    fun dismissMessage() {
        message.value = null
    }

    fun openUsageAccessSettings(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun notificationsEnabled(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    private inline fun accessSafe(context: Context, block: () -> Boolean): Boolean =
        runCatching { block() }.getOrDefault(false)
}

data class PermissionState(
    val hasUsageAccess: Boolean = false,
    val accessibilityOn: Boolean = false,
    val notificationsEnabled: Boolean = false,
)

/** AI 洞察展示模型 */
data class InsightUi(
    val id: String,
    val title: String,
    /** 形如 11-06 */
    val dateLabel: String,
    val detail: String,
    val suggestion: String,
)

data class InsightsState(
    val loading: Boolean = true,
    val items: List<InsightUi> = emptyList(),
)

/** 监控规则展示模型（name + describe 文案 + 启用状态） */
data class MonitorRuleUi(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
)

/** 最近一次触发（dateLabel 形如 11-06） */
data class MonitorTriggerUi(
    val dateLabel: String,
    val ruleName: String,
    val value: Long,
    val threshold: Long,
    val unit: String,
)

data class MonitorsState(
    val loading: Boolean = true,
    val rules: List<MonitorRuleUi> = emptyList(),
    val triggers: List<MonitorTriggerUi> = emptyList(),
)

/** 守护保活状态（v1.8.3）：无障碍是否在跑 / 是否可自恢复 / 是否已被系统加白 */
data class KeepAliveState(
    val accessibilityOn: Boolean = false,
    val writePermission: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
)
