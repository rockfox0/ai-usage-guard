package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.ActionDraftRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.repo.WhitelistRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.restriction.RuleValidator
import org.json.JSONArray
import org.json.JSONObject

/**
 * 白名单工具：get_whitelist / set_whitelist。
 *
 * v1.7 权限与确认（方案 §6.1/§6.2）：
 * - get_whitelist 是 QUERY，所有任务域可用，只读；
 * - set_whitelist 是 PROPOSE：不再直接写 DataStore，而是生成 PENDING 草案（SET_WHITELIST），
 *   用户确认后由本地 ActionExecutor 执行，避免 AI 直接改动用户业务配置。
 * 系统关键应用（电话/短信/系统设置/桌面/本应用）会被 [RuleValidator] 拒绝。
 */
class WhitelistTools(
    private val settings: SettingsRepository,
    private val source: UsageEventSource,
    private val usage: UsageRepository,
    /** 待确认草案仓库（延迟取 Container，避免 AppWiring 增加注入参数） */
    private val actionDraftsProvider: () -> ActionDraftRepository = { Container.actionDrafts },
    /** 当前任务 id，由 AgentLoop 注入 */
    private val taskIdProvider: () -> String = { "" },
    /** 白名单仓储（只读每日添加上限），默认延迟取 Container */
    private val whitelistProvider: () -> WhitelistRepository = { Container.whitelist },
) {

    val all: List<AgentTool> = listOf(GetWhitelistTool(), SetWhitelistTool())

    // ------------------------------------------------------------------

    private inner class GetWhitelistTool : AgentTool {
        override val name: String = "get_whitelist"
        override val description: String =
            "查询白名单应用与今天还能添加几个（每日上限）。只读；修改用 set_whitelist（草案）。"

        override val parameters: JSONObject = JsonSchema.objectSchema(properties = emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val prefs = settings.current()
            val quota = runCatching { whitelistProvider().quota() }.getOrNull()
            return whitelistPayload(
                packages = prefs.allowedPackages,
                restrictByDefault = prefs.restrictByDefault,
                executed = false,
            ).apply {
                put("whitelistDailyLimit", quota?.limit ?: prefs.whitelistDailyLimit)
                put("whitelistAddedToday", quota?.used ?: prefs.whitelistAddCount)
                put(
                    "whitelistRemainingToday",
                    quota?.let { if (it.unlimited) -1 else it.remaining } ?: -1,
                )
                put(
                    "message",
                    buildString {
                        append("当前白名单共 ").append(prefs.allowedPackages.size).append(" 个应用。")
                        quota?.let { append(it.describe()).append("。") }
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------

    private inner class SetWhitelistTool : AgentTool {
        override val name: String = "set_whitelist"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "生成白名单加入/移出草案（SET_WHITELIST，需确认后生效）；系统关键应用会被拒绝。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "package_name" to JsonSchema.string(
                    "应用包名或应用名，例如 com.tencent.mm / 微信",
                    format = "app_ref",
                ),
                "allowed" to JsonSchema.boolean("true=加入白名单（始终允许）；false=移出白名单"),
            ),
            required = listOf("package_name", "allowed"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val input = arguments.stringArg("package_name")
                ?: throw ToolArgumentException("缺少必填参数 package_name（包名或应用名）")
            val allowed = arguments.boolArg("allowed")
                ?: throw ToolArgumentException("缺少必填参数 allowed（true / false）")

            val packageName = resolvePackageName(usage, source, input)
                ?: throw ToolArgumentException(
                    "没有找到应用「$input」，无法修改白名单。可以先用 get_usage_ranking 查准确的包名。",
                )
            val appName = source.appNameOf(packageName) ?: packageName
            val before = settings.current()

            // 系统保护应用：拒绝（本身不受限制，也不需要白名单；防止借白名单绕过保护）
            val launcherPackages = runCatching { source.launcherPackages() }.getOrDefault(emptySet())
            if (RuleValidator.isProtected(packageName, launcherPackages)) {
                return whitelistPayload(
                    packages = before.allowedPackages,
                    restrictByDefault = before.restrictByDefault,
                    executed = false,
                ).apply {
                    put("action", "REJECTED")
                    put("packageName", packageName)
                    put("appName", appName)
                    put("requestedAllowed", allowed)
                    put("rejectedReason", "系统保护应用无需白名单")
                    put(
                        "message",
                        "「$appName」是系统关键应用（电话/短信/系统设置/桌面/本应用等），本身不会被限制，无需加入白名单。",
                    )
                    put("note", "未修改任何设置。")
                }
            }

            // v1.8.2：加入白名单受「每天最多添加 N 个」限制，先检查再生成草案
            // （已经在白名单里的应用重复加入不消耗额度，不做拒绝）
            val alreadyWhitelisted = packageName in before.allowedPackages
            val whitelistQuota = runCatching { whitelistProvider().quota() }.getOrNull()
            if (allowed && !alreadyWhitelisted && whitelistQuota != null && !whitelistQuota.canAdd) {
                return whitelistPayload(
                    packages = before.allowedPackages,
                    restrictByDefault = before.restrictByDefault,
                    executed = false,
                ).apply {
                    put("action", "REJECTED")
                    put("packageName", packageName)
                    put("appName", appName)
                    put("requestedAllowed", true)
                    put("rejectedReason", "已达到白名单每日添加上限")
                    put("whitelistDailyLimit", whitelistQuota.limit)
                    put("whitelistAddedToday", whitelistQuota.used)
                    put("whitelistRemainingToday", 0)
                    put("message", "今天不能再添加白名单应用：${whitelistQuota.describe()}。")
                    put("note", "未生成草案，也未修改任何设置；可在设置里调整每日添加上限。")
                }
            }

            // v1.7：不直接写设置，改为生成待确认草案（SET_WHITELIST）
            val drafts = runCatching { actionDraftsProvider() }.getOrElse {
                throw ToolArgumentException("待确认操作服务尚未就绪，请稍后再试。")
            }
            val taskId = runCatching { taskIdProvider() }.getOrDefault("").ifBlank { "agent-task" }
            val wasAllowed = packageName in before.allowedPackages
            val params = JSONObject().put("package_name", packageName).put("allowed", allowed)
            val beforeJson = JSONObject().put("package_name", packageName).put("allowed", wasAllowed)
            val afterJson = JSONObject().put("package_name", packageName).put("allowed", allowed)
            val entity = runCatching {
                drafts.propose(
                    taskId = taskId,
                    type = "SET_WHITELIST",
                    packages = setOf(packageName),
                    paramsJson = params.toString(),
                    beforeJson = beforeJson.toString(),
                    afterJson = afterJson.toString(),
                )
            }.getOrElse {
                throw ToolArgumentException("生成待确认操作失败：${it.message?.take(120) ?: "未知错误"}")
            }

            return whitelistPayload(
                packages = before.allowedPackages,
                restrictByDefault = before.restrictByDefault,
                executed = false,
            ).apply {
                put("requiresConfirmation", true)
                put("opId", entity.opId)
                put("type", entity.type)
                put("status", entity.status)
                put("expiresAt", entity.expiresAt)
                put("action", if (allowed) "ADD" else "REMOVE")
                put("packageName", packageName)
                put("appName", appName)
                put("requestedAllowed", allowed)
                put("whitelisted", wasAllowed)
                put(
                    "changed",
                    JSONObject().put(
                        "whitelisted",
                        JSONObject().apply {
                            put("from", wasAllowed)
                            put("to", allowed)
                        },
                    ),
                )
                put(
                    "message",
                    if (allowed) {
                        "已生成待确认操作：将「$appName」加入白名单（开启默认限制时它不会被暂停），请在应用内确认。"
                    } else {
                        "已生成待确认操作：将「$appName」移出白名单（开启默认限制时它会重新受限制），请在应用内确认。"
                    },
                )
                whitelistQuota?.let { quota ->
                    put("whitelistDailyLimit", quota.limit)
                    put("whitelistAddedToday", quota.used)
                    put(
                        "whitelistRemainingToday",
                        if (quota.unlimited) -1 else quota.remaining,
                    )
                }
                put("note", "草案 5 分钟内有效；确认后才生效，可在「应用」页查看。")
            }
        }
    }

    // ------------------------------------------------------------------

    /** 统一的白名单返回结构：数量 + 每项包名/应用名 + 当前限制模式 */
    private fun whitelistPayload(
        packages: Set<String>,
        restrictByDefault: Boolean,
        executed: Boolean,
    ): JSONObject = JSONObject().apply {
        put("executed", executed)
        put("count", packages.size)
        put("restrictByDefault", restrictByDefault)
        put(
            "whitelist",
            JSONArray().apply {
                packages
                    .map { pkg -> pkg to (runCatching { source.appNameOf(pkg) }.getOrNull() ?: pkg) }
                    .sortedBy { it.second }
                    .forEach { (pkg, name) ->
                        put(
                            JSONObject().apply {
                                put("packageName", pkg)
                                put("appName", name)
                            },
                        )
                    }
            },
        )
        if (packages.isEmpty()) {
            put("note", "白名单为空：默认限制模式下所有应用都可能被暂停。")
        }
    }
}
