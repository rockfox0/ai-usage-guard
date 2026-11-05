package com.aid.usageguard.ui.settings

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aid.usageguard.data.Access
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun refreshPermissions() {
        val context = getApplication<Application>()
        permissionState.value = PermissionState(
            hasUsageAccess = accessSafe(context) { usageSource.hasUsageAccess() },
            accessibilityOn = accessSafe(context) { Access.accessibilityGranted(context) },
            notificationsEnabled = accessSafe(context) { notificationsEnabled(context) },
        )
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
