package com.aid.usageguard.data.repo

import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.data.db.DailyScoreEntity
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.model.DataStatus
import com.aid.usageguard.domain.model.SessionSlice
import com.aid.usageguard.domain.score.ScorePersona
import com.aid.usageguard.domain.score.SlotMinutes
import com.aid.usageguard.domain.score.TimeSlot
import com.aid.usageguard.domain.score.UsageScore
import com.aid.usageguard.domain.score.UsageScoreEngine
import com.aid.usageguard.domain.score.UsageSnapshot
import com.aid.usageguard.domain.score.WeekScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 打分仓库：把 Room 里的会话/聚合数据 + 用户设置，交给 [UsageScoreEngine] 算出
 * 「今日分数 + 个性化判断词条 + 时段分布」，并支持落库与一周总结。
 */
class ScoreRepository(
    private val db: AppDatabase,
    private val usage: UsageRepository,
    private val settings: SettingsRepository,
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun today(): String = dateFormat.format(Calendar.getInstance().time)

    private fun dateOffset(days: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return dateFormat.format(cal.time)
    }

    private fun personaOf(prefs: AppSettings): ScorePersona =
        ScorePersona(nickname = prefs.aiNickname, tone = prefs.aiTone)

    /** 计算某一天的评分（不落库） */
    suspend fun scoreFor(date: String): UsageScore = withContext(Dispatchers.IO) {
        val prefs = settings.current()
        val snapshot = snapshotOf(date, prefs)
        UsageScoreEngine.score(snapshot, personaOf(prefs))
    }

    suspend fun todayScore(): UsageScore = scoreFor(today())

    /** 计算并写入 daily_score（供周总结 / 历史趋势 / 通知复用） */
    suspend fun persist(date: String = today()): UsageScore = withContext(Dispatchers.IO) {
        val score = scoreFor(date)
        db.dailyScoreDao().upsert(
            DailyScoreEntity(
                date = score.date,
                score = score.score,
                grade = score.grade,
                headline = score.headline,
                verdict = score.verdict,
                totalMinutes = score.totalMinutes,
                targetMinutes = score.targetMinutes,
                lateNightMinutes = score.lateNightMinutes,
                longestSessionMinutes = score.longestSessionMinutes,
                launchCount = score.launchCount,
                slotsJson = slotsToJson(score.slots),
                updatedAt = System.currentTimeMillis(),
                algorithmVersion = ALGORITHM_VERSION,
                dataStatus = score.dataStatus,
            ),
        )
        score
    }

    /** 最近 [days] 天（含今天）的一周总结 */
    suspend fun week(days: Int = 7): WeekScore = withContext(Dispatchers.IO) {
        val prefs = settings.current()
        val all = (days - 1 downTo 0).map { offset -> scoreFor(dateOffset(offset)) }
        // 只有有效数据参与周平均，缺失日期不能按满分抬高平均（方案 4.4）
        val valid = all.filter { it.scoreAvailable }
        UsageScoreEngine.week(valid, personaOf(prefs))
    }

    /** 已落库的历史分数（新→旧） */
    suspend fun persisted(limit: Int = 30): List<DailyScoreEntity> =
        withContext(Dispatchers.IO) { db.dailyScoreDao().recent(limit) }

    suspend fun persistedSince(date: String): List<DailyScoreEntity> =
        withContext(Dispatchers.IO) { db.dailyScoreDao().since(date) }

    // ------------------------------------------------------------------

    private suspend fun snapshotOf(date: String, prefs: AppSettings): UsageSnapshot {
        val sessions = runCatching { usage.sessionsOf(date) }.getOrDefault(emptyList())
        val ranking = runCatching { usage.rankingForDate(date, 5) }.getOrDefault(emptyList())
        val launches = runCatching { usage.launchCount(date).values.sum() }.getOrDefault(0)

        val slotMillis = LinkedHashMap<TimeSlot, Long>()
        var lateMillis = 0L
        var longest = 0L
        sessions.forEach { session ->
            distribute(session, slotMillis) { lateDelta ->
                lateMillis += lateDelta
            }
            longest = maxOf(longest, session.minutes)
        }

        val sessionMinutes = slotMillis.values.sum() / 60_000L
        val rankingMinutes = ranking.sumOf { it.minutes }
        val totalMinutes = if (sessionMinutes > 0) sessionMinutes else rankingMinutes

        val today = today()
        val coverage = runCatching { usage.coverage(date, date) }.getOrNull()
        val dataStatus = when {
            coverage == null -> if (sessions.isNotEmpty()) DataStatus.VALID.name else DataStatus.NOT_SYNCED.name
            coverage.status == DataStatus.NO_PERMISSION -> DataStatus.NO_PERMISSION.name
            coverage.status == DataStatus.NOT_SYNCED -> DataStatus.NOT_SYNCED.name
            coverage.status == DataStatus.FAILED -> DataStatus.FAILED.name
            sessions.isEmpty() && totalMinutes == 0L -> DataStatus.NOT_SYNCED.name
            coverage.validDates.isEmpty() && date == today -> DataStatus.NOT_SYNCED.name
            coverage.status == DataStatus.PARTIAL -> DataStatus.PARTIAL.name
            else -> DataStatus.VALID.name
        }

        return UsageSnapshot(
            date = date,
            totalMinutes = totalMinutes,
            targetMinutes = prefs.dailyTargetMinutes,
            lateNightMinutes = lateMillis / 60_000L,
            longestSessionMinutes = longest,
            launchCount = launches,
            slots = slotMillis.mapValues { (_, millis) -> millis / 60_000L },
            topApps = ranking.take(3).map { it.appName to it.minutes },
            dataStatus = dataStatus,
        )
    }

    /**
     * 把一次会话的时长按「小时」切分到对应时段，同时累计深夜时长（23:00–05:00）。
     */
    private fun distribute(
        session: SessionSlice,
        out: MutableMap<TimeSlot, Long>,
        onLate: (Long) -> Unit,
    ) {
        var cursor = session.startTime
        val end = session.endTime
        if (end <= cursor) return
        val cal = Calendar.getInstance()
        while (cursor < end) {
            cal.timeInMillis = cursor
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val hourEnd = ((cursor / 3_600_000L) + 1) * 3_600_000L
            val chunkEnd = minOf(end, hourEnd)
            val duration = chunkEnd - cursor
            if (duration > 0) {
                val slot = slotOfHour(hour)
                out[slot] = (out[slot] ?: 0L) + duration
                if (hour == 23 || hour <= 4) onLate(duration)
            }
            cursor = chunkEnd
        }
    }

    private fun slotOfHour(hour: Int): TimeSlot = when (hour) {
        in 5..8 -> TimeSlot.EARLY
        in 9..11 -> TimeSlot.MORNING
        in 12..17 -> TimeSlot.AFTERNOON
        in 18..22 -> TimeSlot.EVENING
        else -> TimeSlot.NIGHT
    }

    private fun slotsToJson(slots: List<SlotMinutes>): String = JSONObject().apply {
        slots.forEach { put(it.slot.key, it.minutes) }
    }.toString()
}

/** 评分算法版本：写入 daily_score，便于区分旧算法记录 */
const val ALGORITHM_VERSION = 1
