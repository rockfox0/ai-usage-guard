package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.Container
import com.aid.usageguard.data.repo.ActionDraftRepository
import com.aid.usageguard.data.repo.WhitelistRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.quota.FocusWindow
import org.json.JSONObject

/**
 * 守卫类工具组（v1.8.2）：
 * - `set_focus_window`（PROPOSE）：专注时段开关与起止小时，时段内只能使用白名单应用；
 * - `set_whitelist_daily_limit`（PROPOSE）：白名单每日添加上限（0 = 不限）。
 *
 * 两者都只生成「待确认操作草案」，由 ActionExecutor 在用户确认后执行；
 * 时段/上限的具体拦截由 GuardService 负责（与额度无关）。
 */
class GuardTools(
    private val settings: SettingsRepository,
    /** 白名单仓储（延迟取 Container，避免 AppWiring 增加注入参数） */
    private val whitelistProvider: () -> WhitelistRepository = { Container.whitelist },
    private val actionDraftsProvider: () -> ActionDraftRepository = { Container.actionDrafts },
    /** 当前任务 id，由 AgentLoop 注入 */
    private val taskIdProvider: () -> String = { "" },
) {

    val all: List<AgentTool> = listOf(
        SetFocusWindowTool(),
        SetWhitelistDailyLimitTool(),
    )

    // ==================================================================
    // 1) 专注时段（PROPOSE → SET_FOCUS_WINDOW）
    // ==================================================================

    private inner class SetFocusWindowTool : AgentTool {
        override val name: String = "set_focus_window"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成「设置 / 关闭专注时段」的待确认草案：时段内只能使用白名单应用，与额度无关，支持跨午夜。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "enabled" to JsonSchema.boolean("true=开启专注时段；false=关闭"),
                "start_hour" to JsonSchema.integer("开始小时 0-23，可选（缺省沿用当前）", minimum = 0, maximum = 23),
                "end_hour" to JsonSchema.integer("结束小时 0-23，可选（缺省沿用当前）", minimum = 0, maximum = 23),
            ),
            required = listOf("enabled"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val enabled = arguments.boolArg("enabled")
                ?: throw ToolArgumentException("缺少必填参数 enabled（true / false）")
            val startArg = arguments.intArg("start_hour")
            val endArg = arguments.intArg("end_hour")
            if (startArg != null && startArg !in 0..23) {
                throw ToolArgumentException("start_hour 需在 0-23 之间，收到 $startArg")
            }
            if (endArg != null && endArg !in 0..23) {
                throw ToolArgumentException("end_hour 需在 0-23 之间，收到 $endArg")
            }

            val prefs = settings.current()
            val start = startArg ?: prefs.focusWindowStart
            val end = endArg ?: prefs.focusWindowEnd
            val label = FocusWindow.label(start, end)

            // 参数显式写全（enabled/start_hour/end_hour），确认后执行的就是草案里展示的值
            val params = JSONObject()
                .put("enabled", enabled)
                .put("start_hour", start)
                .put("end_hour", end)
            val beforeJson = JSONObject()
                .put("enabled", prefs.focusWindowEnabled)
                .put("start_hour", prefs.focusWindowStart)
                .put("end_hour", prefs.focusWindowEnd)
            val afterJson = JSONObject()
                .put("enabled", enabled)
                .put("start_hour", start)
                .put("end_hour", end)
            val changed = JSONObject()
                .put("focusWindowEnabled", change(prefs.focusWindowEnabled, enabled))
                .put("focusWindowStart", change(prefs.focusWindowStart, start))
                .put("focusWindowEnd", change(prefs.focusWindowEnd, end))

            val message = if (enabled) {
                "专注时段 $label（起止小时相同=全天）：时段内只能使用白名单应用，与额度无关，支持跨午夜。"
            } else {
                "关闭专注时段（当前设置 $label）。关闭后不再按时段限制应用。"
            }

            return proposeDraft(
                type = "SET_FOCUS_WINDOW",
                params = params,
                before = beforeJson,
                after = afterJson,
                changed = changed,
                message = message,
                settingsUrlHint = SETTINGS_HINT,
            ).apply {
                put("focusWindow", afterJson)
                put("focusWindowText", label)
            }
        }
    }

    // ==================================================================
    // 2) 白名单每日添加上限（PROPOSE → SET_WHITELIST_DAILY_LIMIT）
    // ==================================================================

    private inner class SetWhitelistDailyLimitTool : AgentTool {
        override val name: String = "set_whitelist_daily_limit"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成「设置白名单每日添加上限」的待确认草案：每天最多添加几个应用到白名单（0-20，0=不限）。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "limit" to JsonSchema.integer("每天最多添加几个白名单应用，0-20；0=不限", minimum = 0, maximum = 20),
            ),
            required = listOf("limit"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val limit = arguments.intArg("limit")
                ?: throw ToolArgumentException("缺少必填参数 limit（0-20 的整数，0 表示不限）")
            if (limit !in 0..20) {
                throw ToolArgumentException("limit 需在 0-20 之间，收到 $limit")
            }

            val prefs = settings.current()
            val quota = runCatching { whitelistProvider().quota() }.getOrNull()
            val usedToday = quota?.used ?: prefs.whitelistAddCount

            val params = JSONObject().put("limit", limit)
            val beforeJson = JSONObject().put("limit", prefs.whitelistDailyLimit)
            val afterJson = JSONObject().put("limit", limit)
            val changed = JSONObject().put(
                "whitelistDailyLimit",
                change(prefs.whitelistDailyLimit, limit),
            )

            val message = if (limit == 0) {
                "关闭白名单每日添加上限（不限次数；今天已添加 $usedToday 个）。"
            } else {
                "每天最多添加 $limit 个应用到白名单（今天已添加 $usedToday 个）。"
            }

            return proposeDraft(
                type = "SET_WHITELIST_DAILY_LIMIT",
                params = params,
                before = beforeJson,
                after = afterJson,
                changed = changed,
                message = message,
                settingsUrlHint = SETTINGS_HINT,
            ).apply {
                put("whitelistDailyLimit", limit)
                put("whitelistAddedToday", usedToday)
            }
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private suspend fun proposeDraft(
        type: String,
        params: JSONObject,
        before: JSONObject,
        after: JSONObject,
        changed: JSONObject,
        message: String,
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
                packages = emptySet(),
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

    private fun change(from: Any?, to: Any?): JSONObject = JSONObject().apply {
        put("from", from ?: JSONObject.NULL)
        put("to", to ?: JSONObject.NULL)
    }
}

// 文件级常量（放在类外，避免 inner class 里 companion 作用域解析歧义）
private const val SETTINGS_HINT = "可在「设置」页查看专注时段与白名单上限"
private const val CONFIRM_HINT = "可在应用内确认（草案 5 分钟内有效）"
