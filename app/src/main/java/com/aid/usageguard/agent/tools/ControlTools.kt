package com.aid.usageguard.agent.tools

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aid.usageguard.MainActivity
import com.aid.usageguard.R
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.ReportRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.notify.NotificationHelper
import com.aid.usageguard.restriction.AppRestrictionController
import com.aid.usageguard.restriction.RuleValidator
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 控制工具组（15 个）：让 AI 助手在**用户明确要求**时调控本 App。
 *
 * 设计约定：
 * - 查询类（get_app_settings）只读，随时可调；
 * - 修改类（set_*）一律「仅在用户明确要求修改时调用；只是询问时用 get_app_settings」，
 *   并且返回统一结构 `{executed, changed:{字段:{from,to}}, message, settingsUrlHint}`，
 *   message 是给用户看的一句话（含旧→新），便于模型直接复述；
 * - 涉及系统保护应用（电话/短信/设置/桌面/本应用等）一律用 [RuleValidator] 拒绝；
 * - 没有任何执行代码 / Shell / 任意文件读写的通道。
 *
 * 说明：generate_report_now 会调用 [ReportRepository]，而 ReportRepository 内部会回调
 * [com.aid.usageguard.agent.contract.AgentRuntime.generateDailyReport]，属于「嵌套一次 Agent 任务」；
 * 这里用 [reportGenerating] 做个递归护栏，避免模型在报告任务里再次触发本工具导致无限递归。
 */
