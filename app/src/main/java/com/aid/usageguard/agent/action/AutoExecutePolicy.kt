package com.aid.usageguard.agent.action

import com.aid.usageguard.agent.tools.ToolKind
import org.json.JSONObject

/**
 * AI 写操作权限（v1.9.2）。
 *
 * - 默认「每次确认」：写工具只生成草案，用户点确认后由 ActionExecutor 执行；
 * - 「自动执行」：草案生成后立即 markConfirmed + 执行，模型拿到的工具结果会变成
 *   `executed=true`，可以直接汇报"已生效"。
 *
 * 安全边界不变：自动执行仍走 [com.aid.usageguard.domain.action.ActionExecutor]，
 * 系统保护应用、白名单每日添加上限、参数校验、失败回滚语义都与手动确认完全一致。
 */
object AutoExecutePolicy {

    /** 哪些工具属于「写操作」（会自动执行的只有这些） */
    fun isWriteTool(kind: ToolKind): Boolean = kind == ToolKind.PROPOSE || kind == ToolKind.CONTROL

    fun shouldAutoExecute(autoExecuteWrites: Boolean, kind: ToolKind): Boolean =
        autoExecuteWrites && isWriteTool(kind)

    /**
     * 自动执行后，把工具原始输出改写成「已执行」结果，作为 tool 消息喂回模型。
     * 保留原字段（opId/changed 等），补充执行结果，避免模型以为还要用户确认。
     */
    fun rewriteExecuted(originalJson: String, success: Boolean, message: String): String = runCatching {
        val obj = JSONObject(originalJson)
        obj.put("autoExecuted", true)
        obj.put("executed", success)
        obj.put("requiresConfirmation", false)
        obj.put("success", success)
        obj.put("message", if (success) "已自动执行：$message" else "自动执行失败：$message")
        obj.toString()
    }.getOrDefault(originalJson)

    /** 自动执行模式下追加到系统提示词的说明（覆盖"必须让用户确认"的旧要求） */
    const val AUTO_EXECUTE_HINT: String =
        "【当前为「自动执行」模式】写操作（改设置/限制/额度/白名单等）会在你调用工具后立即执行，" +
            "工具返回 executed=true 表示已经生效：请直接汇报执行结果与实际变化，不要再说「请在应用内确认」。\n" +
            "若返回 executed=false 或 success=false，说明执行失败（可能命中系统保护应用/白名单每日上限/参数校验），" +
            "必须如实说明原因，不要声称已经改好。\n" +
            "自动执行不等于可以绕过规则：系统保护应用、白名单每日添加上限、草案过期等限制仍然有效。"

    /** 每次确认模式下追加的一句（保持旧行为） */
    const val CONFIRM_REQUIRED_HINT: String =
        "【当前为「每次确认」模式】写操作只生成待确认草案，必须提示用户在应用内确认后才会生效。"
}
