package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.restriction.AppRestrictionController
import com.aid.usageguard.restriction.RuleValidator
import org.json.JSONObject

/**
 * 限制建议工具：propose_restriction。
 *
 * 安全边界（硬性要求）：
 * - 只调用 [RuleValidator] 做校验并返回「建议」，绝不直接调用
 *   [AppRestrictionController.block] / unblock / 不写限制规则表；
 * - 真正执行必须由用户在「应用」页确认；
 * - 电话、短信、系统设置、桌面等受保护应用会被明确拒绝。
 */
class RestrictionTools(
    private val restrictions: AppRestrictionController,
    private val source: UsageEventSource,
    private val usage: UsageRepository,
    private val settings: SettingsRepository? = null,
) {

    val all: List<AgentTool> = listOf(ProposeRestrictionTool())

    private inner class ProposeRestrictionTool : AgentTool {
        override val name: String = "propose_restriction"
        override val description: String =
            "针对某个应用向用户提出「暂停 / 限制」建议，并返回规则校验结果。" +
                "这个工具只生成建议，不会执行任何限制；最终是否生效由用户在应用内确认。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "package_name" to JsonSchema.string("应用包名或应用名，例如 com.tencent.mm / 微信"),
                "reason" to JsonSchema.string("建议理由（一句话，中文）"),
                "daily_limit_minutes" to JsonSchema.integer(
                    "建议的每日使用上限（分钟），可选",
                    minimum = 1,
                    maximum = 600,
                ),
            ),
            required = listOf("package_name"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val input = arguments.stringArg("package_name")
                ?: throw ToolArgumentException("缺少必填参数 package_name（包名或应用名）")
            val packageName = resolvePackageName(usage, source, input)
                ?: throw ToolArgumentException("没有找到应用「$input」，无法生成限制建议。")
            val appName = source.appNameOf(packageName) ?: packageName

            // 只做校验：installed 用包名能否解析判断，launcher 包名走系统查询。
            val installed = if (source.appNameOf(packageName) != null) setOf(packageName) else emptySet()
            val launcherPackages = runCatching { source.launcherPackages() }.getOrDefault(emptySet())
            val validation = RuleValidator.validate(
                requested = setOf(packageName),
                installed = installed,
                launcherPackages = launcherPackages,
            )
            val whitelisted = runCatching {
                settings?.current()?.allowedPackages?.contains(packageName) == true
            }.getOrDefault(false)
            val rejectedReason = when {
                whitelisted -> "该应用在「始终允许」白名单中，不受限制"
                else -> validation.rejected[packageName]
            }
            val alreadyBlocked = runCatching { restrictions.isBlocked(packageName) }.getOrDefault(false)
            val limit = (arguments.intArg("daily_limit_minutes") ?: 0).coerceIn(0, 600)
            val reason = arguments.stringArg("reason")

            return JSONObject().apply {
                put("action", "PROPOSE_RESTRICTION")
                put("executed", false)
                put("requiresUserConfirmation", true)
                put("allowed", rejectedReason == null)
                put("packageName", packageName)
                put("appName", appName)
                put("currentlyBlocked", alreadyBlocked)
                if (reason != null) put("reason", reason)
                if (limit > 0) {
                    put("suggestedDailyLimitMinutes", limit)
                    put("suggestedDailyLimitText", ToolFormat.minutes(limit.toLong()))
                }
                if (rejectedReason != null) {
                    put("rejectedReason", rejectedReason)
                    put("message", "不能建议限制「$appName」：$rejectedReason。")
                } else {
                    put(
                        "message",
                        "已生成限制建议（$appName / $packageName），但尚未执行。" +
                            "请告诉用户：需要到「应用」页手动确认后才会真正暂停。",
                    )
                }
                put("safetyNote", "Agent 不能直接执行限制，也不能调用 Shell / 系统接口修改规则。")
            }
        }
    }
}
