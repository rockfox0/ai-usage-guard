package com.aid.usageguard.data.repo

import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 白名单仓储（v1.8.2）：在 [SettingsRepository] 之上加「每天最多添加 N 个」的限制。
 *
 * 规则：
 * - 只有「加入白名单」计数，「移出白名单」不计数；
 * - 已经在白名单里的应用重复确认不计数；
 * - 每天限制可在设置里调整（默认 1，0 = 不限制）；
 * - 计数按本地日期跨天重置。
 *
 * 所有入口（应用列表开关、ActionExecutor、AI 草案执行）都必须走这里，
 * 避免绕过限制直接写 DataStore。
 */
class WhitelistRepository(private val settings: SettingsRepository) {

    data class Quota(
        val limit: Int,
        val used: Int,
        val remaining: Int,
        val unlimited: Boolean,
    ) {
        val canAdd: Boolean get() = unlimited || remaining > 0
        fun describe(): String = when {
            unlimited -> "白名单添加不限次数"
            remaining <= 0 -> "今天已添加 $used/$limit 个应用，明天再试"
            else -> "今天还能添加 $remaining 个应用（已用 $used/$limit）"
        }
    }

    sealed interface AddResult {
        data class Added(val quota: Quota) : AddResult
        data object AlreadyAllowed : AddResult
        data class Rejected(val reason: String, val quota: Quota) : AddResult
    }

    /** 当前白名单添加额度（自动跨天重置） */
    suspend fun quota(): Quota = withContext(Dispatchers.IO) {
        val prefs = settings.current()
        val today = UsageEventSource.todayDate()
        val used = if (prefs.whitelistAddDate == today) prefs.whitelistAddCount else {
            runCatching { settings.resetWhitelistAddCounter(today) }
            return@withContext fresh(prefs, 0)
        }
        fresh(prefs, used)
    }

    private fun fresh(prefs: com.aid.usageguard.data.settings.AppSettings, used: Int): Quota {
        val limit = prefs.whitelistDailyLimit.coerceIn(0, 20)
        val unlimited = limit == 0
        return Quota(
            limit = limit,
            used = used,
            remaining = if (unlimited) Int.MAX_VALUE else (limit - used).coerceAtLeast(0),
            unlimited = unlimited,
        )
    }

    suspend fun add(packageName: String): AddResult = withContext(Dispatchers.IO) {
        val prefs = settings.current()
        if (packageName in prefs.allowedPackages) return@withContext AddResult.AlreadyAllowed

        val quota = quota()
        if (!quota.canAdd) {
            return@withContext AddResult.Rejected(
                reason = "今天最多只能添加 ${quota.limit} 个应用到白名单（已用 ${quota.used} 个），明天再试。" +
                    "可在设置里调整这个每日上限。",
                quota = quota,
            )
        }
        settings.setAllowed(packageName, true)
        val today = UsageEventSource.todayDate()
        settings.recordWhitelistAdd(today, quota.used)
        AddResult.Added(quota.copy(used = quota.used + 1, remaining = quota.remaining - 1))
    }

    /** 移出白名单：不消耗每日额度 */
    suspend fun remove(packageName: String) = withContext(Dispatchers.IO) {
        settings.setAllowed(packageName, false)
    }

    suspend fun setDailyLimit(limit: Int) = withContext(Dispatchers.IO) {
        settings.setWhitelistDailyLimit(limit)
    }
}
