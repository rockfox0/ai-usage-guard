package com.aid.usageguard.ui.actions

import com.aid.usageguard.data.db.PendingActionEntity
import com.aid.usageguard.data.repo.ActionDraftRepository
import com.aid.usageguard.domain.action.ActionExecutor
import com.aid.usageguard.restriction.RestrictionMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 一条「待确认操作」的展示模型。
 * 设置页（[PendingActionsCard]）与 AI 助手页（ActionProposed 事件插入）共用同一份数据。
 */
data class PendingActionUi(
    val opId: String,
    val type: String,
    /** 类型中文名，例如 SET_DAILY_TARGET →「每日目标」 */
    val typeLabel: String,
    /** 目标应用（packages 非空时），例如「微信、抖音」 */
    val targetLabel: String? = null,
    /** 修改前后摘要，例如「目标 120 分钟 → 100 分钟」 */
    val changeLabel: String = "",
    val expiresAt: Long = 0L,
    val expired: Boolean = false,
    /** 「剩余 4 分 12 秒」/「已过期」 */
    val remainingText: String = "",
    val busy: Boolean = false,
    /** 非空表示已处理（已执行/已取消/失败），卡片置灰不可再操作 */
    val statusText: String? = null,
    val success: Boolean? = null,
    /** AI 在 ActionProposed 事件里带的说明（助手页展示用） */
    val note: String? = null,
) {
    /** 是否可以点「确认并执行 / 取消」 */
    val actionable: Boolean get() = !expired && !busy && statusText == null

    /** 已经处理完成（成功/失败/取消） */
    val done: Boolean get() = statusText != null
}

/** 确认并执行的结果 */
data class ActionExecution(
    val success: Boolean,
    val message: String,
)

/**
 * 待确认操作的展示与执行逻辑（无状态，纯函数 + 两个 suspend 帮助函数）。
 * 设置页 ViewModel 和助手页 ViewModel 都复用这里，避免两处各写一套。
 */
object PendingActionPresenter {

    /** 操作类型 → 中文名 */
    fun typeLabel(type: String): String = when (type) {
        "SET_DAILY_TARGET" -> "每日目标"
        "SET_QUIET_HOURS" -> "免打扰时段"
        "SET_NOTIFICATION_ENABLED" -> "通知开关"
        "SET_NOTIFICATION_CHANNELS" -> "通知渠道"
        "SET_REMINDER_STYLE" -> "提醒风格"
        "SET_THEME_MODE" -> "主题模式"
        "SET_RESTRICT_MODE" -> "限制模式"
        "SET_NUDGE" -> "实时提示"
        "SET_AI_PERSONA" -> "AI 个性化"
        "SET_AI_ANALYSIS_ENABLED" -> "AI 分析开关"
        "SET_WHITELIST" -> "白名单"
        "SET_APP_RESTRICTION" -> "应用限制"
        "SET_APP_DAILY_LIMIT" -> "应用每日限额"
        "SET_DAILY_BUDGET_ENFORCED" -> "每日额度拦截"
        "SET_RESTRICTION_MODE" -> "限制模式"
        "SET_FOCUS_WINDOW" -> "专注时段"
        "SET_WHITELIST_DAILY_LIMIT" -> "白名单每日上限"
        "REFRESH_USAGE_DATA" -> "同步使用数据"
        else -> "配置修改"
    }

    /** 目标应用名（packages 为逗号分隔的包名） */
    fun targetApps(packages: String, nameOf: (String) -> String?): String? {
        val list = packages.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (list.isEmpty()) return null
        val shown = list.take(3).joinToString("、") { pkg -> nameOf(pkg) ?: pkg }
        return if (list.size > 3) "$shown 等 ${list.size} 个应用" else shown
    }

