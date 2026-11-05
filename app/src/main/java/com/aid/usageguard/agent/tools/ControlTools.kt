package com.aid.usageguard.agent.tools

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aid.usageguard.MainActivity
import com.aid.usageguard.R
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.ActionDraftRepository
import com.aid.usageguard.data.repo.ReportRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.repo.WhitelistRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.quota.FocusWindow
import com.aid.usageguard.notify.NotificationHelper
import com.aid.usageguard.restriction.AppRestrictionController
import com.aid.usageguard.restriction.EffectiveRestrictionResolver
import com.aid.usageguard.restriction.RuleValidator
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 控制工具组（15 个）：让 AI 助手在**用户明确要求**时调控本 App。
 *
 * v1.7 权限与确认（方案 §6.1/§6.2）：
 * - 只读类 [GetAppSettingsTool] 是 QUERY，所有任务域可见；
 * - 12 个会改设置 / 限制 / 白名单 / 数据同步的工具改为 **PROPOSE**：不再直接写 DataStore/Room，
 *   而是调用 [ActionDraftRepository.propose] 生成 PENDING 待确认草案（携带修改前后值），
 *   返回 `{executed:false, requiresConfirmation:true, opId, type, changed, message}`；
 *   用户确认后由本地 `ActionExecutor` 按保存的参数执行；
 * - 生成报告（本地结果保存）与测试通知（瞬时通知）没有「前后值」，按方案 §6 属于允许的本地结果/通知，
 *   归为 CONTROL：只在对话域可用，直接执行但绝不改用户业务配置。
 *
 * 执行器会独立校验 [ToolKind]：报告 / 后台 / 洞察任务里这些 PROPOSE/CONTROL 工具既不可见也执行不了。
 */
