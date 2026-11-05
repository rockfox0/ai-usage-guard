package com.aid.usageguard.domain.action

import com.aid.usageguard.data.db.PendingActionEntity
import com.aid.usageguard.domain.quota.FocusWindow
import com.aid.usageguard.data.repo.ActionDraftRepository
import com.aid.usageguard.data.repo.QuotaRepository
import com.aid.usageguard.data.repo.WhitelistRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.restriction.AppRestrictionController
import com.aid.usageguard.restriction.RestrictionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 本地执行器：只在用户确认草案之后执行（v1.7 方案 6.2/6.3）。
 *
 * - 参数全部来自已保存的草案，不重新让模型生成；
 * - 以 opId 去重：已确认/已执行的草案不会重复执行；
 * - 执行后重新读取实际状态（设置重新读取、限制状态按有效策略核对），失败如实返回。
 */
class ActionExecutor(
    private val settings: SettingsRepository,
    private val restrictions: AppRestrictionController,
    private val usage: UsageRepository,
    private val quota: QuotaRepository,
    private val whitelist: WhitelistRepository,
    /** v1.9 Phase 5：自定义监控规则落库 */
    private val monitors: com.aid.usageguard.data.repo.MonitorRepository,
) {

    data class Result(
        val success: Boolean,
        val message: String,
        val resultJson: String? = null,
        val error: String? = null,
    )

    suspend fun execute(action: PendingActionEntity): Result = withContext(Dispatchers.IO) {
        if (action.status != ActionDraftRepository.STATUS_CONFIRMED) {
            return@withContext Result(
                success = false,
                message = "该操作未确认或已处理，未执行",
                error = "invalid_status:${action.status}",
            )
        }
        runCatching {
            when (action.type) {
                "SET_DAILY_TARGET" -> {
                    val minutes = params(action).getInt("minutes")
                    settings.setDailyTarget(minutes)
                    val now = settings.current().dailyTargetMinutes
                    Result(now == minutes, "每日目标已设为 $now 分钟", JSONObject().put("dailyTargetMinutes", now).toString())
                }

                "SET_QUIET_HOURS" -> {
                    val start = params(action).getInt("start_hour")
                    val end = params(action).getInt("end_hour")
                    settings.setQuietHours(start, end)
                    val now = settings.current()
                    Result(
                        now.quietHoursStart == start && now.quietHoursEnd == end,
                        "免打扰时段已设为 %02d:00-%02d:00".format(now.quietHoursStart, now.quietHoursEnd),
                        JSONObject().put("start", now.quietHoursStart).put("end", now.quietHoursEnd).toString(),
                    )
                }

                "SET_NOTIFICATION_ENABLED" -> {
                    val enabled = params(action).getBoolean("enabled")
                    settings.setNotificationEnabled(enabled)
                    val now = settings.current().notificationEnabled
                    Result(now == enabled, if (now) "通知已开启" else "通知已关闭", JSONObject().put("enabled", now).toString())
                }

                "SET_NOTIFICATION_CHANNELS" -> {
                    val p = params(action)
                    p.optBooleanOrNull("daily_reminder")?.let { settings.setNotifyDailyReminder(it) }
                    p.optBooleanOrNull("goal_exceeded")?.let { settings.setNotifyGoalExceeded(it) }
                    p.optBooleanOrNull("anomaly")?.let { settings.setNotifyAnomaly(it) }
                    p.optBooleanOrNull("proactive")?.let { settings.setNotifyProactive(it) }
                    p.optBooleanOrNull("daily_summary")?.let { settings.setNotifyDailySummary(it) }
                    p.optBooleanOrNull("weekly_summary")?.let { settings.setNotifyWeeklySummary(it) }
                    Result(true, "通知渠道设置已更新")
                }

                "SET_REMINDER_STYLE" -> {
                    val style = params(action).getString("style")
                    settings.setReminderStyle(style)
                    Result(true, "提醒风格已设为 $style")
                }

                "SET_THEME_MODE" -> {
                    val mode = params(action).getString("mode")
                    settings.setThemeMode(mode)
                    val now = settings.current().themeMode
                    Result(now == mode, "主题已设为 $now")
                }

                "SET_RESTRICTION_MODE" -> {
                    val mode = RestrictionMode.parse(params(action).optString("mode"))
                    settings.setRestrictionMode(mode)
                    val now = RestrictionMode.parse(settings.current().restrictionMode)
                    Result(
                        now == mode,
                        "限制模式已设为「${now.label}」：${now.description}",
                        JSONObject().put("mode", now.name).toString(),
                    )
                }

                "SET_RESTRICT_MODE" -> {
                    val enabled = params(action).getBoolean("enabled")
                    settings.setRestrictByDefault(enabled)
                    val now = settings.current().restrictByDefault
                    Result(now == enabled, if (now) "默认限制模式已开启" else "默认限制模式已关闭")
                }

                "SET_NUDGE" -> {
                    val p = params(action)
                    p.optBooleanOrNull("enabled")?.let { settings.setRealtimeNudgeEnabled(it) }
                    if (p.has("interval_minutes")) settings.setNudgeIntervalMinutes(p.getInt("interval_minutes"))
                    p.optBooleanOrNull("use_ai")?.let { settings.setNudgeUseAi(it) }
                    val now = settings.current()
                    Result(
                        true,
                        "实时提示：${if (now.realtimeNudgeEnabled) "开" else "关"}，间隔 ${now.nudgeIntervalMinutes} 分钟",
                    )
                }

                "SET_AI_PERSONA" -> {
                    val p = params(action)
                    val current = settings.current()
                    settings.setAiPersona(
                        nickname = if (p.has("nickname")) p.getString("nickname") else current.aiNickname,
                        tone = if (p.has("tone")) p.getString("tone") else current.aiTone,
                        focus = if (p.has("focus")) p.getJSONArray("focus").toStringSet() else current.aiFocus,
                        customPrompt = if (p.has("custom_prompt")) p.getString("custom_prompt") else current.aiCustomPrompt,
                    )
                    Result(true, "AI 个性化已更新")
                }

                "SET_AI_ANALYSIS_ENABLED" -> {
                    val enabled = params(action).getBoolean("enabled")
                    settings.setAiAnalysisEnabled(enabled)
                    Result(true, if (enabled) "AI 分析已开启" else "AI 分析已关闭")
                }

                "SET_WHITELIST" -> {
                    val pkg = params(action).getString("package_name")
                    val allowed = params(action).getBoolean("allowed")
                    if (!allowed) {
                        whitelist.remove(pkg)
                        val now = pkg in settings.current().allowedPackages
                        Result(!now, "$pkg 已移出允许名单")
                    } else {
                        when (val added = whitelist.add(pkg)) {
                            is WhitelistRepository.AddResult.Added -> Result(
                                true,
                                "$pkg 已加入允许名单（${added.quota.describe()}）",
                            )

                            WhitelistRepository.AddResult.AlreadyAllowed ->
                                Result(true, "$pkg 已在允许名单中")

                            is WhitelistRepository.AddResult.Rejected ->
                                Result(false, added.reason, error = "whitelist_daily_limit")
                        }
                    }
                }

                "SET_APP_RESTRICTION" -> {
                    val p = params(action)
                    val pkg = p.getString("package_name")
                    val restricted = p.getBoolean("restricted")
                    if (restricted) {
                        // 限制：移出白名单并写入手动限制（与有效策略一致）
                        settings.setAllowed(pkg, false)
                        restrictions.block(pkg)
                        val blocked = restrictions.isBlocked(pkg)
                        Result(blocked, "$pkg 已限制", JSONObject().put("restricted", blocked).toString())
                    } else {
                        // 放行也要占白名单每日额度
                        when (val added = whitelist.add(pkg)) {
                            is WhitelistRepository.AddResult.Rejected ->
                                Result(false, added.reason, error = "whitelist_daily_limit")

                            else -> {
                                restrictions.unblock(pkg)
                                val blocked = restrictions.isBlocked(pkg)
                                Result(
                                    !blocked,
                                    "$pkg 已放行（加入允许名单并清除手动限制）",
                                    JSONObject().put("restricted", blocked).toString(),
                                )
                            }
                        }
                    }
                }

                "SET_FOCUS_WINDOW" -> {
                    val p = params(action)
                    val prefs = settings.current()
                    val enabled = if (p.has("enabled")) p.getBoolean("enabled") else prefs.focusWindowEnabled
                    val start = if (p.has("start_hour")) p.getInt("start_hour") else prefs.focusWindowStart
                    val end = if (p.has("end_hour")) p.getInt("end_hour") else prefs.focusWindowEnd
                    settings.setFocusWindow(enabled, start, end)
                    val now = settings.current()
                    Result(
                        now.focusWindowEnabled == enabled &&
                            now.focusWindowStart == start && now.focusWindowEnd == end,
                        if (now.focusWindowEnabled) {
                            "专注时段已设为 ${FocusWindow.label(now.focusWindowStart, now.focusWindowEnd)}（只能使用白名单应用）"
                        } else {
                            "专注时段已关闭"
                        },
                    )
                }

                "SET_WHITELIST_DAILY_LIMIT" -> {
                    val limit = params(action).getInt("limit")
                    whitelist.setDailyLimit(limit)
                    val now = settings.current().whitelistDailyLimit
                    Result(
                        now == limit.coerceIn(0, 20),
                        if (now == 0) "白名单每日添加上限已关闭（不限次数）" else "每天最多添加 $now 个应用到白名单",
                    )
                }

                "SET_APP_DAILY_LIMIT" -> {
                    val p = params(action)
                    val pkg = p.getString("package_name")
                    val minutes = p.optInt("minutes", 0)
                    quota.setLimit(pkg, minutes)
                    val now = quota.limitOf(pkg)
                    Result(
                        now == (minutes.takeIf { it > 0 }),
                        if (now == null) "$pkg 已取消每日限额" else "$pkg 每日限额已设为 $now 分钟",
                        JSONObject().put("packageName", pkg).put("minutes", now ?: 0).toString(),
                    )
                }

                "SET_DAILY_BUDGET_ENFORCED" -> {
                    val enabled = params(action).getBoolean("enabled")
                    settings.setDailyBudgetEnforced(enabled)
                    val now = settings.current().dailyBudgetEnforced
                    Result(now == enabled, if (now) "每日额度超限拦截已开启" else "每日额度超限拦截已关闭")
                }

                "REFRESH_USAGE_DATA" -> {
                    val result = usage.refresh()
                    Result(result.hasAccess, "已同步使用数据：${result.sessions} 个会话 / ${result.daysTouched} 天")
                }

                "CREATE_MONITOR" -> {
                    val p = params(action)
                    val draft = com.aid.usageguard.domain.monitor.MonitorDefinition(
                        id = "",
                        name = p.optString("name").ifBlank { "自定义监控" },
                        packageName = p.optString("package_name").takeIf { it.isNotBlank() },
                        metric = runCatching {
                            com.aid.usageguard.domain.monitor.MonitorMetric.valueOf(p.getString("metric"))
                        }.getOrElse { com.aid.usageguard.domain.monitor.MonitorMetric.FOREGROUND_DURATION },
                        operator = runCatching {
                            com.aid.usageguard.domain.monitor.MonitorOperator.valueOf(p.getString("operator"))
                        }.getOrElse { com.aid.usageguard.domain.monitor.MonitorOperator.GREATER_THAN },
                        threshold = p.optLong("threshold"),
                        schedule = runCatching {
                            com.aid.usageguard.domain.monitor.MonitorSchedule.valueOf(
                                p.optString("schedule", "EVERY_DAY"),
                            )
                        }.getOrElse { com.aid.usageguard.domain.monitor.MonitorSchedule.EVERY_DAY },
                        action = com.aid.usageguard.domain.monitor.MonitorAction.NOTIFY,
                        timeWindow = p.optString("time_window", "DAILY"),
                    )
                    val existing = monitors.all()
                    when (val v = com.aid.usageguard.domain.monitor.MonitorValidator.validate(draft, existing)) {
                        is com.aid.usageguard.domain.monitor.MonitorValidator.Result.Rejected ->
                            Result(false, v.reason, error = "monitor_invalid")

                        com.aid.usageguard.domain.monitor.MonitorValidator.Result.Ok -> {
                            val saved = monitors.create(draft)
                            Result(
                                true,
                                "监控规则已创建：${com.aid.usageguard.domain.monitor.MonitorValidator.describe(saved)}",
                                JSONObject().put("ruleId", saved.id).toString(),
                            )
                        }
                    }
                }

                "DELETE_MONITOR" -> {
                    val ruleId = params(action).optString("rule_id")
                    if (ruleId.isBlank()) {
                        Result(false, "缺少 rule_id", error = "monitor_invalid")
                    } else {
                        monitors.delete(ruleId)
                        Result(true, "监控规则已删除", JSONObject().put("ruleId", ruleId).toString())
                    }
                }

                else -> Result(false, "不支持的操作类型：${action.type}", error = "unsupported_type")
            }
        }.getOrElse { e ->
            Result(false, "执行失败：${e.message ?: e.javaClass.simpleName}", error = e.message)
        }
    }

    private fun params(action: PendingActionEntity): JSONObject =
        runCatching { JSONObject(action.paramsJson) }.getOrDefault(JSONObject())

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    private fun org.json.JSONArray.toStringSet(): Set<String> =
        (0 until length()).mapNotNull { runCatching { getString(it) }.getOrNull() }.toSet()
}