    /**
     * 把 beforeJson / afterJson 的关键字段渲染成「目标 120 分钟 → 100 分钟」。
     * [type] 用于个别类型（每日限额 / 限制模式 / 超额拦截）做更贴切的字段名与取值格式。
     */
    fun summarize(beforeJson: String, afterJson: String, type: String = ""): String {
        val before = parse(beforeJson)
        val after = parse(afterJson)
        val keys = LinkedHashSet<String>()
        after.keys().forEach { keys.add(it) }
        val parts = keys.mapNotNull { key ->
            val label = keyLabel(key, type) ?: return@mapNotNull null
            val beforeValue = formatValue(key, before.optValue(key), type)
            val afterValue = formatValue(key, after.optValue(key), type)
            if (beforeValue == afterValue) null else "$label $beforeValue → $afterValue"
        }
        return parts.joinToString("；")
    }

    /** 没有 before/after 时，尽量从 paramsJson 里解析一句摘要 */
    fun summarizeParams(type: String, paramsJson: String): String {
        val params = parse(paramsJson)
        return when (type) {
            "SET_APP_DAILY_LIMIT" -> {
                val minutes = params.optInt("minutes", 0)
                if (minutes <= 0) "清除每日限额" else "每日限额 $minutes 分钟"
            }

            "SET_DAILY_BUDGET_ENFORCED" ->
                if (params.optBoolean("enabled", true)) "超额拦截：开启" else "超额拦截：关闭"

            "SET_RESTRICTION_MODE" ->
                "限制模式：${RestrictionMode.parse(params.optString("mode")).label}"

            "SET_FOCUS_WINDOW" -> {
                val enabled = params.optBoolean("enabled", true)
                val start = params.optInt("start_hour", -1)
                val end = params.optInt("end_hour", -1)
                when {
                    !enabled -> "关闭专注时段"
                    start in 0..23 && end in 0..23 ->
                        "专注时段：%02d:00-%02d:00（仅白名单可用）".format(start, end)
                    else -> "开启专注时段（仅白名单可用）"
                }
            }

            "SET_WHITELIST_DAILY_LIMIT" -> {
                val limit = params.optInt("limit", 0)
                if (limit <= 0) "白名单每日上限：不限" else "白名单每日上限：$limit 个/天"
            }

            else -> ""
        }
    }

    /** 「剩余 4 分 12 秒」/「已过期」 */
    fun remainingText(expiresAt: Long, now: Long = System.currentTimeMillis()): String {
        val left = expiresAt - now
        return when {
            left <= 0L -> "已过期"
            left < 60_000L -> "剩余 ${left / 1000} 秒"
            else -> "剩余 ${left / 60_000L} 分 ${(left % 60_000L) / 1000} 秒"
        }
    }

    /** 实体 → 展示模型 */
    fun toUi(
        entity: PendingActionEntity,
        nameOf: (String) -> String? = { null },
        now: Long = System.currentTimeMillis(),
    ): PendingActionUi {
        val expired = entity.status != ActionDraftRepository.STATUS_PENDING || now > entity.expiresAt
        return PendingActionUi(
            opId = entity.opId,
            type = entity.type,
            typeLabel = typeLabel(entity.type),
            targetLabel = targetApps(entity.packages, nameOf),
            changeLabel = summarize(entity.beforeJson, entity.afterJson, entity.type)
                .ifBlank { summarizeParams(entity.type, entity.paramsJson) }
                .ifBlank { "按草案参数执行" },
            expiresAt = entity.expiresAt,
            expired = expired,
            remainingText = remainingText(entity.expiresAt, now),
        )
    }

    /**
     * 确认并执行：markConfirmed → execute → finish。
     * markConfirmed 返回 null 说明草案已过期/已处理，绝不重复执行。
     */
    suspend fun confirmAndExecute(
        drafts: ActionDraftRepository,
        executor: ActionExecutor,
        opId: String,
    ): ActionExecution {
        val confirmed = runCatching { drafts.markConfirmed(opId) }.getOrNull()
            ?: return ActionExecution(success = false, message = "该操作已过期或已处理")
        val result = runCatching { executor.execute(confirmed) }.getOrNull()
        if (result == null) {
            runCatching { drafts.finish(opId, false, null, "执行器异常") }
            return ActionExecution(success = false, message = "执行失败，请稍后重试")
        }
        runCatching { drafts.finish(opId, result.success, result.resultJson, result.error) }
        return ActionExecution(success = result.success, message = result.message)
    }

