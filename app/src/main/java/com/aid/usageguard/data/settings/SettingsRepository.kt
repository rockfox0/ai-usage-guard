package com.aid.usageguard.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/** 用户偏好（DataStore 持久化） */
data class AppSettings(
    val dailyTargetMinutes: Int = 120,
    val quietHoursStart: Int = 23,
    val quietHoursEnd: Int = 7,
    val notificationEnabled: Boolean = true,
    val aiAnalysisEnabled: Boolean = true,
    val reminderStyle: String = STYLE_GENTLE,
    val aiBaseUrl: String = DEFAULT_BASE_URL,
    val aiApiKey: String = "",
    val aiModel: String = DEFAULT_MODEL,
    val aiFormat: String = FORMAT_OPENAI,
    val notifyDailyReminder: Boolean = true,
    val notifyGoalExceeded: Boolean = true,
    val notifyAnomaly: Boolean = true,
    val notifyDailySummary: Boolean = true,
    val notifyWeeklySummary: Boolean = true,
    val themeMode: String = THEME_SYSTEM,
    /** 限制模式：true=默认限制所有应用（白名单除外），false=只限制手动指定的应用 */
    val restrictByDefault: Boolean = true,
    /** 白名单：始终允许使用、永不被限制的应用 */
    val allowedPackages: Set<String> = emptySet(),

    // ---- AI 个性化 ----
    /** AI 对用户的称呼，空则用「你」 */
    val aiNickname: String = "",
    /** 语气：gentle 温和 / direct 直接 / strict 严厉 */
    val aiTone: String = TONE_GENTLE,
    /** 关注点：sleep 睡眠 / focus 专注 / entertainment 娱乐 / study 学习 / efficiency 效率 */
    val aiFocus: Set<String> = emptySet(),
    /** 用户给 AI 的额外要求（会拼进系统提示词） */
    val aiCustomPrompt: String = "",

    // ---- 实时提示（陪伴模式） ----
    val realtimeNudgeEnabled: Boolean = true,
    /** 连续使用同一应用多少分钟后提示 */
    val nudgeIntervalMinutes: Int = 15,
    /** 是否用 AI 生成提示语（更费 token，默认关，用本地文案） */
    val nudgeUseAi: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"

        const val FORMAT_OPENAI = "openai"
        const val FORMAT_ANTHROPIC = "anthropic"

        const val STYLE_GENTLE = "gentle"
        const val STYLE_STRICT = "strict"

        const val TONE_GENTLE = "gentle"
        const val TONE_DIRECT = "direct"
        const val TONE_STRICT = "strict"

        const val FOCUS_SLEEP = "sleep"
        const val FOCUS_STUDY = "study"
        const val FOCUS_ENTERTAINMENT = "entertainment"
        const val FOCUS_EFFICIENCY = "efficiency"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        val DEFAULT = AppSettings()
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DAILY_TARGET = intPreferencesKey("daily_target_minutes")
        val QUIET_START = intPreferencesKey("quiet_hours_start")
        val QUIET_END = intPreferencesKey("quiet_hours_end")
        val NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
        val AI_ANALYSIS_ENABLED = booleanPreferencesKey("ai_analysis_enabled")
        val REMINDER_STYLE = stringPreferencesKey("reminder_style")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_FORMAT = stringPreferencesKey("ai_format")
        val NOTIFY_DAILY_REMINDER = booleanPreferencesKey("notify_daily_reminder")
        val NOTIFY_GOAL_EXCEEDED = booleanPreferencesKey("notify_goal_exceeded")
        val NOTIFY_ANOMALY = booleanPreferencesKey("notify_anomaly")
        val NOTIFY_DAILY_SUMMARY = booleanPreferencesKey("notify_daily_summary")
        val NOTIFY_WEEKLY_SUMMARY = booleanPreferencesKey("notify_weekly_summary")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val RESTRICT_BY_DEFAULT = booleanPreferencesKey("restrict_by_default")
        val AI_NICKNAME = stringPreferencesKey("ai_nickname")
        val AI_TONE = stringPreferencesKey("ai_tone")
        val AI_FOCUS = stringSetPreferencesKey("ai_focus")
        val AI_CUSTOM_PROMPT = stringPreferencesKey("ai_custom_prompt")
        val REALTIME_NUDGE_ENABLED = booleanPreferencesKey("realtime_nudge_enabled")
        val NUDGE_INTERVAL_MINUTES = intPreferencesKey("nudge_interval_minutes")
        val NUDGE_USE_AI = booleanPreferencesKey("nudge_use_ai")
        val ALLOWED_PACKAGES = stringSetPreferencesKey("allowed_packages")
        val ALLOWLIST_SEEDED = booleanPreferencesKey("allowlist_seeded")
    }

    val settings: Flow<AppSettings> = context.appSettingsStore.data.map { prefs ->
        val defaults = AppSettings.DEFAULT
        AppSettings(
            dailyTargetMinutes = prefs[Keys.DAILY_TARGET] ?: defaults.dailyTargetMinutes,
            quietHoursStart = prefs[Keys.QUIET_START] ?: defaults.quietHoursStart,
            quietHoursEnd = prefs[Keys.QUIET_END] ?: defaults.quietHoursEnd,
            notificationEnabled = prefs[Keys.NOTIFICATION_ENABLED] ?: defaults.notificationEnabled,
            aiAnalysisEnabled = prefs[Keys.AI_ANALYSIS_ENABLED] ?: defaults.aiAnalysisEnabled,
            reminderStyle = prefs[Keys.REMINDER_STYLE] ?: defaults.reminderStyle,
            aiBaseUrl = prefs[Keys.AI_BASE_URL] ?: defaults.aiBaseUrl,
            aiApiKey = prefs[Keys.AI_API_KEY] ?: defaults.aiApiKey,
            aiModel = prefs[Keys.AI_MODEL] ?: defaults.aiModel,
            aiFormat = prefs[Keys.AI_FORMAT] ?: defaults.aiFormat,
            notifyDailyReminder = prefs[Keys.NOTIFY_DAILY_REMINDER] ?: defaults.notifyDailyReminder,
            notifyGoalExceeded = prefs[Keys.NOTIFY_GOAL_EXCEEDED] ?: defaults.notifyGoalExceeded,
            notifyAnomaly = prefs[Keys.NOTIFY_ANOMALY] ?: defaults.notifyAnomaly,
            notifyDailySummary = prefs[Keys.NOTIFY_DAILY_SUMMARY] ?: defaults.notifyDailySummary,
            notifyWeeklySummary = prefs[Keys.NOTIFY_WEEKLY_SUMMARY] ?: defaults.notifyWeeklySummary,
            themeMode = prefs[Keys.THEME_MODE] ?: defaults.themeMode,
            restrictByDefault = prefs[Keys.RESTRICT_BY_DEFAULT] ?: defaults.restrictByDefault,
            allowedPackages = prefs[Keys.ALLOWED_PACKAGES] ?: defaults.allowedPackages,
            aiNickname = prefs[Keys.AI_NICKNAME] ?: defaults.aiNickname,
            aiTone = prefs[Keys.AI_TONE] ?: defaults.aiTone,
            aiFocus = prefs[Keys.AI_FOCUS] ?: defaults.aiFocus,
            aiCustomPrompt = prefs[Keys.AI_CUSTOM_PROMPT] ?: defaults.aiCustomPrompt,
            realtimeNudgeEnabled = prefs[Keys.REALTIME_NUDGE_ENABLED] ?: defaults.realtimeNudgeEnabled,
            nudgeIntervalMinutes = prefs[Keys.NUDGE_INTERVAL_MINUTES] ?: defaults.nudgeIntervalMinutes,
            nudgeUseAi = prefs[Keys.NUDGE_USE_AI] ?: defaults.nudgeUseAi,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDailyTarget(minutes: Int) = edit { it[Keys.DAILY_TARGET] = minutes }

    suspend fun setQuietHours(start: Int, end: Int) = context.appSettingsStore.edit { prefs ->
        prefs[Keys.QUIET_START] = start
        prefs[Keys.QUIET_END] = end
    }

    suspend fun setNotificationEnabled(enabled: Boolean) = edit { it[Keys.NOTIFICATION_ENABLED] = enabled }

    suspend fun setAiAnalysisEnabled(enabled: Boolean) = edit { it[Keys.AI_ANALYSIS_ENABLED] = enabled }

    suspend fun setReminderStyle(style: String) = edit { it[Keys.REMINDER_STYLE] = style }

    suspend fun setAiConfig(baseUrl: String, apiKey: String, model: String, format: String) =
        context.appSettingsStore.edit { prefs ->
            prefs[Keys.AI_BASE_URL] = baseUrl.trim()
            prefs[Keys.AI_API_KEY] = apiKey.trim()
            prefs[Keys.AI_MODEL] = model.trim().ifBlank { AppSettings.DEFAULT_MODEL }
            prefs[Keys.AI_FORMAT] = format
        }

    suspend fun setNotifyDailyReminder(enabled: Boolean) = edit { it[Keys.NOTIFY_DAILY_REMINDER] = enabled }

    suspend fun setNotifyGoalExceeded(enabled: Boolean) = edit { it[Keys.NOTIFY_GOAL_EXCEEDED] = enabled }

    suspend fun setNotifyAnomaly(enabled: Boolean) = edit { it[Keys.NOTIFY_ANOMALY] = enabled }

    suspend fun setNotifyDailySummary(enabled: Boolean) = edit { it[Keys.NOTIFY_DAILY_SUMMARY] = enabled }

    suspend fun setNotifyWeeklySummary(enabled: Boolean) = edit { it[Keys.NOTIFY_WEEKLY_SUMMARY] = enabled }

    suspend fun setThemeMode(mode: String) = edit { it[Keys.THEME_MODE] = mode }

    suspend fun setRestrictByDefault(enabled: Boolean) =
        edit { it[Keys.RESTRICT_BY_DEFAULT] = enabled }

    /** 首次升级到「默认限制」模式时，把已安装的通讯/办公类应用加入白名单，避免误伤必要工具 */
    suspend fun seedAllowlistOnce(candidates: Collection<String>) {
        val seeded = context.appSettingsStore.data.first()[Keys.ALLOWLIST_SEEDED] == true
        if (seeded) return
        context.appSettingsStore.edit { prefs ->
            val current = prefs[Keys.ALLOWED_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            current.addAll(candidates)
            prefs[Keys.ALLOWED_PACKAGES] = current
            prefs[Keys.ALLOWLIST_SEEDED] = true
        }
    }

    suspend fun setAiPersona(nickname: String, tone: String, focus: Set<String>, customPrompt: String) =
        context.appSettingsStore.edit { prefs ->
            prefs[Keys.AI_NICKNAME] = nickname.trim()
            prefs[Keys.AI_TONE] = tone
            prefs[Keys.AI_FOCUS] = focus
            prefs[Keys.AI_CUSTOM_PROMPT] = customPrompt.trim()
        }

    suspend fun setRealtimeNudgeEnabled(enabled: Boolean) =
        edit { it[Keys.REALTIME_NUDGE_ENABLED] = enabled }

    suspend fun setNudgeIntervalMinutes(minutes: Int) =
        edit { it[Keys.NUDGE_INTERVAL_MINUTES] = minutes.coerceIn(1, 120) }

    suspend fun setNudgeUseAi(enabled: Boolean) =
        edit { it[Keys.NUDGE_USE_AI] = enabled }

    suspend fun setAllowed(packageName: String, allowed: Boolean) =
        context.appSettingsStore.edit { prefs ->
            val current = prefs[Keys.ALLOWED_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (allowed) current.add(packageName) else current.remove(packageName)
            prefs[Keys.ALLOWED_PACKAGES] = current
        }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.appSettingsStore.edit { prefs -> block(prefs) }
    }
}
