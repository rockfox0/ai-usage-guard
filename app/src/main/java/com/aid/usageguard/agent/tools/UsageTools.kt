package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.model.AppUsageSummary
import org.json.JSONArray
import org.json.JSONObject

/**
 * 使用数据查询工具组（5 个）：
 * get_daily_usage / get_weekly_usage / get_usage_ranking / get_app_usage_history / get_user_preferences。
 *
 * 只暴露聚合结果（时长、次数、排行榜），不把原始 UsageEvents 明细发给模型。
 */
class UsageTools(
    private val usage: UsageRepository,
    private val source: UsageEventSource,
    private val settings: SettingsRepository,
) {

    val all: List<AgentTool> = listOf(
        DailyUsageTool(),
        WeeklyUsageTool(),
        RankingTool(),
        AppHistoryTool(),
        UserPreferencesTool(),
    )

    private inner class DailyUsageTool : AgentTool {
        override val name: String = "get_daily_usage"
        override val description: String =
            "查询指定日期（默认今天）的手机使用情况：总时长、目标、使用最多的 App 及占比。只返回聚合数据。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "date" to JsonSchema.string("日期，yyyy-MM-dd，缺省为今天"),
            ),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val date = arguments.dateArg(UsageEventSource.todayDate())
            val prefs = settings.current()
            val ranking = usage.rankingForDate(date)
            val total = ranking.sumOf { it.minutes }

            return JSONObject().apply {
                put("date", date)
                put("totalMinutes", total)
                put("totalText", ToolFormat.minutes(total))
                put("dailyTargetMinutes", prefs.dailyTargetMinutes)
                put("dailyTargetText", ToolFormat.minutes(prefs.dailyTargetMinutes.toLong()))
                put("overTargetMinutes", (total - prefs.dailyTargetMinutes).coerceAtLeast(0L))
                put("appCount", ranking.size)
                put("topApps", ranking.toJson(limit = 8, total = total))
                if (total <= 0L) put("note", "该日期没有记录到使用数据（可能未授权或当天还没使用手机）")
            }
        }
    }

    private inner class WeeklyUsageTool : AgentTool {
        override val name: String = "get_weekly_usage"
        override val description: String =
            "查询最近 7 天（含今天）每天的总使用时长、日均、最高的一天以及今天使用最多的 App。"

        override val parameters: JSONObject = JsonSchema.objectSchema(properties = emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val prefs = settings.current()
            val week = usage.weekTotals()
            val total = week.sumOf { it.minutes }
            val average = if (week.isEmpty()) 0L else total / week.size
            val maxDay = week.maxByOrNull { it.minutes }
            val risingDays = week.sortedBy { it.date }.zipWithNext().count { (a, b) -> b.minutes > a.minutes }
            val ranking = usage.todayRanking(5)

            return JSONObject().apply {
                put("from", week.firstOrNull()?.date ?: "")
                put("to", week.lastOrNull()?.date ?: "")
                put("dailyTargetMinutes", prefs.dailyTargetMinutes)
                put("totalMinutes", total)
                put("totalText", ToolFormat.minutes(total))
                put("averageMinutes", average)
                put("averageText", ToolFormat.minutes(average))
                put("maxDay", JSONObject().apply {
                    put("date", maxDay?.date ?: "")
                    put("minutes", maxDay?.minutes ?: 0L)
                    put("minutesText", ToolFormat.minutes(maxDay?.minutes ?: 0L))
                })
                put("risingDays", risingDays)
                put("days", JSONArray().apply {
                    week.forEach { day ->
                        put(JSONObject().apply {
                            put("date", day.date)
                            put("label", UsageEventSource.dayLabel(day.date))
                            put("minutes", day.minutes)
                            put("minutesText", ToolFormat.minutes(day.minutes))
                        })
                    }
                })
                put("todayTopApps", ranking.toJson(limit = 5, total = ranking.sumOf { it.minutes }))
            }
        }
    }

    private inner class RankingTool : AgentTool {
        override val name: String = "get_usage_ranking"
        override val description: String =
            "查询某一天（默认今天）的 App 使用时长排行榜，包含时长、打开次数和占比。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "date" to JsonSchema.string("日期，yyyy-MM-dd，缺省为今天"),
                "limit" to JsonSchema.integer("返回条数，1-20，缺省 10", minimum = 1, maximum = 20),
            ),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val date = arguments.dateArg(UsageEventSource.todayDate())
            val limit = (arguments.intArg("limit") ?: 10).coerceIn(1, 20)
            val ranking = usage.rankingForDate(date)
            val total = ranking.sumOf { it.minutes }

            return JSONObject().apply {
                put("date", date)
                put("limit", limit)
                put("totalMinutes", total)
                put("totalText", ToolFormat.minutes(total))
                put("apps", ranking.toJson(limit = limit, total = total))
                if (ranking.isEmpty()) put("note", "该日期没有记录到使用数据")
            }
        }
    }

    private inner class AppHistoryTool : AgentTool {
        override val name: String = "get_app_usage_history"
        override val description: String =
            "查询某个 App 最近几天的使用历史（今天/本周时长、日均、每日明细、今天打开次数）。" +
                "package_name 可以是包名（com.tencent.mm），也可以是应用名（微信）。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "package_name" to JsonSchema.string("应用包名或应用名，例如 com.tencent.mm / 微信"),
                "days" to JsonSchema.integer("查询最近几天，1-7，缺省 7", minimum = 1, maximum = 7),
            ),
            required = listOf("package_name"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val input = arguments.stringArg("package_name")
                ?: throw ToolArgumentException("缺少必填参数 package_name（包名或应用名）")
            val packageName = resolvePackageName(usage, source, input)
                ?: throw ToolArgumentException("没有找到应用「$input」。可以先用 get_usage_ranking 查准确的包名。")
            val days = (arguments.intArg("days") ?: 7).coerceIn(1, 7)
            val detail = usage.appDetail(packageName)
                ?: throw ToolArgumentException("没有查到「$input」的使用记录")

            return JSONObject().apply {
                put("packageName", packageName)
                put("appName", detail.appName)
                put("days", days)
                put("todayMinutes", detail.todayMinutes)
                put("todayText", ToolFormat.minutes(detail.todayMinutes))
                put("weekMinutes", detail.weekMinutes)
                put("weekText", ToolFormat.minutes(detail.weekMinutes))
                put("averageMinutes", detail.averageMinutes)
                put("averageText", ToolFormat.minutes(detail.averageMinutes))
                put("launchCountToday", detail.launchCount)
                put("lastUsed", detail.lastUsed)
                put("lastUsedText", ToolFormat.timeOfDay(detail.lastUsed))
                put("daily", JSONArray().apply {
                    detail.daily.takeLast(days).forEach { day ->
                        put(JSONObject().apply {
                            put("date", day.date)
                            put("label", UsageEventSource.dayLabel(day.date))
                            put("minutes", day.minutes)
                            put("minutesText", ToolFormat.minutes(day.minutes))
                        })
                    }
                })
                put("sessionCountToday", detail.sessions.size)
            }
        }
    }

    private inner class UserPreferencesTool : AgentTool {
        override val name: String = "get_user_preferences"
        override val description: String =
            "获取用户的设置：每日使用目标、免打扰时段、提醒与 AI 分析开关、提醒风格。不包含 API Key 等敏感信息。"

        override val parameters: JSONObject = JsonSchema.objectSchema(properties = emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val prefs = settings.current()
            return JSONObject().apply {
                put("dailyTargetMinutes", prefs.dailyTargetMinutes)
                put("dailyTargetText", ToolFormat.minutes(prefs.dailyTargetMinutes.toLong()))
                put("quietHoursStart", prefs.quietHoursStart)
                put("quietHoursEnd", prefs.quietHoursEnd)
                put(
                    "quietHoursText",
                    ToolFormat.clock(prefs.quietHoursStart) + " - " + ToolFormat.clock(prefs.quietHoursEnd),
                )
                put("notificationEnabled", prefs.notificationEnabled)
                put("aiAnalysisEnabled", prefs.aiAnalysisEnabled)
                put("reminderStyle", prefs.reminderStyle)
                put("notificationDailyReminder", prefs.notifyDailyReminder)
                put("notificationGoalExceeded", prefs.notifyGoalExceeded)
                put("notificationAnomaly", prefs.notifyAnomaly)
                put("themeMode", prefs.themeMode)
            }
        }
    }
}

private fun List<AppUsageSummary>.toJson(limit: Int, total: Long): JSONArray = JSONArray().apply {
    take(limit).forEach { app ->
        put(JSONObject().apply {
            put("appName", app.appName)
            put("packageName", app.packageName)
            put("minutes", app.minutes)
            put("minutesText", ToolFormat.minutes(app.minutes))
            put("launchCount", app.launchCount)
            put("sharePercent", if (total > 0) (app.minutes * 100.0 / total).toInt() else 0)
        })
    }
}