    /** 取消草案（仅 PENDING 生效） */
    suspend fun cancel(drafts: ActionDraftRepository, opId: String) {
        runCatching { drafts.cancel(opId) }
    }

    // ------------------------------------------------------------------

    private fun keyLabel(key: String, type: String = ""): String? {
        // 额度/模式类草案的字段用更贴切的名字
        if (type == "SET_APP_DAILY_LIMIT" && key == "minutes") return "限额"
        if (type == "SET_RESTRICTION_MODE" && key == "mode") return "模式"
        if (type == "SET_DAILY_BUDGET_ENFORCED" && key == "enabled") return "超额拦截"
        if (type == "SET_FOCUS_WINDOW" && key == "enabled") return "专注时段"
        if (type == "SET_WHITELIST_DAILY_LIMIT" && key == "limit") return "每日上限"
        return when (key) {
            "minutes" -> "目标"
            "start_hour" -> "开始"
            "end_hour" -> "结束"
            "enabled" -> "开关"
            "restricted" -> "限制"
            "whitelisted" -> "白名单"
            "allowed" -> "允许"
            "style" -> "风格"
            "mode" -> "模式"
            "nickname" -> "昵称"
            "tone" -> "语气"
            "focus" -> "关注"
            "custom_prompt" -> "自定义提示"
            "interval_minutes" -> "间隔"
            "use_ai" -> "AI 生成"
            "daily_reminder" -> "每日提醒"
            "goal_exceeded" -> "超目标提醒"
            "anomaly" -> "异常提醒"
            "daily_summary" -> "每日总结"
            "weekly_summary" -> "每周总结"
            // package_name 由 targetLabel 展示，摘要里不重复
            else -> null
        }
    }

    private fun formatValue(key: String, value: Any?, type: String = ""): String {
        // 额度/模式类字段的取值格式
        when {
            type == "SET_APP_DAILY_LIMIT" && key == "minutes" ->
                return if (value == null) "不限" else "${toLong(value)} 分钟"

            type == "SET_RESTRICTION_MODE" && key == "mode" ->
                return RestrictionMode.parse(value?.toString()).label

            type == "SET_DAILY_BUDGET_ENFORCED" && key == "enabled" ->
                return if (toBool(value)) "开" else "关"

            type == "SET_WHITELIST_DAILY_LIMIT" && key == "limit" ->
                return if (toLong(value) <= 0L) "不限" else "${toLong(value)} 个/天"
        }
        if (value == null) return "—"
        return when (key) {
            "minutes", "interval_minutes" -> "${toLong(value)} 分钟"
            "start_hour", "end_hour" -> String.format(Locale.US, "%02d:00", toLong(value))
            "enabled", "use_ai", "daily_reminder", "goal_exceeded", "anomaly",
            "daily_summary", "weekly_summary" -> if (toBool(value)) "开" else "关"
            "restricted" -> if (toBool(value)) "受限" else "不受限"
            "whitelisted" -> if (toBool(value)) "是" else "否"
            "allowed" -> if (toBool(value)) "允许" else "禁止"
            else -> when (value) {
                is JSONArray -> (0 until value.length())
                    .mapNotNull { index -> runCatching { value.getString(index) }.getOrNull() }
                    .joinToString("、")
                    .ifBlank { "—" }

                else -> value.toString()
            }
        }
    }

    private fun parse(json: String): JSONObject =
        runCatching { JSONObject(json) }.getOrDefault(JSONObject())

    private fun JSONObject.optValue(key: String): Any? =
        if (has(key) && !isNull(key)) get(key) else null

    private fun toLong(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        else -> value?.toString()?.toLongOrNull() ?: 0L
    }

    private fun toBool(value: Any?): Boolean = when (value) {
        is Boolean -> value
        else -> value?.toString() == "true"
    }
}
