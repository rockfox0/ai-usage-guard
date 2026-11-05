package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.ActionDraftRepository
import com.aid.usageguard.data.repo.QuotaRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.repo.WhitelistRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.quota.FocusWindow
import com.aid.usageguard.domain.quota.QuotaPolicy
import com.aid.usageguard.restriction.RuleValidator
import org.json.JSONArray
import org.json.JSONObject

/**
 * 时长额度工具组（v1.8）：
 * - `set_app_daily_limit`：单应用每日限额（PROPOSE，生成待确认草案）；
 * - `set_daily_budget_enforced`：全局每日额度超限拦截开关（PROPOSE，生成待确认草案）；
 * - `get_quota_status`：额度/限额/今日已用查询（QUERY，只读，报告与后台域也可用）。
 *
 * 规则与 GuardService/QuotaPolicy 保持一致：
 * 白名单应用不受全局额度限制；单应用限额是显式设置，优先于白名单；系统保护应用永不受限。
 */
class QuotaTools(
    private val settings: SettingsRepository,
    private val source: UsageEventSource,
    private val usage: UsageRepository,
    /** 额度仓储（延迟取 Container，避免 AppWiring 增加注入参数） */
    private val quotaProvider: () -> QuotaRepository = { Container.quota },
    private val actionDraftsProvider: () -> ActionDraftRepository = { Container.actionDrafts },
    /** 当前任务 id，由 AgentLoop 注入 */
    private val taskIdProvider: () -> String = { "" },
    /** 白名单仓储（只读每日添加上限与今日已添加），默认延迟取 Container */
    private val whitelistProvider: () -> WhitelistRepository = { Container.whitelist },
) {

    val all: List<AgentTool> = listOf(
        SetAppDailyLimitTool(),
        SetDailyBudgetEnforcedTool(),
        SetRestrictionModeTool(),
        GetQuotaStatusTool(),
    )

    // ==================================================================
    // 1) 单应用每日限额（PROPOSE → SET_APP_DAILY_LIMIT）
    // ==================================================================

    private inner class SetAppDailyLimitTool : AgentTool {
        override val name: String = "set_app_daily_limit"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成单应用每日限额草案（0-1440 分钟，0=取消）；超限单独拦该应用，优先于白名单。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "package_name" to JsonSchema.string(
                    "应用包名或应用名，例如 com.tencent.mm / 微信",
                    format = "app_ref",
                ),
                "minutes" to JsonSchema.integer(
                    "每日允许使用的分钟数，0-1440；0 表示取消限额",
                    minimum = 0,
                    maximum = 1440,
                ),
            ),
            required = listOf("package_name", "minutes"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val input = arguments.stringArg("package_name")
                ?: throw ToolArgumentException("缺少必填参数 package_name（包名或应用名）")
            val minutes = arguments.intArg("minutes")
                ?: throw ToolArgumentException("缺少必填参数 minutes（0-1440 的整数，0 表示取消限额）")
            if (minutes !in 0..1440) {
                throw ToolArgumentException("minutes 需在 0-1440 之间，收到 $minutes")
            }

            val packageName = resolvePackageName(usage, source, input)
                ?: throw ToolArgumentException(
                    "没有找到应用「$input」。可以先用 get_usage_ranking 或 get_whitelist 查准确的包名。",
                )
            val appName = source.appNameOf(packageName) ?: packageName

            val launcherPackages = runCatching { source.launcherPackages() }.getOrDefault(emptySet())
            if (RuleValidator.isProtected(packageName, launcherPackages)) {
                return result(
                    executed = false,
                    message = "「$appName」是系统保护应用（电话/短信/系统设置/桌面/本应用等），不参与每日限额。",
                    settingsUrlHint = APPS_HINT,
                ).apply {
                    put("packageName", packageName)
                    put("appName", appName)
                    put("rejectedReason", "系统保护应用不允许设置每日限额")
                }
            }

            val quota = runCatching { quotaProvider() }.getOrElse {
                throw ToolArgumentException("额度服务尚未就绪，请稍后再试。")
            }
            val beforeLimit = runCatching { quota.limitOf(packageName) }.getOrNull()

            val params = JSONObject()
                .put("package_name", packageName)
                .put("minutes", minutes)
            val beforeJson = JSONObject()
                .put("package_name", packageName)
                .put("minutes", beforeLimit ?: JSONObject.NULL)
            val afterJson = JSONObject()
                .put("package_name", packageName)
                .put("minutes", minutes)
            val changed = JSONObject().put(
                "minutes",
                JSONObject().apply {
                    put("from", beforeLimit ?: JSONObject.NULL)
                    put("to", minutes)
                },
            )
            val message = when {
                beforeLimit == null && minutes == 0 ->
                    "「$appName」本来就没有每日限额；仍会生成一条「清除限额」草案，确认后保持不限时。"

                beforeLimit == null ->
                    "「$appName」每日限额：不限时 → $minutes 分钟。"

                minutes == 0 ->
                    "「$appName」每日限额：$beforeLimit 分钟 → 不限时（清除限额）。"

                else ->
                    "「$appName」每日限额：$beforeLimit 分钟 → $minutes 分钟。"
            }

            return proposeDraft(
                type = "SET_APP_DAILY_LIMIT",
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
                put("limitMinutes", minutes)
                put("previousLimitMinutes", beforeLimit ?: JSONObject.NULL)
            }
        }
    }

    // ==================================================================
    // 2) 全局每日额度超限拦截开关（PROPOSE → SET_DAILY_BUDGET_ENFORCED）
    // ==================================================================

    private inner class SetDailyBudgetEnforcedTool : AgentTool {
        override val name: String = "set_daily_budget_enforced"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成每日额度超限拦截开关草案：开启后今日总时长超额度即拦非白名单应用。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "enabled" to JsonSchema.boolean("true=开启超限拦截；false=关闭超限拦截"),
            ),
            required = listOf("enabled"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val enabled = arguments.boolArg("enabled")
                ?: throw ToolArgumentException("缺少必填参数 enabled（true / false）")

            val prefs = settings.current()
            val before = prefs.dailyBudgetEnforced
            val budget = prefs.dailyTargetMinutes

            val params = JSONObject().put("enabled", enabled)
            val beforeJson = JSONObject().put("enabled", before)
            val afterJson = JSONObject().put("enabled", enabled)
            val changed = JSONObject().put(
                "dailyBudgetEnforced",
                JSONObject().apply {
                    put("from", before)
                    put("to", enabled)
                },
            )

            return proposeDraft(
                type = "SET_DAILY_BUDGET_ENFORCED",
                params = params,
                before = beforeJson,
                after = afterJson,
                changed = changed,
                message = if (enabled) {
                    "开启每日额度拦截：今日总时长达到 $budget 分钟（每日目标）后，" +
                        "除白名单外的所有应用都会被拦截。"
                } else {
                    "关闭每日额度拦截：今日总时长超过 $budget 分钟也不会再拦截（单应用每日限额仍然生效）。"
                },
                settingsUrlHint = SETTINGS_HINT,
            ).apply {
                put("dailyBudgetMinutes", budget)
            }
        }
    }


    // ==================================================================
    // 2.5) 限制模式（PROPOSE → SET_RESTRICTION_MODE）
    // ==================================================================

    private inner class SetRestrictionModeTool : AgentTool {
        override val name: String = "set_restriction_mode"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成限制模式草案：QUOTA_AFTER_BUDGET(额度用满后只留白名单)/ALWAYS_NON_WHITELIST/MANUAL_ONLY。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "mode" to JsonSchema.string(
                    "限制模式：QUOTA_AFTER_BUDGET / ALWAYS_NON_WHITELIST / MANUAL_ONLY",
                    enum = listOf("QUOTA_AFTER_BUDGET", "ALWAYS_NON_WHITELIST", "MANUAL_ONLY"),
                ),
            ),
            required = listOf("mode"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val raw = arguments.stringArg("mode")?.trim()?.uppercase()
                ?: throw ToolArgumentException("缺少必填参数 mode")
            val mode = com.aid.usageguard.restriction.RestrictionMode.parse(raw)
            val prefs = settings.current()
            val before = com.aid.usageguard.restriction.RestrictionMode.parse(prefs.restrictionMode)
            if (before == mode) {
                return result(
                    executed = false,
                    message = "当前已经是「${mode.label}」模式，无需修改。",
                    changed = JSONObject(),
                    settingsUrlHint = SETTINGS_HINT,
                )
            }
            val changed = JSONObject().put(
                "restrictionMode",
                JSONObject().apply {
                    put("from", before.name)
                    put("to", mode.name)
                },
            )
            return proposeDraft(
                type = "SET_RESTRICTION_MODE",
                params = JSONObject().put("mode", mode.name),
                before = JSONObject().put("mode", before.name),
                after = JSONObject().put("mode", mode.name),
                changed = changed,
                message = "限制模式：${before.label} → ${mode.label}（${mode.description}）。",
                settingsUrlHint = SETTINGS_HINT,
            ).apply {
                put("mode", mode.name)
            }
        }
    }

    // ==================================================================
    // 3) 额度状态查询（QUERY，报告/后台域也可用）
    // ==================================================================

    private inner class GetQuotaStatusTool : AgentTool {
        override val name: String = "get_quota_status"
        override val description: String =
            "查询今日额度：已用/剩余、是否启用超限拦截、各单应用限额与超限情况、专注时段、白名单每日额度。" +
                "只读；回答「还剩多少额度 / 哪个应用快超了」用它。"

        override val parameters: JSONObject = JsonSchema.objectSchema(properties = emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val prefs = settings.current()
            val quota = runCatching { quotaProvider() }.getOrElse {
                throw ToolArgumentException("额度服务尚未就绪，请稍后再试。")
            }
            val snapshot = runCatching { quota.todayUsage() }.getOrElse {
                throw ToolArgumentException("读取今日使用数据失败：${it.message?.take(120) ?: "未知错误"}")
            }
            val limits = runCatching { quota.limits() }.getOrElse {
                throw ToolArgumentException("读取应用限额失败：${it.message?.take(120) ?: "未知错误"}")
            }

            val budget = prefs.dailyTargetMinutes
            val totalUsed = snapshot.totalMinutes
            val remaining = QuotaPolicy.remainingMinutes(budget, totalUsed)
            val enforced = prefs.dailyBudgetEnforced
            val globalExceeded = enforced && budget > 0 && totalUsed >= budget

            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val focusActive = prefs.focusWindowEnabled &&
                FocusWindow.isActive(hour, prefs.focusWindowStart, prefs.focusWindowEnd)
            val whitelistQuota = runCatching { whitelistProvider().quota() }.getOrNull()

            val rows = limits.entries.map { (packageName, limitMinutes) ->
                val usedMinutes = snapshot.perAppMinutes[packageName] ?: 0L
                LimitRow(
                    packageName = packageName,
                    appName = source.appNameOf(packageName) ?: packageName,
                    limitMinutes = limitMinutes,
                    usedMinutes = usedMinutes,
                    remainingMinutes = QuotaPolicy.appRemainingMinutes(limitMinutes, usedMinutes) ?: 0L,
                    exceeded = limitMinutes > 0 && usedMinutes >= limitMinutes,
                )
            }.sortedWith(
                compareByDescending<LimitRow> { it.exceeded }
                    .thenByDescending { it.usedMinutes }
                    .thenBy { it.packageName },
            )

            val limitArray = JSONArray()
            rows.forEach { row ->
                limitArray.put(
                    JSONObject().apply {
                        put("packageName", row.packageName)
                        put("appName", row.appName)
                        put("limitMinutes", row.limitMinutes)
                        put("usedMinutes", row.usedMinutes)
                        put("remainingMinutes", row.remainingMinutes)
                        put("exceeded", row.exceeded)
                    },
                )
            }

            val exceededNames = rows.filter { it.exceeded }.map { it.appName }
            return JSONObject().apply {
                put("executed", false)
                put("dailyBudgetMinutes", budget)
                put("enforced", enforced)
                put("todayTotalMinutes", totalUsed)
                put("remainingMinutes", remaining)
                put("whitelistedCount", prefs.allowedPackages.size)
                put("limits", limitArray)
                put("globalExceeded", globalExceeded)
                put("focusWindow", JSONObject().apply {
                    put("enabled", prefs.focusWindowEnabled)
                    put("start_hour", prefs.focusWindowStart)
                    put("end_hour", prefs.focusWindowEnd)
                    put("text", FocusWindow.label(prefs.focusWindowStart, prefs.focusWindowEnd))
                    put("active_now", focusActive)
                })
                put("whitelistQuota", JSONObject().apply {
                    put("limit", whitelistQuota?.limit ?: prefs.whitelistDailyLimit)
                    put("used", whitelistQuota?.used ?: prefs.whitelistAddCount)
                    put(
                        "remaining",
                        whitelistQuota?.let { if (it.unlimited) -1 else it.remaining } ?: -1,
                    )
                    put("unlimited", whitelistQuota?.unlimited ?: (prefs.whitelistDailyLimit == 0))
                    put("canAdd", whitelistQuota?.canAdd ?: true)
                })
                put(
                    "message",
                    buildString {
                        append("今日已用 ").append(totalUsed).append(" 分钟，")
                        append("每日额度 ").append(budget).append(" 分钟（剩余 ").append(remaining).append(" 分钟）。")
                        if (budget <= 0) {
                            append("当前每日额度为 0，等同于不启用全局额度拦截。")
                        } else if (enforced) {
                            append(if (globalExceeded) "已达到/超过每日额度，非白名单应用会被拦截。" else "每日额度拦截已开启。")
                        } else {
                            append("每日额度拦截未开启（超过额度也不会拦截）。")
                        }
                        when {
                            exceededNames.isNotEmpty() ->
                                append("已超单应用限额：").append(exceededNames.joinToString("、")).append("。")

                            rows.isNotEmpty() ->
                                append("已设 ").append(rows.size).append(" 个单应用限额，目前均未超限。")

                            else ->
                                append("当前没有设置单应用限额。")
                        }
                        append("白名单 ").append(prefs.allowedPackages.size)
                        append(" 个应用不受全局额度限制；单应用限额优先于白名单。")
                        if (prefs.focusWindowEnabled) {
                            append("专注时段 ")
                            append(FocusWindow.label(prefs.focusWindowStart, prefs.focusWindowEnd))
                            append(if (focusActive) "（当前生效中，只能用白名单应用）" else "（当前不在时段内）")
                            append("。")
                        }
                        whitelistQuota?.let { append("白名单添加：").append(it.describe()).append("。") }
                    },
                )
                put("settingsUrlHint", DASHBOARD_HINT)
            }
        }
    }

    private data class LimitRow(
        val packageName: String,
        val appName: String,
        val limitMinutes: Int,
        val usedMinutes: Long,
        val remainingMinutes: Long,
        val exceeded: Boolean,
    )

    // ==================================================================
    // 待确认草案（与 ControlTools 保持同一返回结构）
    // ==================================================================

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
}

// 文件级常量（放在类外，避免 inner class 里 companion 作用域解析歧义）
private const val SETTINGS_HINT = "可在「设置」页查看每日额度"
private const val APPS_HINT = "可在「应用」页查看每日限额"
private const val DASHBOARD_HINT = "可在「首页」查看今日额度"
private const val CONFIRM_HINT = "可在应用内确认（草案 5 分钟内有效）"