class ControlTools(
    private val context: Context,
    private val settings: SettingsRepository,
    private val restrictions: AppRestrictionController,
    private val reports: ReportRepository,
    private val usage: UsageRepository,
    private val source: UsageEventSource,
    /** 待确认草案仓库（默认延迟取 Container，避免 AppWiring 再多一个注入参数） */
    private val actionDraftsProvider: () -> ActionDraftRepository = { Container.actionDrafts },
    /** 当前任务 id，由 AgentLoop 注入；草案会归属到该任务 */
    private val taskIdProvider: () -> String = { "" },
    /** 白名单仓储（v1.8.2：只读每日添加上限），默认延迟取 Container */
    private val whitelistProvider: () -> WhitelistRepository = { Container.whitelist },
) {

    val all: List<AgentTool> = listOf(
        GetAppSettingsTool(),
        SetDailyTargetTool(),
        SetQuietHoursTool(),
        SetNotificationEnabledTool(),
        SetNotificationChannelsTool(),
        SetReminderStyleTool(),
        SetThemeModeTool(),
        SetRestrictModeTool(),
        SetNudgeTool(),
        SetAiPersonaTool(),
        SetAppRestrictionTool(),
        SetAiAnalysisEnabledTool(),
        RefreshUsageDataTool(),
        GenerateReportNowTool(),
        SendTestNotificationTool(),
    )

    /** generate_report_now 的递归护栏（报告任务自身也会挂载只读工具） */
    @Volatile
    private var reportGenerating = false

    // ==================================================================
    // 1) 只读：get_app_settings（QUERY）
    // ==================================================================

    private inner class GetAppSettingsTool : AgentTool {
        override val name: String = "get_app_settings"
        override val description: String =
            "查询全部 App 设置（目标/额度、勿扰、通知、主题、限制模式、白名单、专注时段、AI 个性化）。只读。"


        override val parameters: JSONObject = JsonSchema.objectSchema(properties = emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val prefs = settings.current()
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val focusActive = prefs.focusWindowEnabled &&
                FocusWindow.isActive(hour, prefs.focusWindowStart, prefs.focusWindowEnd)
            val whitelistQuota = runCatching { whitelistProvider().quota() }.getOrNull()
            return JSONObject().apply {
                put("executed", false)
                put("changed", JSONObject())
                put("dailyTargetMinutes", prefs.dailyTargetMinutes)
                put("dailyBudgetEnforced", prefs.dailyBudgetEnforced)
            put("restrictionModeLabel", com.aid.usageguard.restriction.RestrictionMode.parse(prefs.restrictionMode).label)
                put(
                    "quietHours",
                    JSONObject().apply {
                        put("start", prefs.quietHoursStart)
                        put("end", prefs.quietHoursEnd)
                        put(
                            "text",
                            ToolFormat.clock(prefs.quietHoursStart) + " - " + ToolFormat.clock(prefs.quietHoursEnd),
                        )
                    },
                )
                put("notificationEnabled", prefs.notificationEnabled)
                put("notifyDailyReminder", prefs.notifyDailyReminder)
                put("notifyGoalExceeded", prefs.notifyGoalExceeded)
                put("notifyAnomaly", prefs.notifyAnomaly)
                put("notifyDailySummary", prefs.notifyDailySummary)
                put("notifyWeeklySummary", prefs.notifyWeeklySummary)
                put("reminderStyle", prefs.reminderStyle)
                put("themeMode", prefs.themeMode)
                put("restrictByDefault", prefs.restrictByDefault)
            put(
                "restrictionMode",
                com.aid.usageguard.restriction.RestrictionMode.parse(prefs.restrictionMode).name,
            )
                put("whitelistCount", prefs.allowedPackages.size)
                put("whitelistDailyLimit", whitelistQuota?.limit ?: prefs.whitelistDailyLimit)
                put("whitelistAddedToday", whitelistQuota?.used ?: prefs.whitelistAddCount)
                put(
                    "whitelistRemainingToday",
                    whitelistQuota?.let { if (it.unlimited) -1 else it.remaining } ?: -1,
                )
                put("focusWindow", JSONObject().apply {
                    put("enabled", prefs.focusWindowEnabled)
                    put("start_hour", prefs.focusWindowStart)
                    put("end_hour", prefs.focusWindowEnd)
                    put("text", FocusWindow.label(prefs.focusWindowStart, prefs.focusWindowEnd))
                    put("active_now", focusActive)
                })
                put("aiAnalysisEnabled", prefs.aiAnalysisEnabled)
                put("realtimeNudgeEnabled", prefs.realtimeNudgeEnabled)
                put("nudgeIntervalMinutes", prefs.nudgeIntervalMinutes)
                put("nudgeUseAi", prefs.nudgeUseAi)
                put("aiNickname", prefs.aiNickname)
                put("aiTone", prefs.aiTone)
                put("aiFocus", JSONArray(prefs.aiFocus.toList()))
                put("aiCustomPrompt", prefs.aiCustomPrompt)
                put("message", "已读取当前设置。")
                put("settingsUrlHint", SETTINGS_HINT)
            }
        }
    }

    // ==================================================================
    // 2) 每日目标（PROPOSE → SET_DAILY_TARGET）
    // ==================================================================

    private inner class SetDailyTargetTool : AgentTool {
        override val name: String = "set_daily_target"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成每日目标草案（30-1440 分钟）；该分钟数即每日总额度，配 set_daily_budget_enforced 决定超限拦截。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "minutes" to JsonSchema.integer("每日目标分钟数，30-1440", minimum = 30, maximum = 1440),
            ),
            required = listOf("minutes"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val minutes = arguments.intArg("minutes")
                ?: throw ToolArgumentException("缺少必填参数 minutes（30-1440 的整数）")
            if (minutes !in 30..1440) {
                throw ToolArgumentException("minutes 需在 30-1440 之间，收到 $minutes")
            }
            val before = settings.current().dailyTargetMinutes
            return proposeDraft(
                type = "SET_DAILY_TARGET",
                params = JSONObject().put("minutes", minutes),
                before = JSONObject().put("minutes", before),
                after = JSONObject().put("minutes", minutes),
                changed = changedOf("dailyTargetMinutes" to change(before, minutes)),
                message = "每日目标 $before → $minutes 分钟。",
            )
        }
    }

    // ==================================================================
    // 3) 勿扰时段（PROPOSE → SET_QUIET_HOURS）
    // ==================================================================

    private inner class SetQuietHoursTool : AgentTool {
        override val name: String = "set_quiet_hours"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成免打扰时段草案（0-23 点，支持跨零点），需确认。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "start_hour" to JsonSchema.integer("开始小时，0-23", minimum = 0, maximum = 23),
                "end_hour" to JsonSchema.integer("结束小时，0-23", minimum = 0, maximum = 23),
            ),
            required = listOf("start_hour", "end_hour"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val start = arguments.intArg("start_hour")
                ?: throw ToolArgumentException("缺少必填参数 start_hour（0-23 的整数）")
            val end = arguments.intArg("end_hour")
                ?: throw ToolArgumentException("缺少必填参数 end_hour（0-23 的整数）")
            if (start !in 0..23) throw ToolArgumentException("start_hour 需在 0-23 之间，收到 $start")
            if (end !in 0..23) throw ToolArgumentException("end_hour 需在 0-23 之间，收到 $end")

            val before = settings.current()
            return proposeDraft(
                type = "SET_QUIET_HOURS",
                params = JSONObject().put("start_hour", start).put("end_hour", end),
                before = JSONObject()
                    .put("start_hour", before.quietHoursStart)
                    .put("end_hour", before.quietHoursEnd),
                after = JSONObject().put("start_hour", start).put("end_hour", end),
                changed = changedOf(
                    "quietHoursStart" to change(before.quietHoursStart, start),
                    "quietHoursEnd" to change(before.quietHoursEnd, end),
                ),
                message = "勿扰时段 ${ToolFormat.clock(before.quietHoursStart)}-${ToolFormat.clock(before.quietHoursEnd)}" +
                    " → ${ToolFormat.clock(start)}-${ToolFormat.clock(end)}。",
            )
        }
    }

    // ==================================================================
    // 4) 通知总开关（PROPOSE → SET_NOTIFICATION_ENABLED）
    // ==================================================================

    private inner class SetNotificationEnabledTool : AgentTool {
        override val name: String = "set_notification_enabled"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成通知总开关草案（开启/关闭全部通知），需确认。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "enabled" to JsonSchema.boolean("true=开启通知；false=关闭所有通知"),
            ),
            required = listOf("enabled"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val enabled = arguments.boolArg("enabled")
                ?: throw ToolArgumentException("缺少必填参数 enabled（true / false）")
            val before = settings.current().notificationEnabled
            return proposeDraft(
                type = "SET_NOTIFICATION_ENABLED",
                params = JSONObject().put("enabled", enabled),
                before = JSONObject().put("enabled", before),
                after = JSONObject().put("enabled", enabled),
                changed = changedOf("notificationEnabled" to change(before, enabled)),
                message = "通知总开关 ${onOff(before)} → ${onOff(enabled)}。",
            )
        }
    }

    // ==================================================================
    // 5) 通知渠道（PROPOSE → SET_NOTIFICATION_CHANNELS）
    // ==================================================================

    private inner class SetNotificationChannelsTool : AgentTool {
        override val name: String = "set_notification_channels"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成通知渠道草案：daily_reminder/goal_exceeded/anomaly/proactive/daily_summary/weekly_summary，只改传入项。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "daily_reminder" to JsonSchema.boolean("每日使用提醒，可选"),
                "goal_exceeded" to JsonSchema.boolean("超出每日目标的提醒，可选"),
                "anomaly" to JsonSchema.boolean("异常使用提醒，可选"),
                "proactive" to JsonSchema.boolean("主动洞察提醒（AI 主动发现使用变化），可选"),
                "daily_summary" to JsonSchema.boolean("每日报告摘要，可选"),
                "weekly_summary" to JsonSchema.boolean("每周报告摘要，可选"),
            ),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val provided = LinkedHashMap<String, Boolean>()
            CHANNEL_KEYS.forEach { key ->
                arguments.boolArg(key)?.let { provided[key] = it }
            }
            if (provided.isEmpty()) {
                throw ToolArgumentException("至少提供一个渠道开关：${CHANNEL_KEYS.joinToString(" / ")}")
            }

            val before = settings.current()
            val params = JSONObject()
            val beforeJson = JSONObject()
            val afterJson = JSONObject()
            val changed = JSONObject()
            val parts = ArrayList<String>()
            provided.forEach { (key, enabled) ->
                params.put(key, enabled)
                val old = channelValue(before, key)
                beforeJson.put(key, old)
                afterJson.put(key, enabled)
                changed.put(key, change(old, enabled))
                parts.add("${channelLabel(key)} ${onOff(old)}→${onOff(enabled)}")
            }
            return proposeDraft(
                type = "SET_NOTIFICATION_CHANNELS",
                params = params,
                before = beforeJson,
                after = afterJson,
                changed = changed,
                message = "通知渠道：" + parts.joinToString("；") + "。",
            )
        }
    }

    // ==================================================================
    // 6) 提醒风格（PROPOSE → SET_REMINDER_STYLE）
    // ==================================================================

    private inner class SetReminderStyleTool : AgentTool {
        override val name: String = "set_reminder_style"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成提醒风格草案：gentle（温和）/strict（严格），需确认。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "style" to JsonSchema.string("提醒风格", enum = listOf("gentle", "strict")),
            ),
            required = listOf("style"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val style = arguments.stringArg("style")
                ?: throw ToolArgumentException("缺少必填参数 style（gentle / strict）")
            requireEnum(style, listOf("gentle", "strict"), "style")

            val before = settings.current().reminderStyle
            return proposeDraft(
                type = "SET_REMINDER_STYLE",
                params = JSONObject().put("style", style),
                before = JSONObject().put("style", before),
                after = JSONObject().put("style", style),
                changed = changedOf("reminderStyle" to change(before, style)),
                message = "提醒风格 ${reminderStyleLabel(before)} → ${reminderStyleLabel(style)}。",
            )
        }
    }

    // ==================================================================
    // 7) 主题（PROPOSE → SET_THEME_MODE）
    // ==================================================================

    private inner class SetThemeModeTool : AgentTool {
        override val name: String = "set_theme_mode"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成主题草案：system（跟随系统）/light/dark，需确认。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "mode" to JsonSchema.string("主题模式", enum = listOf("system", "light", "dark")),
            ),
            required = listOf("mode"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val mode = arguments.stringArg("mode")
                ?: throw ToolArgumentException("缺少必填参数 mode（system / light / dark）")
            requireEnum(mode, listOf("system", "light", "dark"), "mode")

            val before = settings.current().themeMode
            return proposeDraft(
                type = "SET_THEME_MODE",
                params = JSONObject().put("mode", mode),
                before = JSONObject().put("mode", before),
                after = JSONObject().put("mode", mode),
                changed = changedOf("themeMode" to change(before, mode)),
                message = "主题 ${themeLabel(before)} → ${themeLabel(mode)}。",
            )
        }
    }

    // ==================================================================
    // 8) 限制模式（PROPOSE → SET_RESTRICT_MODE）
    // ==================================================================

    private inner class SetRestrictModeTool : AgentTool {
        override val name: String = "set_restrict_mode"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成限制模式草案：开启=默认限制所有第三方应用（白名单除外）；关闭=只限制手动指定应用。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "enabled" to JsonSchema.boolean("true=默认限制所有第三方应用；false=只限制手动指定的应用"),
            ),
            required = listOf("enabled"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val enabled = arguments.boolArg("enabled")
                ?: throw ToolArgumentException("缺少必填参数 enabled（true / false）")
            val before = settings.current().restrictByDefault
            val summary = if (enabled) {
                "默认限制所有第三方应用（白名单除外）"
            } else {
                "只限制手动指定的应用"
            }
            return proposeDraft(
                type = "SET_RESTRICT_MODE",
                params = JSONObject().put("enabled", enabled),
                before = JSONObject().put("enabled", before),
                after = JSONObject().put("enabled", enabled),
                changed = changedOf("restrictByDefault" to change(before, enabled)),
                message = "限制模式 ${if (before) "默认限制" else "仅手动限制"} → $summary。",
            )
        }
    }

    // ==================================================================
    // 9) 实时提示（PROPOSE → SET_NUDGE）
    // ==================================================================

    private inner class SetNudgeTool : AgentTool {
        override val name: String = "set_nudge"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成实时提示草案（enabled / interval_minutes 5-120 / use_ai），只改传入项。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "enabled" to JsonSchema.boolean("是否开启实时提示，可选"),
                "interval_minutes" to JsonSchema.integer("提示间隔分钟数，5-120，可选", minimum = 5, maximum = 120),
                "use_ai" to JsonSchema.boolean("是否用 AI 生成提示文案，可选"),
            ),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val enabled = arguments.boolArg("enabled")
            val interval = arguments.intArg("interval_minutes")
            val useAi = arguments.boolArg("use_ai")
            if (enabled == null && interval == null && useAi == null) {
                throw ToolArgumentException("至少提供一个参数：enabled / interval_minutes / use_ai")
            }
            if (interval != null && interval !in 5..120) {
                throw ToolArgumentException("interval_minutes 需在 5-120 之间，收到 $interval")
            }

            val before = settings.current()
            val afterEnabled = enabled ?: before.realtimeNudgeEnabled
            val afterInterval = interval ?: before.nudgeIntervalMinutes
            val afterUseAi = useAi ?: before.nudgeUseAi

            val params = JSONObject()
            val beforeJson = JSONObject()
            val afterJson = JSONObject()
            val changed = JSONObject()
            if (enabled != null) {
                params.put("enabled", enabled)
                beforeJson.put("enabled", before.realtimeNudgeEnabled)
                afterJson.put("enabled", afterEnabled)
                changed.put("realtimeNudgeEnabled", change(before.realtimeNudgeEnabled, afterEnabled))
            }
            if (interval != null) {
                params.put("interval_minutes", interval)
                beforeJson.put("interval_minutes", before.nudgeIntervalMinutes)
                afterJson.put("interval_minutes", afterInterval)
                changed.put("nudgeIntervalMinutes", change(before.nudgeIntervalMinutes, afterInterval))
            }
            if (useAi != null) {
                params.put("use_ai", useAi)
                beforeJson.put("use_ai", before.nudgeUseAi)
                afterJson.put("use_ai", afterUseAi)
                changed.put("nudgeUseAi", change(before.nudgeUseAi, afterUseAi))
            }
            val beforeSummary = nudgeSummary(before.realtimeNudgeEnabled, before.nudgeIntervalMinutes, before.nudgeUseAi)
            val afterSummary = nudgeSummary(afterEnabled, afterInterval, afterUseAi)
            return proposeDraft(
                type = "SET_NUDGE",
                params = params,
                before = beforeJson,
                after = afterJson,
                changed = changed,
                message = "实时提示 $beforeSummary → $afterSummary。",
            )
        }
    }

    // ==================================================================
    // 10) AI 个性化（PROPOSE → SET_AI_PERSONA）
    // ==================================================================

    private inner class SetAiPersonaTool : AgentTool {
        override val name: String = "set_ai_persona"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成 AI 个性化草案：称呼/语气(gentle|direct|strict)/关注点/自定义要求，空值=清空，需确认。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "nickname" to JsonSchema.string("AI 对你的称呼；传空串表示清空，可选"),
                "tone" to JsonSchema.string("AI 语气", enum = listOf("gentle", "direct", "strict")),
                "focus" to JsonSchema.stringArray("关注点数组，可选", enum = FOCUS_KEYS),
                "custom_prompt" to JsonSchema.string("给 AI 的额外要求；传空串表示清空，可选"),
            ),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val nicknameProvided = arguments.has("nickname") && !arguments.isNull("nickname")
            val nickname = arguments.optionalRawString("nickname")
            val toneRaw = arguments.stringArg("tone")
            val focus = arguments.arrayArg("focus")
            val customProvided = arguments.has("custom_prompt") && !arguments.isNull("custom_prompt")
            val custom = arguments.optionalRawString("custom_prompt")

            if (!nicknameProvided && toneRaw == null && focus == null && !customProvided) {
                throw ToolArgumentException("至少提供一个参数：nickname / tone / focus / custom_prompt")
            }
            val tone = toneRaw?.let { requireEnum(it, listOf("gentle", "direct", "strict"), "tone") }
            focus?.forEach { requireEnum(it, FOCUS_KEYS, "focus[]") }

            val before = settings.current()
            val newNickname = if (nicknameProvided) nickname.orEmpty() else before.aiNickname
            val newTone = tone ?: before.aiTone
            val newFocus = focus?.toSet() ?: before.aiFocus
            val newCustom = if (customProvided) custom.orEmpty() else before.aiCustomPrompt

            val params = JSONObject()
            val beforeJson = JSONObject()
            val afterJson = JSONObject()
            val changed = JSONObject()
            val parts = ArrayList<String>()

            if (nicknameProvided) {
                params.put("nickname", newNickname)
                beforeJson.put("nickname", before.aiNickname)
                afterJson.put("nickname", newNickname)
                changed.put("aiNickname", change(before.aiNickname, newNickname))
                parts.add(if (newNickname.isEmpty()) "清空称呼" else "称呼改为「$newNickname」")
            }
            if (tone != null) {
                params.put("tone", tone)
                beforeJson.put("tone", before.aiTone)
                afterJson.put("tone", tone)
                changed.put("aiTone", change(before.aiTone, tone))
                parts.add("语气 ${toneLabel(before.aiTone)} → ${toneLabel(tone)}")
            }
            if (focus != null) {
                params.put("focus", JSONArray(focus))
                beforeJson.put("focus", JSONArray(before.aiFocus.toList()))
                afterJson.put("focus", JSONArray(newFocus.toList()))
                changed.put("aiFocus", change(JSONArray(before.aiFocus.toList()), JSONArray(newFocus.toList())))
                parts.add(if (newFocus.isEmpty()) "清空关注点" else "关注点改为 ${focusText(newFocus)}")
            }
            if (customProvided) {
                params.put("custom_prompt", newCustom)
                beforeJson.put("custom_prompt", before.aiCustomPrompt)
                afterJson.put("custom_prompt", newCustom)
                changed.put("aiCustomPrompt", change(before.aiCustomPrompt, newCustom))
                parts.add(if (newCustom.isEmpty()) "清空自定义要求" else "更新自定义要求")
            }
            return proposeDraft(
                type = "SET_AI_PERSONA",
                params = params,
                before = beforeJson,
                after = afterJson,
                changed = changed,
                message = "AI 个性化：" + parts.joinToString("；") + "。",
            )
        }
    }

    // ==================================================================
    // 11) 应用限制（PROPOSE → SET_APP_RESTRICTION）
    // ==================================================================

    private inner class SetAppRestrictionTool : AgentTool {
        override val name: String = "set_app_restriction"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成限制/放行应用草案；系统保护应用会被拒，限制白名单应用会一并移出白名单。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "package_name" to JsonSchema.string(
                    "应用包名或应用名，例如 com.tencent.mm / 微信",
                    format = "app_ref",
                ),
                "restricted" to JsonSchema.boolean("true=限制该应用；false=放行该应用"),
            ),
            required = listOf("package_name", "restricted"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val input = arguments.stringArg("package_name")
                ?: throw ToolArgumentException("缺少必填参数 package_name（包名或应用名）")
            val restricted = arguments.boolArg("restricted")
                ?: throw ToolArgumentException("缺少必填参数 restricted（true / false）")

            val packageName = resolvePackageName(usage, source, input)
                ?: throw ToolArgumentException(
                    "没有找到应用「$input」。可以先用 get_usage_ranking 或 get_whitelist 查准确的包名。",
                )
            val appName = source.appNameOf(packageName) ?: packageName

            val launcherPackages = runCatching { source.launcherPackages() }.getOrDefault(emptySet())
            if (RuleValidator.isProtected(packageName, launcherPackages)) {
                return result(
                    executed = false,
                    message = "「$appName」是系统保护应用（电话/短信/系统设置/桌面/本应用等），不能限制。",
                    settingsUrlHint = APPS_HINT,
                ).apply {
                    put("packageName", packageName)
                    put("appName", appName)
                    put("restricted", false)
                    put("rejectedReason", "系统保护应用不允许限制")
                }
            }

            val before = settings.current()
            val wasBlocked = restrictions.isBlocked(packageName)
            val whitelistedBefore = before.allowedPackages.contains(packageName)
            val effectiveBefore = EffectiveRestrictionResolver.resolve(
                packageName = packageName,
                allowedPackages = before.allowedPackages,
                explicitBlocked = if (wasBlocked) setOf(packageName) else emptySet(),
                restrictByDefault = before.restrictByDefault,
                protectedPackages = launcherPackages,
            )

            val params = JSONObject()
                .put("package_name", packageName)
                .put("restricted", restricted)
            val beforeJson = JSONObject()
                .put("package_name", packageName)
                .put("restricted", effectiveBefore.restricted)
                .put("whitelisted", whitelistedBefore)
            val afterJson = JSONObject()
                .put("package_name", packageName)
                .put("restricted", restricted)
                .put("whitelisted", if (restricted) false else true)
            val changed = JSONObject().apply {
                put("restricted", change(effectiveBefore.restricted, restricted))
                if (restricted && whitelistedBefore) {
                    put("whitelisted", change(true, false))
                }
            }
            val message = if (restricted) {
                buildString {
                    append("限制「").append(appName).append("」")
                    if (whitelistedBefore) append("（将移出白名单并加入手动限制）")
                    append("；当前有效状态：").append(effectiveBefore.reason.label)
                    append(" -> 受限。")
                }
            } else {
                "放行「$appName」（移出手动限制并加入允许名单）。"
            }

            return proposeDraft(
                type = "SET_APP_RESTRICTION",
                params = params,
                before = beforeJson,
                after = afterJson,
                changed = changed,
                message = message,
                packages = setOf(packageName),
                settingsUrlHint = APPS_HINT,
            ).apply {
                put("packageName", packageName)
                put("appName", appName)
                put("requestedRestricted", restricted)
                put("wasWhitelisted", whitelistedBefore)
            }
        }
    }

    // ==================================================================
    // 12) AI 分析开关（PROPOSE → SET_AI_ANALYSIS_ENABLED）
    // ==================================================================

    private inner class SetAiAnalysisEnabledTool : AgentTool {
        override val name: String = "set_ai_analysis_enabled"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成 AI 分析开关草案：关闭后日报/周报只用本地规则，不消耗 token。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "enabled" to JsonSchema.boolean("true=允许调用 AI 生成报告；false=只用本地规则"),
            ),
            required = listOf("enabled"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val enabled = arguments.boolArg("enabled")
                ?: throw ToolArgumentException("缺少必填参数 enabled（true / false）")
            val before = settings.current().aiAnalysisEnabled
            return proposeDraft(
                type = "SET_AI_ANALYSIS_ENABLED",
                params = JSONObject().put("enabled", enabled),
                before = JSONObject().put("enabled", before),
                after = JSONObject().put("enabled", enabled),
                changed = changedOf("aiAnalysisEnabled" to change(before, enabled)),
                message = if (enabled) "开启 AI 分析。" else "关闭 AI 分析（只用本地规则）。",
            )
        }
    }

    // ==================================================================
    // 13) 刷新使用数据（PROPOSE → REFRESH_USAGE_DATA）
    // ==================================================================

    private inner class RefreshUsageDataTool : AgentTool {
        override val name: String = "refresh_usage_data"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成立即同步使用数据的草案；仅用户明确要求刷新/同步时用。"


        override val parameters: JSONObject = JsonSchema.objectSchema(properties = emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            return proposeDraft(
                type = "REFRESH_USAGE_DATA",
                params = JSONObject(),
                before = JSONObject(),
                after = JSONObject(),
                changed = JSONObject(),
                message = "确认后将立即同步一次手机使用数据。",
                settingsUrlHint = DASHBOARD_HINT,
            )
        }
    }

    // ==================================================================
    // 14) 立即生成报告（CONTROL，本地结果保存，直接执行）
    // ==================================================================

    private inner class GenerateReportNowTool : AgentTool {
        override val name: String = "generate_report_now"
        override val kind: ToolKind = ToolKind.CONTROL
        override val description: String =
            "立即生成并保存日报/周报（AI 或本地规则），不改设置、无需确认；仅对话可用。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "type" to JsonSchema.string("报告类型", enum = listOf("daily", "weekly")),
            ),
            required = listOf("type"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val raw = arguments.stringArg("type")
                ?: throw ToolArgumentException("缺少必填参数 type（daily / weekly）")
            val type = requireEnum(raw.lowercase(), listOf("daily", "weekly"), "type")

            if (reportGenerating) {
                throw ToolArgumentException("正在生成报告，请稍后再试；不要重复调用本工具。")
            }
            reportGenerating = true
            try {
                val report = if (type == "weekly") reports.generateWeekly() else reports.generateDaily()
                val label = if (type == "weekly") "周报" else "日报"
                val sourceLabel = if (report.source == "AI") "AI" else "本地规则"
                return result(
                    executed = true,
                    message = "已生成$label（来源：$sourceLabel），共 ${report.content.length} 字。",
                    settingsUrlHint = REPORT_HINT,
                ).apply {
                    put("reportType", report.reportType)
                    put("source", report.source)
                    put("charCount", report.content.length)
                    put("relatedDate", report.relatedDate)
                    put("preview", report.content.take(160))
                }
            } finally {
                reportGenerating = false
            }
        }
    }

    // ==================================================================
    // 15) 测试通知（CONTROL，瞬时通知，直接执行）
    // ==================================================================

    private inner class SendTestNotificationTool : AgentTool {
        override val name: String = "send_test_notification"
        override val kind: ToolKind = ToolKind.CONTROL
        override val description: String =
            "发送一条系统测试通知（不改设置、无需确认），仅对话可用。"


        override val parameters: JSONObject = JsonSchema.objectSchema(properties = emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            if (!NotificationHelper.canNotify(context)) {
                return result(
                    executed = false,
                    message = "通知权限未开启（或系统里关闭了 UsageGuard 通知），请先授予通知权限后再试。",
                    settingsUrlHint = SETTINGS_HINT,
                ).apply { put("ok", false) }
            }
            val sent = runCatching { postTestNotification() }.getOrDefault(false)
            return result(
                executed = sent,
                message = if (sent) {
                    "已发送测试通知，请下拉通知栏查看（若没看到，检查系统里 UsageGuard 的通知渠道是否被关闭）。"
                } else {
                    "测试通知发送失败，请检查系统通知权限或通知渠道设置。"
                },
                settingsUrlHint = SETTINGS_HINT,
            ).apply { put("ok", sent) }
        }
    }

    // ==================================================================
    // 待确认草案
    // ==================================================================

    /**
     * 生成 PENDING 待确认草案（不写任何业务配置）。
     * 返回统一结构；用户确认后由 ActionExecutor 按保存的参数执行。
     */
    private suspend fun proposeDraft(
        type: String,
        params: JSONObject,
        before: JSONObject,
        after: JSONObject,
        changed: JSONObject,
        message: String,
        packages: Set<String> = emptySet(),
        settingsUrlHint: String = CONFIRM_HINT,
    ): JSONObject {
        val drafts = runCatching { actionDraftsProvider() }.getOrElse {
            throw ToolArgumentException("待确认操作服务尚未就绪，请稍后再试。")
        }
        val taskId = runCatching { taskIdProvider() }.getOrDefault("").ifBlank { "agent-task" }
        val entity = runCatching {
            drafts.propose(
                taskId = taskId,
                type = type,
                packages = packages,
                paramsJson = params.toString(),
                beforeJson = before.toString(),
                afterJson = after.toString(),
            )
        }.getOrElse {
            throw ToolArgumentException("生成待确认操作失败：${it.message?.take(120) ?: "未知错误"}")
        }

        return JSONObject().apply {
            put("executed", false)
            put("requiresConfirmation", true)
            put("opId", entity.opId)
            put("type", entity.type)
            put("status", entity.status)
            put("expiresAt", entity.expiresAt)
            put("changed", changed)
            put("before", before)
            put("after", after)
            put("message", "已生成待确认操作：$message 请在应用内确认后生效。")
            put("settingsUrlHint", settingsUrlHint)
        }
    }

    // ==================================================================
    // 通知实现（NotificationHelper 没有测试入口，这里只借用它的公开 helper
    // 做权限/渠道检查，再直接 post 一条测试通知，避免被开关/免打扰/去重拦截）
    // ==================================================================

    @SuppressLint("MissingPermission")
    private fun postTestNotification(): Boolean {
        NotificationHelper.ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            TEST_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = "如果你看到这条通知，说明通知功能正常：AI 助手可以按你的要求生成待确认操作并给你发通知。"
        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_AI_REPORT)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("UsageGuard 测试通知")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
        return true
    }

    // ==================================================================
    // 工具结果 / 变更的统一结构
    // ==================================================================

    private fun result(
        executed: Boolean,
        message: String,
        changed: JSONObject = JSONObject(),
        settingsUrlHint: String = SETTINGS_HINT,
    ): JSONObject = JSONObject().apply {
        put("executed", executed)
        put("changed", changed)
        put("message", message)
        put("settingsUrlHint", settingsUrlHint)
    }

    private fun change(from: Any?, to: Any?): JSONObject = JSONObject().apply {
        put("from", from ?: JSONObject.NULL)
        put("to", to ?: JSONObject.NULL)
    }

    private fun changedOf(vararg entries: Pair<String, JSONObject>): JSONObject = JSONObject().apply {
        entries.forEach { (key, value) -> put(key, value) }
    }

    private fun requireEnum(value: String, allowed: List<String>, key: String): String {
        if (value !in allowed) {
            throw ToolArgumentException(
                "参数 $key 只能是 ${allowed.joinToString(" / ")}，收到「$value」",
            )
        }
        return value
    }

    private fun onOff(value: Boolean): String = if (value) "开启" else "关闭"

    private fun nudgeSummary(enabled: Boolean, intervalMinutes: Int, useAi: Boolean): String =
        "${onOff(enabled)}，每 $intervalMinutes 分钟，${if (useAi) "AI 文案" else "本地文案"}"

    private fun reminderStyleLabel(style: String): String = when (style) {
        AppSettings.STYLE_STRICT -> "严格"
        else -> "温和"
    }

    private fun themeLabel(mode: String): String = when (mode) {
        AppSettings.THEME_LIGHT -> "浅色"
        AppSettings.THEME_DARK -> "深色"
        else -> "跟随系统"
    }

    private fun toneLabel(tone: String): String = when (tone) {
        AppSettings.TONE_STRICT -> "严厉"
        AppSettings.TONE_DIRECT -> "直接"
        else -> "温和"
    }

    private fun focusLabel(key: String): String = when (key) {
        AppSettings.FOCUS_SLEEP -> "睡眠"
        AppSettings.FOCUS_STUDY -> "学习"
        AppSettings.FOCUS_ENTERTAINMENT -> "娱乐"
        AppSettings.FOCUS_EFFICIENCY -> "效率"
        else -> key
    }

    private fun focusText(focus: Set<String>): String = focus.map { focusLabel(it) }.joinToString("、")

    private fun channelValue(prefs: AppSettings, key: String): Boolean = when (key) {
        "daily_reminder" -> prefs.notifyDailyReminder
        "goal_exceeded" -> prefs.notifyGoalExceeded
        "anomaly" -> prefs.notifyAnomaly
        "proactive" -> prefs.notifyProactive
        "daily_summary" -> prefs.notifyDailySummary
        "weekly_summary" -> prefs.notifyWeeklySummary
        else -> false
    }

    private fun channelLabel(key: String): String = when (key) {
        "daily_reminder" -> "每日提醒"
        "goal_exceeded" -> "超目标提醒"
        "anomaly" -> "异常提醒"
        "proactive" -> "主动洞察"
        "daily_summary" -> "日报摘要"
        "weekly_summary" -> "周报摘要"
        else -> key
    }
}

// ======================================================================
// 文件级常量（放在类外，避免 inner class 里 companion 作用域解析歧义）
// ======================================================================

private const val SETTINGS_HINT = "可在「设置」页查看"
private const val APPS_HINT = "可在「应用」页查看暂停状态"
private const val REPORT_HINT = "可在「报告」页查看"
private const val DASHBOARD_HINT = "可在「首页」查看最新数据"

/** 草案提示：5 分钟内有效，需要用户在应用内确认（方案 §10.2） */
private const val CONFIRM_HINT = "请在应用内确认（草案 5 分钟内有效）"

/** 测试通知 id，避开 NotificationHelper 的 1001-1005 固定 id */
private const val TEST_NOTIFICATION_ID = 1099

private val CHANNEL_KEYS = listOf(
    "daily_reminder",
    "goal_exceeded",
    "anomaly",
    "proactive",
    "daily_summary",
    "weekly_summary",
)

private val FOCUS_KEYS = listOf("sleep", "study", "entertainment", "efficiency")
