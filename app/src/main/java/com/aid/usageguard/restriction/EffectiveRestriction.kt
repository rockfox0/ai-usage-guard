package com.aid.usageguard.restriction

/**
 * 统一「最终生效限制」判断（v1.7 方案 4.3）。
 *
 * UI、Agent 工具和 GuardService 必须共用同一份判断，避免出现
 * 「页面显示已放行、服务仍在拦截」或「删掉手动限制但仍被默认限制拦截」这类不一致。
 */
enum class RestrictionReason(val label: String) {
    PROTECTED("系统保护，不限"),
    WHITELISTED("在允许名单"),
    ALLOWED("未限制"),
    DEFAULT_RESTRICTED("默认限制"),
    MANUALLY_RESTRICTED("手动限制"),
}

data class EffectiveRestriction(
    val restricted: Boolean,
    val reason: RestrictionReason,
    val explain: String,
) {
    companion object {
        fun allowed(reason: RestrictionReason, explain: String) =
            EffectiveRestriction(false, reason, explain)

        fun restricted(reason: RestrictionReason, explain: String) =
            EffectiveRestriction(true, reason, explain)
    }
}

object EffectiveRestrictionResolver {

    fun resolve(
        packageName: String,
        allowedPackages: Set<String>,
        explicitBlocked: Set<String>,
        restrictByDefault: Boolean,
        protectedPackages: Set<String>,
    ): EffectiveRestriction = when {
        RuleValidator.isProtected(packageName, protectedPackages) ->
            EffectiveRestriction.allowed(
                RestrictionReason.PROTECTED,
                "系统关键应用或输入法，永远不受限制",
            )

        packageName in allowedPackages ->
            EffectiveRestriction.allowed(
                RestrictionReason.WHITELISTED,
                "在允许名单中，不受限制",
            )

        restrictByDefault ->
            EffectiveRestriction.restricted(
                RestrictionReason.DEFAULT_RESTRICTED,
                "默认限制模式：除允许名单和系统应用外一律受限",
            )

        packageName in explicitBlocked ->
            EffectiveRestriction.restricted(
                RestrictionReason.MANUALLY_RESTRICTED,
                "在手动限制列表中",
            )

        else -> EffectiveRestriction.allowed(
            RestrictionReason.ALLOWED,
            "当前未限制",
        )
    }
}
