package com.aid.usageguard.data.repo

import android.content.Context
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.UsageIngester
import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.domain.model.AppDetailModel
import com.aid.usageguard.domain.model.AppUsageSummary
import com.aid.usageguard.domain.model.DailyTotal
import com.aid.usageguard.domain.model.DataCoverage
import com.aid.usageguard.domain.model.DataStatus
import com.aid.usageguard.domain.model.SessionSlice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 使用数据仓储：负责增量采集（UsageIngester）与查询。
 * UI / Agent 只依赖这里的查询方法，不直接碰 Room 或 UsageStatsManager。
 */
class UsageRepository(
    private val context: Context,
    private val source: UsageEventSource,
    private val ingester: UsageIngester,
    private val db: AppDatabase,
) {

    fun hasUsageAccess(): Boolean = source.hasUsageAccess()

    /** 增量同步一次（页面打开、WorkManager 定时任务都会调用） */
    suspend fun refresh(): UsageIngester.IngestResult = ingester.ingest()

    /**
     * 数据覆盖检查（v1.7 方案 4.2）：请求范围 vs 实际有数据的日期。
     * 缺失日期不会被补零，调用方应只基于 [DataCoverage.validDates] 下结论。
     */
    suspend fun coverage(fromDate: String, toDate: String): DataCoverage = withContext(Dispatchers.IO) {
        val hasAccess = source.hasUsageAccess()
        val lastSyncedAt = db.syncStateDao().get(KEY_LAST_INGEST)
        val requested = dateRange(fromDate, toDate)
        val records = db.appUsageRecordDao().between(fromDate, toDate)
        val datesWithData = records.filter { it.totalDuration > 0 }.map { it.date }.toSet()
        val validDates = requested.filter { it in datesWithData }
        val missingDates = requested.filter { it !in datesWithData }
        val status = when {
            !hasAccess -> DataStatus.NO_PERMISSION
            lastSyncedAt == null -> DataStatus.NOT_SYNCED
            validDates.isEmpty() -> DataStatus.NOT_SYNCED
            missingDates.isNotEmpty() -> DataStatus.PARTIAL
            else -> DataStatus.VALID
        }
        DataCoverage(
            requestedFrom = fromDate,
            requestedTo = toDate,
            availableFrom = validDates.firstOrNull(),
            availableTo = validDates.lastOrNull(),
            validDates = validDates,
            missingDates = missingDates,
            lastSyncedAt = lastSyncedAt,
            status = status,
            timezone = java.util.TimeZone.getDefault().id,
        )
    }

    private fun dateRange(fromDate: String, toDate: String): List<String> = runCatching {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val from = format.parse(fromDate) ?: return emptyList()
        val to = format.parse(toDate) ?: return emptyList()
        val cal = java.util.Calendar.getInstance().apply { time = from }
        val end = java.util.Calendar.getInstance().apply { time = to }.timeInMillis
        val out = ArrayList<String>()
        while (cal.timeInMillis <= end && out.size < 90) {
            out += format.format(cal.time)
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        out
    }.getOrDefault(emptyList())

    suspend fun todayTotalMinutes(): Long = withContext(Dispatchers.IO) {
        db.appUsageRecordDao().totalForDate(UsageEventSource.todayDate()) / 60_000L
    }

    suspend fun rankingForDate(date: String, limit: Int = Int.MAX_VALUE): List<AppUsageSummary> =
        withContext(Dispatchers.IO) {
            val records = db.appUsageRecordDao().forDate(date)
            val infos = db.appInfoDao().all().associateBy { it.packageName }
            records.take(limit).map { record ->
                AppUsageSummary(
                    packageName = record.packageName,
                    appName = infos[record.packageName]?.appName ?: record.packageName,
                    minutes = record.totalDuration / 60_000L,
                    lastUsed = record.lastUsedTime,
                    launchCount = record.launchCount,
                )
            }
        }

    suspend fun todayRanking(limit: Int = Int.MAX_VALUE): List<AppUsageSummary> =
        rankingForDate(UsageEventSource.todayDate(), limit)

    /** 最近 7 天（含今天）每日总时长，缺失日期补 0 */
    suspend fun weekTotals(anchorDate: String = UsageEventSource.todayDate()): List<DailyTotal> =
        withContext(Dispatchers.IO) {
            val from = UsageEventSource.addDays(anchorDate, -6)
            val records = db.appUsageRecordDao().between(from, anchorDate)
            val byDate = records.groupBy { it.date }.mapValues { (_, list) -> list.sumOf { it.totalDuration } }
            (0..6).map { offset ->
                val date = UsageEventSource.addDays(from, offset)
                DailyTotal(date, (byDate[date] ?: 0L) / 60_000L)
            }
        }

    /** 任意日期区间的每日总量（只返回有数据的日期，缺失日不补零） */
    suspend fun dailyTotals(fromDate: String, toDate: String): List<DailyTotal> =
        withContext(Dispatchers.IO) {
            db.appUsageRecordDao().between(fromDate, toDate)
                .groupBy { it.date }
                .map { (date, records) -> DailyTotal(date = date, minutes = records.sumOf { it.totalDuration } / 60_000L) }
                .sortedBy { it.date }
        }

    /** 最近 [days] 天（含今天）的每日总量 */
    suspend fun dailyTotalsForLastDays(days: Int): List<DailyTotal> = withContext(Dispatchers.IO) {
        val safeDays = days.coerceIn(1, 90)
        val end = UsageEventSource.todayDate()
        val from = UsageEventSource.dateOf(
            System.currentTimeMillis() - (safeDays - 1) * 24L * 60 * 60 * 1000,
        )
        dailyTotals(from, end)
    }

    /** 区间内按应用汇总的排行（30 天分析用） */
    suspend fun rankingForRange(
        fromDate: String,
        toDate: String,
        limit: Int = Int.MAX_VALUE,
    ): List<AppUsageSummary> = withContext(Dispatchers.IO) {
        val records = db.appUsageRecordDao().between(fromDate, toDate)
        val infos = db.appInfoDao().all().associateBy { it.packageName }
        records.groupBy { it.packageName }
            .map { (pkg, list) ->
                AppUsageSummary(
                    packageName = pkg,
                    appName = infos[pkg]?.appName ?: pkg,
                    minutes = list.sumOf { it.totalDuration } / 60_000L,
                    lastUsed = list.maxOf { it.lastUsedTime },
                    launchCount = list.sumOf { it.launchCount },
                )
            }
            .sortedByDescending { it.minutes }
            .take(limit)
    }

    suspend fun appDetail(packageName: String): AppDetailModel? = withContext(Dispatchers.IO) {
        val today = UsageEventSource.todayDate()
        val from = UsageEventSource.addDays(today, -6)
        val records = db.appUsageRecordDao().forApp(packageName, 30)
        val info = db.appInfoDao().byName(packageName)
        val todayRecord = db.appUsageRecordDao().forDate(today).firstOrNull { it.packageName == packageName }
        val weekRecords = records.filter { it.date in from..today }
        val weekMinutes = weekRecords.sumOf { it.totalDuration } / 60_000L
        val sessions = db.usageSessionDao().forDate(today)
            .filter { it.packageName == packageName }
            .sortedByDescending { it.startTime }
            .take(20)
            .map { SessionSlice(it.startTime, it.endTime, it.duration / 60_000L) }

        AppDetailModel(
            packageName = packageName,
            appName = info?.appName ?: source.appNameOf(packageName) ?: packageName,
            todayMinutes = (todayRecord?.totalDuration ?: 0L) / 60_000L,
            weekMinutes = weekMinutes,
            averageMinutes = if (weekRecords.isEmpty()) 0L else weekMinutes / weekRecords.size,
            launchCount = todayRecord?.launchCount ?: sessions.size,
            lastUsed = todayRecord?.lastUsedTime ?: records.firstOrNull()?.lastUsedTime ?: 0L,
            daily = (0..6).map { offset ->
                val date = UsageEventSource.addDays(from, offset)
                DailyTotal(date, (records.firstOrNull { it.date == date }?.totalDuration ?: 0L) / 60_000L)
            },
            sessions = sessions,
        )
    }

    suspend fun launchCount(date: String): Map<String, Int> = withContext(Dispatchers.IO) {
        db.appUsageRecordDao().forDate(date).associate { it.packageName to it.launchCount }
    }

    suspend fun sessionsOf(date: String): List<SessionSlice> = withContext(Dispatchers.IO) {
        db.usageSessionDao().forDate(date).map {
            SessionSlice(it.startTime, it.endTime, it.duration / 60_000L)
        }
    }

    suspend fun sessionCount(): Int = withContext(Dispatchers.IO) { db.usageSessionDao().count() }
}

private const val KEY_LAST_INGEST = "last_ingest_at"
