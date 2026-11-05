package com.aid.usageguard.data.repo

import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.data.db.AppDailyLimitEntity
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.quota.QuotaPolicy
import com.aid.usageguard.domain.quota.QuotaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 时长额度仓储（v1.8）：
 * - 全局每日额度复用「每日目标」分钟数 + `dailyBudgetEnforced` 开关；
 * - 单应用每日限额存 `app_daily_limit` 表。
 */
class QuotaRepository(
    private val db: AppDatabase,
    private val usage: UsageRepository,
    private val settings: SettingsRepository,
) {

    suspend fun limits(): Map<String, Int> = withContext(Dispatchers.IO) {
        db.appDailyLimitDao().all().associate { it.packageName to it.dailyLimitMinutes }
    }

    suspend fun limitOf(packageName: String): Int? = withContext(Dispatchers.IO) {
        db.appDailyLimitDao().byName(packageName)?.dailyLimitMinutes
    }

    /** minutes <= 0 表示清除该应用的限额 */
    suspend fun setLimit(packageName: String, minutes: Int) = withContext(Dispatchers.IO) {
        if (minutes <= 0) {
            db.appDailyLimitDao().delete(packageName)
        } else {
            db.appDailyLimitDao().upsert(
                AppDailyLimitEntity(
                    packageName = packageName,
                    dailyLimitMinutes = minutes.coerceIn(1, 24 * 60),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) { db.appDailyLimitDao().clear() }

    /** 今天已用：总分钟 + 各应用分钟（来自本地聚合表） */
    suspend fun todayUsage(): UsageSnapshot = withContext(Dispatchers.IO) {
        val today = UsageEventSource.todayDate()
        val records = db.appUsageRecordDao().forDate(today)
        UsageSnapshot(
            totalMinutes = records.sumOf { it.totalDuration } / 60_000L,
            perAppMinutes = records.associate { it.packageName to it.totalDuration / 60_000L },
        )
    }

    data class UsageSnapshot(val totalMinutes: Long, val perAppMinutes: Map<String, Long>)

    /** 计算某应用当前额度状态（UI / AI / Service 共用同一份策略） */
    suspend fun status(packageName: String, allowedPackages: Set<String>? = null,
                       protectedPackages: Set<String> = emptySet()): QuotaState =
        withContext(Dispatchers.IO) {
            val prefs = settings.current()
            val snapshot = todayUsage()
            val limit = limitOf(packageName)
            QuotaPolicy.evaluate(
                packageName = packageName,
                totalUsedMinutes = snapshot.totalMinutes,
                globalBudgetMinutes = prefs.dailyTargetMinutes,
                globalEnforced = prefs.dailyBudgetEnforced,
                appUsedMinutes = snapshot.perAppMinutes[packageName] ?: 0L,
                appLimitMinutes = limit,
                whitelisted = packageName in (allowedPackages ?: prefs.allowedPackages),
                protected = packageName in protectedPackages,
            )
        }
}