class ControlTools(
    private val context: Context,
    private val settings: SettingsRepository,
    private val restrictions: AppRestrictionController,
    private val reports: ReportRepository,
    private val usage: UsageRepository,
    private val source: UsageEventSource,
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

    /** generate_report_now 的递归护栏（报告任务自身也会挂载这些工具） */
    @Volatile
    private var reportGenerating = false

    // ==================================================================
    // 1) 只读：get_app_settings
    // ==================================================================

    private inner class GetAppSettingsTool : AgentTool {
        override val name: String = "get_app_settings"
        override val description: String =
            "查询当前全部 App 设置：每日目标、勿扰时段、通知开关与渠道、提醒风格、主题、限制模式、白名单数量、" +
                "实时提示、AI 分析与个性化。只读，不会修改任何设置。修改请用对应的 set_* 工具。"

        override val parameters: JSONObject = JsonSchema.objectSchema(properties = emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val prefs = settings.current()
            return JSONObject().apply {
                put("executed", false)
                put("dailyTargetMinutes", prefs.dailyTargetMinutes)
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
                put("whitelistCount", prefs.allowedPackages.size)
                put("aiAnalysisEnabled", prefs.aiAnalysisEnabled)
                put("realtimeNudgeEnabled", prefs.realtimeNudgeEnabled)
                put("nudgeIntervalMinutes", prefs.nudgeIntervalMinutes)
                put("nudgeUseAi", prefs.nudgeUseAi)
                put("aiNickname", prefs.aiNickname)
                put("aiTone", prefs.aiTone)
                put("aiFocus", JSONArray(prefs.aiFocus.toList()))
                put("aiCustomPrompt", prefs.aiCustomPrompt)
                put("changed", JSONObject())
                put("message", "已读取当前设置。")
                put("settingsUrlHint", SETTINGS_HINT)
            }
        }
    }

    // ==================================================================
    // 2) 每日目标
    // ==================================================================

    private inner class SetDailyTargetTool : AgentTool {
        override val name: String = "set_daily_target"
        override val description: String =
            "修改每日使用目标（30-1440 分钟）。仅在用户明确要求修改时调用；只是询问时用 get_app_settings。"

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
            settings.setDailyTarget(minutes)
            val after = settings.current().dailyTargetMinutes
            return result(
                executed = true,
                message = "每日使用目标已从 $before 分钟调整为 $after 分钟。",
                changed = changedOf("dailyTargetMinutes" to change(before, after)),
            )
        }
    }

    // ==================================================================
    // 3) 勿扰时段
    // ==================================================================

    private inner class SetQuietHoursTool : AgentTool {
        override val name: String = "set_quiet_hours"
        override val description: String =
            "修改免打扰时段（0-23 点，跨零点如 23→7 也支持）。仅在用户明确要求修改时调用；只是询问时用 get_app_settings。"

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
            settings.setQuietHours(start, end)
            val after = settings.current()
            return result(
                executed = true,
                message = "勿扰时段已从 ${ToolFormat.clock(before.quietHoursStart)}-${ToolFormat.clock(before.quietHoursEnd)}" +
                    " 调整为 ${ToolFormat.clock(after.quietHoursStart)}-${ToolFormat.clock(after.quietHoursEnd)}。",
                changed = changedOf(
                    "quietHoursStart" to change(before.quietHoursStart, after.quietHoursStart),
                    "quietHoursEnd" to change(before.quietHoursEnd, after.quietHoursEnd),
                ),
            )
        }
    }

    // ==================================================================
    // 4) 通知总开关
    // ==================================================================

    private inner class SetNotificationEnabledTool : AgentTool {
        override val name: String = "set_notification_enabled"
        override val description: String =
            "开关全部通知（总开关）。仅在用户明确要求修改时调用；只是询问时用 get_app_settings。"

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
            settings.setNotificationEnabled(enabled)
            val after = settings.current().notificationEnabled
            return result(
                executed = true,
                message = "通知总开关已${onOff(after)}。",
                changed = changedOf("notificationEnabled" to change(before, after)),
            )
        }
    }

    // ==================================================================
    // 5) 通知渠道（5 个可空开关）
    // ==================================================================

    private inner class SetNotificationChannelsTool : AgentTool {
        override val name: String = "set_notification_channels"
        override val description: String =
            "分别开关五类通知渠道：daily_reminder（每日提醒）、goal_exceeded（超目标）、anomaly（异常）、" +
                "daily_summary（日报摘要）、weekly_summary（周报摘要）。只更新传入的渠道，至少提供一个。" +
                "仅在用户明确要求修改时调用；只是询问时用 get_app_settings。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "daily_reminder" to JsonSchema.boolean("每日使用提醒，可选"),
                "goal_exceeded" to JsonSchema.boolean("超出每日目标的提醒，可选"),
                "anomaly" to JsonSchema.boolean("异常使用提醒，可选"),
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
                throw ToolArgumentException(
                    "至少提供一个渠道开关：${CHANNEL_KEYS.joinToString(" / ")}",
                )
            }

            val before = settings.current()
            provided.forEach { (key, enabled) -> applyChannel(key, enabled) }
            val after = settings.current()

            val changed = JSONObject()
            val parts = ArrayList<String>()
            provided.forEach { (key, _) ->
                val old = channelValue(before, key)
                val new = channelValue(after, key)
                changed.put(key, change(old, new))
                parts.add("${channelLabel(key)} ${onOff(old)}→${onOff(new)}")
            }
            return result(
                executed = true,
                message = "已更新通知渠道：" + parts.joinToString("；") + "。",
                changed = changed,
            )
        }
    }

    // ==================================================================
    // 6) 提醒风格
    // ==================================================================

    private inner class SetReminderStyleTool : AgentTool {
        override val name: String = "set_reminder_style"
        override val description: String =
            "修改本地提醒风格：gentle（温和）或 strict（严格）。仅在用户明确要求修改时调用；只是询问时用 get_app_settings。"

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
            settings.setReminderStyle(style)
            val after = settings.current().reminderStyle
            return result(
                executed = true,
                message = "提醒风格已从${reminderStyleLabel(before)}改为${reminderStyleLabel(after)}。",
                changed = changedOf("reminderStyle" to change(before, after)),
            )
        }
    }

    // ==================================================================
    // 7) 主题
    // ==================================================================

    private inner class SetThemeModeTool : AgentTool {
        override val name: String = "set_theme_mode"
        override val description: String =
            "切换主题：system（跟随系统）/ light（浅色）/ dark（深色）。仅在用户明确要求修改时调用；只是询问时用 get_app_settings。"

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
            settings.setThemeMode(mode)
            val after = settings.current().themeMode
            return result(
                executed = true,
                message = "主题已从${themeLabel(before)}改为${themeLabel(after)}。",
                changed = changedOf("themeMode" to change(before, after)),
            )
        }
    }

    // ==================================================================
    // 8) 限制模式（默认限制所有应用 / 只限制指定应用）
    // ==================================================================

    private inner class SetRestrictModeTool : AgentTool {
        override val name: String = "set_restrict_mode"
        override val description: String =
            "切换限制模式：开启=默认限制所有第三方应用（白名单除外）；关闭=只有手动指定的应用才被限制。" +
                "仅在用户明确要求修改时调用；只是询问时用 get_app_settings。"

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
            settings.setRestrictByDefault(enabled)
            val after = settings.current().restrictByDefault
            val message = if (after) {
                "已开启默认限制：所有第三方应用默认会被限制，白名单应用除外。"
            } else {
                "已关闭默认限制：只有手动指定的应用才会被限制。"
            }
            return result(
                executed = true,
                message = message,
                changed = changedOf("restrictByDefault" to change(before, after)),
            )
        }
    }

    // ==================================================================
    // 9) 实时提示（三个都可空，只改传入项）
    // ==================================================================

    private inner class SetNudgeTool : AgentTool {
        override val name: String = "set_nudge"
        override val description: String =
            "调整实时提示（陪伴模式）：enabled 开关、interval_minutes 连续使用多少分钟后提示（5-120）、" +
                "use_ai 是否用 AI 生成提示文案。三个参数都可选，只改传入项。" +
                "仅在用户明确要求修改时调用；只是询问时用 get_app_settings。"

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
                throw ToolArgumentException(
                    "至少提供一个参数：enabled / interval_minutes / use_ai",
                )
            }
            if (interval != null && interval !in 5..120) {
                throw ToolArgumentException("interval_minutes 需在 5-120 之间，收到 $interval")
            }

            val before = settings.current()
            enabled?.let { settings.setRealtimeNudgeEnabled(it) }
            interval?.let { settings.setNudgeIntervalMinutes(it) }
            useAi?.let { settings.setNudgeUseAi(it) }
            val after = settings.current()

            val changed = JSONObject()
            if (enabled != null) {
                changed.put("realtimeNudgeEnabled", change(before.realtimeNudgeEnabled, after.realtimeNudgeEnabled))
            }
            if (interval != null) {
                changed.put("nudgeIntervalMinutes", change(before.nudgeIntervalMinutes, after.nudgeIntervalMinutes))
            }
            if (useAi != null) {
                changed.put("nudgeUseAi", change(before.nudgeUseAi, after.nudgeUseAi))
            }
            val message = "实时提示已更新：" +
                "${onOff(after.realtimeNudgeEnabled)}，" +
                "每 ${after.nudgeIntervalMinutes} 分钟提示一次，" +
                "文案${if (after.nudgeUseAi) "使用 AI 生成" else "使用本地文案"}。"
            return result(executed = true, message = message, changed = changed)
        }
    }

    // ==================================================================
    // 10) AI 个性化
    // ==================================================================

    private inner class SetAiPersonaTool : AgentTool {
        override val name: String = "set_ai_persona"
        override val description: String =
            "调整 AI 助手个性化：nickname 称呼（空串=清空）、tone 语气（gentle / direct / strict）、" +
                "focus 关注点数组（sleep / study / entertainment / efficiency，空数组=清空）、" +
                "custom_prompt 自定义要求（空串=清空）。参数都可选，只改传入项。" +
                "仅在用户明确要求修改时调用；只是询问时用 get_app_settings。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "nickname" to JsonSchema.string("AI 对你的称呼；传空串表示清空，可选"),
                "tone" to JsonSchema.string("AI 语气", enum = listOf("gentle", "direct", "strict")),
                "focus" to JsonSchema.stringArray(
                    "关注点数组，可选",
                    enum = FOCUS_KEYS,
                ),
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
                throw ToolArgumentException(
                    "至少提供一个参数：nickname / tone / focus / custom_prompt",
                )
            }
            val tone = toneRaw?.let {
                requireEnum(it, listOf("gentle", "direct", "strict"), "tone")
            }
            focus?.forEach { requireEnum(it, FOCUS_KEYS, "focus[]") }

            val before = settings.current()
            val newNickname = if (nicknameProvided) nickname.orEmpty() else before.aiNickname
            val newTone = tone ?: before.aiTone
            val newFocus = focus?.toSet() ?: before.aiFocus
            val newCustom = if (customProvided) custom.orEmpty() else before.aiCustomPrompt
            settings.setAiPersona(newNickname, newTone, newFocus, newCustom)
            val after = settings.current()

            val changed = JSONObject()
            val parts = ArrayList<String>()
            if (nicknameProvided) {
                changed.put("aiNickname", change(before.aiNickname, after.aiNickname))
                parts.add(
                    if (after.aiNickname.isEmpty()) "已清空称呼" else "称呼改为「${after.aiNickname}」",
                )
            }
            if (tone != null) {
                changed.put("aiTone", change(before.aiTone, after.aiTone))
                parts.add("语气${toneLabel(before.aiTone)}→${toneLabel(after.aiTone)}")
            }
            if (focus != null) {
                changed.put(
                    "aiFocus",
                    change(JSONArray(before.aiFocus.toList()), JSONArray(after.aiFocus.toList())),
                )
                parts.add(
                    if (after.aiFocus.isEmpty()) {
                        "已清空关注点"
                    } else {
                        "关注点改为" + focusText(after.aiFocus)
                    },
                )
            }
            if (customProvided) {
                changed.put("aiCustomPrompt", change(before.aiCustomPrompt, after.aiCustomPrompt))
                parts.add(if (after.aiCustomPrompt.isEmpty()) "已清空自定义要求" else "已更新自定义要求")
            }
            return result(
                executed = true,
                message = "AI 个性化已更新：" + parts.joinToString("；") + "。",
                changed = changed,
            )
        }
    }

    // ==================================================================
    // 11) 应用限制（真正 block / unblock）
    // ==================================================================

    private inner class SetAppRestrictionTool : AgentTool {
        override val name: String = "set_app_restriction"
        override val description: String =
            "真正限制或放行某个应用（写入限制列表，由无障碍服务执行拦截）。系统保护应用（电话/短信/设置/桌面/本应用）会被拒绝。" +
                "若应用在白名单中且要限制它，会先把它移出白名单。" +
                "仅在用户明确要求限制/放行某个应用时调用；只是询问时不要调用。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "package_name" to JsonSchema.string("应用包名或应用名，例如 com.tencent.mm / 微信"),
                "restricted" to JsonSchema.boolean("true=限制该应用；false=解除限制"),
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
            var removedFromWhitelist = false

            if (restricted) {
                // 白名单与限制冲突：先移出白名单再限制（否则白名单会覆盖限制）
                if (whitelistedBefore) {
                    settings.setAllowed(packageName, false)
                    removedFromWhitelist = true
                }
                restrictions.block(packageName)
            } else {
                restrictions.unblock(packageName)
            }

            val nowBlocked = restrictions.isBlocked(packageName)
            val whitelistedAfter = settings.current().allowedPackages.contains(packageName)

            val changed = JSONObject().apply {
                put("restricted", change(wasBlocked, nowBlocked))
                if (removedFromWhitelist) {
                    put("whitelisted", change(true, whitelistedAfter))
                }
            }
            val message = buildString {
                if (restricted) {
                    append("已限制「").append(appName).append("」")
                } else {
                    append("已解除对「").append(appName).append("」的限制")
                }
                if (removedFromWhitelist) {
                    append("；并已将它移出白名单（白名单应用不会被限制，冲突时以「限制」为准）")
                }
                append("。实际拦截需要无障碍服务处于开启状态。")
            }
            return result(
                executed = true,
                message = message,
                changed = changed,
                settingsUrlHint = APPS_HINT,
            ).apply {
                put("packageName", packageName)
                put("appName", appName)
                put("restricted", nowBlocked)
                put("whitelisted", whitelistedAfter)
                put("removedFromWhitelist", removedFromWhitelist)
            }
        }
    }

    // ==================================================================
    // 12) AI 分析开关
    // ==================================================================

    private inner class SetAiAnalysisEnabledTool : AgentTool {
        override val name: String = "set_ai_analysis_enabled"
        override val description: String =
            "开关 AI 分析：关闭后日报/周报只用本地规则，不消耗 token。仅在用户明确要求修改时调用；只是询问时用 get_app_settings。"

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
            settings.setAiAnalysisEnabled(enabled)
            val after = settings.current().aiAnalysisEnabled
            val message = if (after) {
                "已开启 AI 分析：日报/周报会调用模型生成。"
            } else {
                "已关闭 AI 分析：日报/周报只用本地规则，不再消耗 token。"
            }
            return result(
                executed = true,
                message = message,
                changed = changedOf("aiAnalysisEnabled" to change(before, after)),
            )
        }
    }

    // ==================================================================
    // 13) 刷新使用数据
    // ==================================================================

    private inner class RefreshUsageDataTool : AgentTool {
        override val name: String = "refresh_usage_data"
        override val description: String =
            "立即同步一次手机使用数据（读取系统 UsageEvents 并更新统计）。" +
                "仅在用户明确要求刷新/同步数据时调用；只是询问数据时用 get_daily_usage 等查询工具。"

        override val parameters: JSONObject = JsonSchema.objectSchema(properties = emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            if (!usage.hasUsageAccess()) {
                return result(
                    executed = false,
                    message = "尚未获得「使用情况访问」权限，无法同步数据；请先到系统设置授权后再试。",
                    settingsUrlHint = SETTINGS_HINT,
                ).apply {
                    put("ok", false)
                    put("sessions", 0)
                }
            }
            val ingest = usage.refresh()
            return result(
                executed = true,
                message = "已同步使用数据：本次处理 ${ingest.sessions} 段会话，覆盖 ${ingest.daysTouched} 天。",
                settingsUrlHint = DASHBOARD_HINT,
            ).apply {
                put("ok", ingest.hasAccess)
                put("sessions", ingest.sessions)
                put("daysTouched", ingest.daysTouched)
            }
        }
    }

    // ==================================================================
    // 14) 立即生成报告
    // ==================================================================

    private inner class GenerateReportNowTool : AgentTool {
        override val name: String = "generate_report_now"
        override val description: String =
            "立即生成一份使用报告（daily=日报，weekly=周报）并保存，来源可能是 AI 或本地规则。" +
                "仅在用户明确要求现在生成报告时调用；只是查看已有报告时不要调用。"

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
    // 15) 测试通知
    // ==================================================================

    private inner class SendTestNotificationTool : AgentTool {
        override val name: String = "send_test_notification"
        override val description: String =
            "发送一条系统测试通知，用于验证通知权限与渠道是否正常。" +
                "仅在用户明确要求发送测试通知时调用；只是询问通知设置时用 get_app_settings。"

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
    // 通知实现（NotificationHelper 没有测试入口，这里只用它的公开 helper 做权限/渠道，
    // 再直接 post 一条测试通知；避免被「日报渠道关闭 / 免打扰 / 10 分钟去重」拦截导致测试失败）
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
        val body = "如果你看到这条通知，说明通知功能正常：AI 助手可以按你的要求调控本 App 并给你发通知。"
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

    private suspend fun applyChannel(key: String, enabled: Boolean) {
        when (key) {
            "daily_reminder" -> settings.setNotifyDailyReminder(enabled)
            "goal_exceeded" -> settings.setNotifyGoalExceeded(enabled)
            "anomaly" -> settings.setNotifyAnomaly(enabled)
            "daily_summary" -> settings.setNotifyDailySummary(enabled)
            "weekly_summary" -> settings.setNotifyWeeklySummary(enabled)
            else -> throw ToolArgumentException("未知通知渠道：$key")
        }
    }

    private fun channelValue(prefs: AppSettings, key: String): Boolean = when (key) {
        "daily_reminder" -> prefs.notifyDailyReminder
        "goal_exceeded" -> prefs.notifyGoalExceeded
        "anomaly" -> prefs.notifyAnomaly
        "daily_summary" -> prefs.notifyDailySummary
        "weekly_summary" -> prefs.notifyWeeklySummary
        else -> false
    }

    private fun channelLabel(key: String): String = when (key) {
        "daily_reminder" -> "每日提醒"
        "goal_exceeded" -> "超目标提醒"
        "anomaly" -> "异常提醒"
        "daily_summary" -> "日报摘要"
        "weekly_summary" -> "周报摘要"
        else -> key
    }
}


// ======================================================================
// 文件级常量
// 放在类外（而不是 companion object）是为了让 inner class 里也能无歧义解析，
// 同时避免 companion 初始化顺序带来的坑。
// ======================================================================

private const val SETTINGS_HINT = "可在「设置」页查看"
private const val APPS_HINT = "可在「应用」页查看暂停状态"
private const val REPORT_HINT = "可在「报告」页查看"
private const val DASHBOARD_HINT = "可在「首页」查看最新数据"

/** 测试通知 id，避开 NotificationHelper 的 1001-1005 固定 id */
private const val TEST_NOTIFICATION_ID = 1099

private val CHANNEL_KEYS = listOf(
    "daily_reminder",
    "goal_exceeded",
    "anomaly",
    "daily_summary",
    "weekly_summary",
)

private val FOCUS_KEYS = listOf("sleep", "study", "entertainment", "efficiency")
