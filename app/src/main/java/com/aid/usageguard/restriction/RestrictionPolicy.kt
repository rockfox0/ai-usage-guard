package com.aid.usageguard.restriction

/**
 * 限制策略：把「默认限制 / 手动限制 / 白名单 / 系统保护」合成一个判断。
 *
 * - 系统关键应用（电话、短信、设置、桌面、本应用）永远不受限；
 * - 白名单（始终允许）永远不受限；
 * - [restrictByDefault] = true 时，其余应用默认全部受限；
 * - [restrictByDefault] = false 时，只限制用户手动勾选的应用。
 */
object RestrictionPolicy {

    fun isRestricted(
        packageName: String,
        allowedPackages: Set<String>,
        explicitBlocked: Set<String>,
        restrictByDefault: Boolean,
        protectedPackages: Set<String>,
    ): Boolean = effective(
        packageName, allowedPackages, explicitBlocked, restrictByDefault, protectedPackages,
    ).restricted

    /** 统一入口：返回是否受限 + 原因（UI / Agent / GuardService 共用） */
    fun effective(
        packageName: String,
        allowedPackages: Set<String>,
        explicitBlocked: Set<String>,
        restrictByDefault: Boolean,
        protectedPackages: Set<String>,
    ): EffectiveRestriction = EffectiveRestrictionResolver.resolve(
        packageName = packageName,
        allowedPackages = allowedPackages,
        explicitBlocked = explicitBlocked,
        restrictByDefault = restrictByDefault,
        protectedPackages = protectedPackages,
    )

    /**
     * 给 UI 用的状态：是否受限、是否因默认策略受限（而非手动勾选）、是否被白名单/系统保护豁免。
     */
    data class State(
        val restricted: Boolean,
        val byDefault: Boolean,
        val whitelisted: Boolean,
        val systemProtected: Boolean,
    )

    fun state(
        packageName: String,
        allowedPackages: Set<String>,
        explicitBlocked: Set<String>,
        restrictByDefault: Boolean,
        protectedPackages: Set<String>,
    ): State {
        val protected = RuleValidator.isProtected(packageName, protectedPackages)
        val whitelisted = packageName in allowedPackages
        val restricted = isRestricted(
            packageName, allowedPackages, explicitBlocked, restrictByDefault, protectedPackages,
        )
        val byDefault = restricted && restrictByDefault && packageName !in explicitBlocked
        return State(restricted, byDefault, whitelisted, protected)
    }
}
