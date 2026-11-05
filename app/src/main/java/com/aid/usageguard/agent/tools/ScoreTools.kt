package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.ScoreRepository
import com.aid.usageguard.domain.score.SlotMinutes
import com.aid.usageguard.domain.score.TimeSlot
import com.aid.usageguard.domain.score.UsageScore
import com.aid.usageguard.domain.score.WeekScore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 评分工具：get_usage_score。
 *
 * 把「今日打分 / 每周总结」（[ScoreRepository] + UsageScoreEngine 生成）暴露给模型：
 * - scope=today：分数、等级、个性化词条（headline）、判断句（verdict）、时长/目标、深夜时长、
 *   最长连续、打开次数、5 个时段分布、topApps，以及 summaryLines；
 * - scope=week：平均分、等级、词条、趋势、超目标/深夜天数、最好/最差的一天、每日简要数组。
 *
 * 只读工具：不落库、不改设置（ScoreRepository 的 todayScore/scoreFor/week 都是纯计算）。
 * 仓库实例通过 `{ Container.score }` 延迟获取：AgentLoop 构造时不需要新增参数，
 * 也能保证在 Container.init 之后才真正访问。
 */
class ScoreTools(
    private val scoreProvider: () -> ScoreRepository = { Container.score },
) {

    val all: List<AgentTool> = listOf(GetUsageScoreTool())

    // ------------------------------------------------------------------

    private inner class GetUsageScoreTool : AgentTool {
        override val name: String = "get_usage_score"

        override val description: String =
            "查询今日评分（scope=today）或每周总结（scope=week）：分数、等级、词条与建议依据。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "scope" to JsonSchema.string(
                    "today=今日评分（默认）；week=最近 7 天总结",
                    enum = listOf("today", "week"),
                ),
                "date" to JsonSchema.string(
                    "可选，yyyy-MM-dd；仅 scope=today 时生效，缺省为今天",
                    format = "date",
                ),
            ),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val scope = (arguments.stringArg("scope") ?: "today").lowercase()
            if (scope != "today" && scope != "week") {
                throw ToolArgumentException("scope 只能是 today 或 week，收到「$scope」")
            }
            val today = UsageEventSource.todayDate()
            return if (scope == "week") {
                buildWeekPayload()
            } else {
                buildDayPayload(arguments.dateArg(today))
            }
        }
    }

    // ------------------------------------------------------------------
    // 今日
    // ------------------------------------------------------------------

    private suspend fun buildDayPayload(date: String): JSONObject {
        val score = loadDay(date)
        val hasData = score.totalMinutes > 0L
        return JSONObject().apply {
            put("executed", false)
            put("changed", JSONObject())
            put("scope", "today")
            put("date", score.date)
            put("hasData", hasData)
            put("score", score.score)
            put("grade", score.grade)
            put("headline", score.headline)
            put("verdict", score.verdict)
            put("totalMinutes", score.totalMinutes)
            put("totalText", ToolFormat.minutes(score.totalMinutes))
            put("targetMinutes", score.targetMinutes)
            put("targetText", ToolFormat.minutes(score.targetMinutes.toLong()))
            put("targetPercent", (score.targetRatio * 100.0).toInt())
            put("lateNightMinutes", score.lateNightMinutes)
            put("longestSessionMinutes", score.longestSessionMinutes)
            put("launchCount", score.launchCount)
            put("slots", slotsJson(score.slots))
            put("topApps", topAppsJson(score.topApps))
            put("summaryLines", JSONArray(score.summaryLines()))
            put(
                "message",
                if (hasData) {
                    "「${score.date}」评分 ${score.score} 分（${score.grade}），词条「${score.headline}」；" +
                        "共使用 ${ToolFormat.minutes(score.totalMinutes)}。"
                } else {
                    "「${score.date}」还没有记录到使用数据，评分仅供参考。"
                },
            )
            put("settingsUrlHint", "可在「首页」查看今日评分")
        }
    }

    private suspend fun loadDay(date: String): UsageScore {
        val repo = runCatching { scoreProvider() }.getOrElse {
            throw ToolArgumentException("评分服务尚未就绪，请稍后再试。")
        }
        return runCatching { repo.scoreFor(date) }.getOrElse {
            throw ToolArgumentException("读取「$date」的评分失败：${it.message?.take(120) ?: "未知错误"}")
        }
    }

    private fun slotsJson(slots: List<SlotMinutes>): JSONArray {
        val bySlot = slots.associate { it.slot to it.minutes }
        return JSONArray().apply {
            TimeSlot.entries.forEach { slot ->
                val minutes = bySlot[slot] ?: 0L
                put(
                    JSONObject().apply {
                        put("key", slot.key)
                        put("label", slot.label)
                        put("minutes", minutes)
                        put("minutesText", ToolFormat.minutes(minutes))
                    },
                )
            }
        }
    }

    private fun topAppsJson(apps: List<Pair<String, Long>>): JSONArray = JSONArray().apply {
        apps.take(5).forEach { (name, minutes) ->
            put(
                JSONObject().apply {
                    put("appName", name)
                    put("minutes", minutes)
                    put("minutesText", ToolFormat.minutes(minutes))
                },
            )
        }
    }

    // ------------------------------------------------------------------
    // 周总结
    // ------------------------------------------------------------------

    private suspend fun buildWeekPayload(): JSONObject {
        val week = loadWeek()
        val hasData = week.days.any { it.totalMinutes > 0L }
        return JSONObject().apply {
            put("executed", false)
            put("changed", JSONObject())
            put("scope", "week")
            put("days", week.days.size)
            put("hasData", hasData)
            put("averageScore", week.averageScore)
            put("grade", week.grade)
            put("headline", week.headline)
            put("verdict", week.verdict)
            put("trend", week.trend)
            put("totalMinutes", week.totalMinutes)
            put("totalText", ToolFormat.minutes(week.totalMinutes))
            put("averageMinutes", week.averageMinutes)
            put("averageText", ToolFormat.minutes(week.averageMinutes))
            put("overTargetDays", week.overTargetDays)
            put("lateNightDays", week.lateNightDays)
            put("bestDay", week.bestDay?.let { highlightOf(it) } ?: JSONObject.NULL)
            put("worstDay", week.worstDay?.let { highlightOf(it) } ?: JSONObject.NULL)
            put("dailyBrief", JSONArray().apply { week.days.forEach { put(briefOf(it)) } })
            put("summaryLines", JSONArray(week.summaryLines()))
            put(
                "message",
                if (hasData) {
                    "最近 ${week.days.size} 天平均 ${week.averageScore} 分（${week.grade}），词条「${week.headline}」，" +
                        "趋势${week.trend}；超目标 ${week.overTargetDays} 天、深夜使用 ${week.lateNightDays} 天。"
                } else {
                    "最近 ${week.days.size} 天还没有可用的使用记录，先正常使用几天再来看看。"
                },
            )
            put("settingsUrlHint", "可在「首页」查看每周总结")
        }
    }

    private suspend fun loadWeek(): WeekScore {
        val repo = runCatching { scoreProvider() }.getOrElse {
            throw ToolArgumentException("评分服务尚未就绪，请稍后再试。")
        }
        return runCatching { repo.week(7) }.getOrElse {
            throw ToolArgumentException("读取本周评分失败：${it.message?.take(120) ?: "未知错误"}")
        }
    }
}

// ======================================================================
// 供周总结复用的扁平化 JSON（放在类外，避免 inner class / lambda 里的
// member-extension 接收者解析歧义）
// ======================================================================

private fun highlightOf(score: UsageScore): JSONObject = JSONObject().apply {
    put("date", score.date)
    put("score", score.score)
    put("grade", score.grade)
    put("headline", score.headline)
}

private fun briefOf(score: UsageScore): JSONObject = JSONObject().apply {
    put("date", score.date)
    put("score", score.score)
    put("grade", score.grade)
    put("headline", score.headline)
    put("minutes", score.totalMinutes)
    put("minutesText", ToolFormat.minutes(score.totalMinutes))
}
