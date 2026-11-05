package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.restriction.RuleValidator
import org.json.JSONArray
import org.json.JSONObject

/**
 * 白名单工具：get_whitelist / set_whitelist。
 *
 * 和 [RestrictionTools] 的 propose_restriction（只给建议、不执行）不同：
 * set_whitelist 会**真正**调用 [SettingsRepository.setAllowed] 修改用户的「始终允许使用」名单，
 * 因此工具描述里明确写了「仅在用户明确要求时调用」，结果里回显执行了什么；
 * 系统关键应用（电话/短信/系统设置/桌面/本应用）会被 [RuleValidator] 拒绝，
 * 避免通过白名单绕过系统保护。白名单只影响「是否被限制」，不会拦截 App、不改限制规则表。
 */
class WhitelistTools(
    private val settings: SettingsRepository,
    private val source: UsageEventSource,
    private val usage: UsageRepository,
) {

    val all: List<AgentTool> = listOf(GetWhitelistTool(), SetWhitelistTool())

    // ------------------------------------------------------------------

    private inner class GetWhitelistTool : AgentTool {
        override val name: String = "get_whitelist"
        override val description: String =
            "查询当前的「始终允许使用」白名单：白名单内的应用即使开启默认限制也不会被暂停。" +
                "只读，不会修改任何设置；需要修改时用 set_whitelist。"

        override val parameters: JSONObject = JsonSchema.objectSchema(properties = emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val prefs = settings.current()
            return whitelistPayload(
                packages = prefs.allowedPackages,
                restrictByDefault = prefs.restrictByDefault,
                executed = false,
            ).apply {
                put("message", "当前白名单共 ${prefs.allowedPackages.size} 个应用。")
            }
        }
    }

    // ------------------------------------------------------------------

    private inner class SetWhitelistTool : AgentTool {
        override val name: String = "set_whitelist"
        override val description: String =
            "把应用加入或移出「始终允许使用」白名单（会真正修改用户设置并立即生效）。" +
                "仅在用户明确要求修改白名单时调用；只是询问、或让你给建议时不要调用。" +
                "系统关键应用（电话/短信/系统设置/桌面/本应用）本身不受限制，会被拒绝加入。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "package_name" to JsonSchema.string("应用包名或应用名，例如 com.tencent.mm / 微信"),
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

            // 真正执行：写入 DataStore 的「始终允许」名单
            settings.setAllowed(packageName, allowed)
            val after = settings.current()

            return whitelistPayload(
                packages = after.allowedPackages,
                restrictByDefault = after.restrictByDefault,
                executed = true,
            ).apply {
                put("action", if (allowed) "ADD" else "REMOVE")
                put("packageName", packageName)
                put("appName", appName)
                put("requestedAllowed", allowed)
                put("whitelisted", after.allowedPackages.contains(packageName))
                put(
                    "message",
                    if (allowed) {
                        "已把「$appName」加入白名单：开启默认限制时它也不会被暂停。"
                    } else {
                        "已把「$appName」移出白名单：开启默认限制时它会重新受限制。"
                    },
                )
                put("note", "可在「应用」页看到白名单状态变化。")
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
