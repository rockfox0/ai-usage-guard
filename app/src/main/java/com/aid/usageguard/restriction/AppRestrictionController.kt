package com.aid.usageguard.restriction

import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.data.db.RestrictionRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 统一限制接口。
 * 第一版只有无障碍实现；未来可以替换为 DeviceOwner 实现，上层（UI / Agent 建议）不受影响。
 * Agent 只能「建议」，真正执行必须经过用户确认与 [RuleValidator] 校验。
 */
interface AppRestrictionController {
    suspend fun block(packageName: String)
    suspend fun unblock(packageName: String)
    suspend fun isBlocked(packageName: String): Boolean
    fun observeBlocked(): Flow<Set<String>>
}

class AccessibilityRestrictionController(
    private val db: AppDatabase,
) : AppRestrictionController {

    override suspend fun block(packageName: String) = withContext(Dispatchers.IO) {
        val existing = db.restrictionDao().byPackage(packageName)
        db.restrictionDao().upsert(
            existing?.copy(enabled = true)
                ?: RestrictionRuleEntity(packageName = packageName, enabled = true),
        )
    }

    override suspend fun unblock(packageName: String) = withContext(Dispatchers.IO) {
        db.restrictionDao().delete(packageName)
    }

    override suspend fun isBlocked(packageName: String): Boolean = withContext(Dispatchers.IO) {
        db.restrictionDao().byPackage(packageName)?.enabled == true
    }

    override fun observeBlocked(): Flow<Set<String>> =
        db.restrictionDao().observeAll().map { rules ->
            rules.filter { it.enabled }.map { it.packageName }.toSet()
        }
}

data class ValidationResult(
    val allowed: Set<String>,
    val rejected: Map<String, String>,
)

/**
 * 规则校验：电话、短信、系统设置、桌面等关键应用不允许被限制；
 * 未安装的应用也不允许进入规则表。
 */
object RuleValidator {

    private val protectedPrefixes = listOf(
        "com.android.settings",
        "com.android.dialer",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.systemui",
        "com.google.android.dialer",
        "com.android.incallui",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.aid.usageguard",
    )

    /** 运行时补充的保护包（例如输入法、当前桌面），由 GuardService / AppWiring 注册 */
    private val runtimeProtected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun registerProtected(packages: Collection<String>) {
        runtimeProtected.addAll(packages)
    }

    fun isProtected(packageName: String, launcherPackages: Set<String>): Boolean {
        if (packageName in launcherPackages) return true
        if (packageName in runtimeProtected) return true
        return protectedPrefixes.any { packageName == it || packageName.startsWith("$it.") }
    }

    fun validate(
        requested: Set<String>,
        installed: Set<String>,
        launcherPackages: Set<String>,
    ): ValidationResult {
        val allowed = LinkedHashSet<String>()
        val rejected = LinkedHashMap<String, String>()
        for (pkg in requested) {
            when {
                pkg !in installed -> rejected[pkg] = "应用未安装"
                isProtected(pkg, launcherPackages) -> rejected[pkg] = "系统关键应用，不允许限制"
                else -> allowed.add(pkg)
            }
        }
        return ValidationResult(allowed, rejected)
    }
}
