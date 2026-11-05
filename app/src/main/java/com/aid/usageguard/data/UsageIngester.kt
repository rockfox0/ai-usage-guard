package com.aid.usageguard.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.domain.aggregate.DailyAggregator
import com.aid.usageguard.domain.aggregate.SessionRange
import com.aid.usageguard.data.db.AppInfoEntity
import com.aid.usageguard.data.db.AppUsageRecordEntity
import com.aid.usageguard.data.db.SyncStateEntity
import com.aid.usageguard.data.db.UsageSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * UsageEvents -> 会话（UsageSession）-> 每日聚合（AppUsageRecord）。
 *
 * 设计要点：
 * - 使用「滚动窗口 + 先删后插」保证幂等：每次同步删除窗口内的会话并重算，
 *   这样即使上次同步时有应用一直停留在前台（会话未收口），也会被正确修正。
 * - 首轮回填 7 天，之后默认回看 24 小时（有更大的空档时按空档补齐）。
 * - 熄屏、桌面、本应用自身都不计入。
 */
class UsageIngester(
    private val context: Context,
    private val source: UsageEventSource,
    private val db: AppDatabase,
) {

    data class IngestResult(
        val sessions: Int,
        val daysTouched: Int,
        val windowFrom: Long,
        val windowTo: Long,
        val hasAccess: Boolean,
    )

    suspend fun ingest(): IngestResult = withContext(Dispatchers.IO) {
        if (!source.hasUsageAccess()) {
            return@withContext IngestResult(0, 0, 0, 0, false)
        }
        val now = System.currentTimeMillis()
        val stateDao = db.syncStateDao()
        val lastIngest = stateDao.get(KEY_LAST_INGEST)
        // 窗口向前多回看 OVERLAP_MS：覆盖「开始于窗口前、结束于窗口内」的会话（方案 T03）
        val base = lastIngest ?: (now - FIRST_BACKFILL_MS)
        val queryFrom = maxOf(now - MAX_WINDOW_MS, base - OVERLAP_MS)

        val homePkgs = source.launcherPackages()
        val excluded = homePkgs + context.packageName

        val events = source.readEvents(queryFrom, now)
        val sessions = buildSessions(events, excluded, queryFrom, now)
        val appInfos = sessions.map { it.packageName }.distinct().mapNotNull { pkg ->
            val name = source.appNameOf(pkg) ?: return@mapNotNull null
            AppInfoEntity(
                packageName = pkg,
                appName = name,
                firstInstallTime = source.firstInstallTime(pkg),
            )
        }

        var touchedDays = 0
        db.withTransaction {
            db.usageSessionDao().deleteFrom(queryFrom)
            if (sessions.isNotEmpty()) db.usageSessionDao().insertAll(sessions)
            db.appInfoDao().upsertAll(appInfos)

            // 重算受影响的全部日期（含窗口前的重叠会话所在日期），避免旧聚合残留
            touchedDays = recomputeDates(
                UsageEventSource.dateOf(queryFrom),
                UsageEventSource.dateOf(now),
            )
            stateDao.put(SyncStateEntity(KEY_LAST_INGEST, now))
        }
        Log.d(
            "UsageIngester",
            "ingest done: sessions=${sessions.size} days=$touchedDays window=[${queryFrom}, $now]",
        )
        IngestResult(sessions.size, touchedDays, queryFrom, now, true)
    }

    /**
     * 按真实会话区间重算 [fromDate, toDate] 每天的应用聚合。
     * 跨午夜的会话在两天各计其重叠时长，启动次数仍按会话开始日归属。
     */
    private suspend fun recomputeDates(fromDate: String, toDate: String): Int {
        val end = UsageEventSource.startOfDay(toDate) + UsageEventSource.DAY_MS
        var dayStart = UsageEventSource.startOfDay(fromDate)
        var days = 0
        while (dayStart < end) {
            val dayEnd = dayStart + UsageEventSource.DAY_MS
            val date = UsageEventSource.dateOf(dayStart)
            val sessions = db.usageSessionDao().overlapping(dayStart, dayEnd)
            val slices = DailyAggregator.aggregate(
                sessions.map { SessionRange(it.packageName, it.startTime, it.endTime) },
                dayStart,
                dayEnd,
            )
            db.appUsageRecordDao().deleteBetween(date, date)
            if (slices.isNotEmpty()) {
                db.appUsageRecordDao().upsertAll(
                    slices.map { slice ->
                        AppUsageRecordEntity(
                            packageName = slice.packageName,
                            date = date,
                            totalDuration = slice.durationMs,
                            launchCount = slice.launchCount,
                            lastUsedTime = slice.lastUsedMs,
                        )
                    },
                )
            }
            days++
            dayStart = dayEnd
        }
        return days
    }

    private fun buildSessions(
        events: List<RawUsageEvent>,
        excluded: Set<String>,
        cut: Long,
        now: Long,
    ): List<UsageSessionEntity> {
        val openStart = HashMap<String, Long>()
        val closed = ArrayList<UsageSessionEntity>()

        fun close(pkg: String, end: Long) {
            val start = openStart.remove(pkg) ?: return
            val duration = (end - start).coerceAtLeast(0L)
            if (duration < MIN_SESSION_MS) return
            closed.add(
                UsageSessionEntity(
                    packageName = pkg,
                    startTime = start,
                    endTime = end,
                    duration = duration,
                    date = UsageEventSource.dateOf(start),
                ),
            )
        }

        for (event in events) {
            val pkg = event.packageName
            when (event.type) {
                FOREGROUND -> {
                    if (pkg in excluded) continue
                    openStart[pkg] = event.time
                }
                BACKGROUND -> close(pkg, event.time)
                SCREEN_OFF -> {
                    for (p in openStart.keys.toList()) close(p, event.time)
                }
            }
        }
        // 仍在进行中的会话：先按回到「当前时刻」计算，下次同步会在窗口内重算修正
        for (pkg in openStart.keys.toList()) close(pkg, now)
        return closed
    }

    companion object {
        private const val KEY_LAST_INGEST = "last_ingest_at"
        private const val MIN_SESSION_MS = 1_000L
        private const val FIRST_BACKFILL_MS = 30L * 24 * 60 * 60 * 1000

        /** 单次回看上限（8 天），避免长时间未同步时拉取过多事件 */
        private const val MAX_WINDOW_MS = 31L * 24 * 60 * 60 * 1000

        /** 窗口重叠回看：捕获开始于上次同步之前、结束于本次窗口内的会话 */
        private const val OVERLAP_MS = 6L * 60 * 60 * 1000

        private const val FOREGROUND = 1 // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
        private const val BACKGROUND = 2 // MOVE_TO_BACKGROUND / ACTIVITY_PAUSED
        private const val SCREEN_OFF = 16 // SCREEN_NON_INTERACTIVE (API 28+)
    }
}
