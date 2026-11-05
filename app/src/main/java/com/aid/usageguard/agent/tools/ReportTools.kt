package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.BehaviorRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.report.LocalReportGenerator
import org.json.JSONObject

/**
 * 报告工具：generate_usage_report。
 *
 * 注意：它不会递归调用 AgentLoop（那会死循环），而是用本地规则引擎
 * （[LocalReportGenerator]）根据真实数据生成一份「草稿」，
 * 由模型在拿到其它工具数据后改写/润色成最终报告。
 */
class ReportTools(
    private val usage: UsageRepository,
    private val behavior: BehaviorRepository,
    private val settings: SettingsRepository,
) {

    val all: List<AgentTool> = listOf(GenerateReportTool())

    private inner class GenerateReportTool : AgentTool {
        override val name: String = "generate_usage_report"
        override val description: String =
            "基于本地真实统计生成日报/周报草稿与关键指标；不要把草稿原样当结论。"


        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "type" to JsonSchema.string("报告类型", enum = listOf("daily", "weekly")),
            ),
            required = listOf("type"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val rawType = arguments.stringArg("type")?.lowercase()
                ?: throw ToolArgumentException("缺少必填参数 type（daily 或 weekly）")
            val type = when (rawType) {
                "daily" -> "daily"
                "weekly" -> "weekly"
                else -> throw ToolArgumentException("type 只能是 daily 或 weekly")
            }

            val prefs = settings.current()
            val date = UsageEventSource.todayDate()
            val draft = if (type == "weekly") {
                LocalReportGenerator.weekly(usage, prefs)
            } else {
                LocalReportGenerator.daily(usage, behavior, prefs, date)
            }

            return JSONObject().apply {
                put("type", type)
                put("date", date)
                put("draft", draft)
                put(
                    "note",
                    "这是本地规则生成的草稿。请结合 get_daily_usage / get_weekly_usage / get_usage_ranking / " +
                        "get_behavior_events 返回的真实数据，用你自己的语言重写，保留具体数字但不要照抄。",
                )
            }
        }
    }
}
