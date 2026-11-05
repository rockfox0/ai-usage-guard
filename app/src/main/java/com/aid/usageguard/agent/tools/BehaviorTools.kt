package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.BehaviorRepository
import com.aid.usageguard.domain.model.BehaviorEventModel
import com.aid.usageguard.domain.model.BehaviorEventType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 异常行为查询工具：get_behavior_events。
 * 事件来自本地规则引擎（domain.behavior），这里只做读取与格式化，不触发新的分析写入。
 */
class BehaviorTools(
    private val behavior: BehaviorRepository,
) {

    val all: List<AgentTool> = listOf(BehaviorEventsTool())

    private inner class BehaviorEventsTool : AgentTool {
        override val name: String = "get_behavior_events"
        override val description: String =
            "获取某一天（默认今天）本地检测到的异常使用行为，例如深夜使用、单次使用过长、使用时长明显上升等。"

        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "date" to JsonSchema.string("日期，yyyy-MM-dd，缺省为今天", format = "date"),
            ),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val today = UsageEventSource.todayDate()
            val date = arguments.dateArg(today)
            val events = behavior.forDate(date).ifEmpty {
                runCatching { behavior.recent(30) }.getOrDefault(emptyList()).filter { it.date == date }
            }

            return JSONObject().apply {
                put("date", date)
                put("count", events.size)
                put("events", JSONArray().apply {
                    events.take(15).forEach { event -> put(event.toJson()) }
                })
                if (events.isEmpty()) {
                    put("note", "该日期没有检测到异常行为（可能本地分析尚未运行，或当天使用较平稳）")
                }
            }
        }
    }

    private fun BehaviorEventModel.toJson(): JSONObject = JSONObject().apply {
        put("type", eventType.name)
        put("typeText", eventType.label())
        put("severity", severity.name)
        put("appName", packageName ?: "全部应用")
        put("packageName", packageName ?: "")
        put("description", description)
        put("date", date)
        put("time", ToolFormat.timeOfDay(timestamp))
    }

    private fun BehaviorEventType.label(): String = when (this) {
        BehaviorEventType.USAGE_INCREASE -> "使用时长明显上升"
        BehaviorEventType.LONG_SESSION -> "单次连续使用过长"
        BehaviorEventType.LATE_NIGHT -> "深夜使用"
        BehaviorEventType.FREQUENT_LAUNCH -> "短时间内频繁打开"
        BehaviorEventType.OVER_TOTAL_TARGET -> "总时长超出目标"
        BehaviorEventType.MULTI_DAY_GROWTH -> "连续多日增长"
    }
}
