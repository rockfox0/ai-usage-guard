package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.ActionDraftRepository
import com.aid.usageguard.data.repo.MonitorRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.domain.monitor.MonitorAction
import com.aid.usageguard.domain.monitor.MonitorDefinition
import com.aid.usageguard.domain.monitor.MonitorEngine
import com.aid.usageguard.domain.monitor.MonitorMetric
import com.aid.usageguard.domain.monitor.MonitorOperator
import com.aid.usageguard.domain.monitor.MonitorSchedule
import com.aid.usageguard.domain.monitor.MonitorValidator
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户自定义监控工具（v1.9 Phase 5）。
 *
 * 分工：AI 负责把自然语言变成结构化规则 + 解释；本地 [MonitorEngine] 负责定时计算与通知。
 * 创建/删除走待确认草案（PROPOSE），用户确认后由 ActionExecutor 落库，避免「AI 悄悄加了规则」。
 */
class MonitorTools(
    private val usage: UsageRepository,
    private val source: UsageEventSource,
    private val monitorsProvider: () -> MonitorRepository = { Container.monitors },
    private val actionDraftsProvider: () -> ActionDraftRepository = { Container.actionDrafts },
    private val taskIdProvider: () -> String = { "" },
) {

    val all: List<AgentTool> = listOf(
        CreateMonitorTool(),
        ListMonitorsTool(),
        RemoveMonitorTool(),
        MonitorHistoryTool(),
    )

    private fun monitors(): MonitorRepository = runCatching { monitorsProvider() }.getOrElse {
        throw ToolArgumentException("监控服务尚未就绪，请稍后再试")
    }

    private inner class CreateMonitorTool : AgentTool {
        override val name: String = "create_monitor"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String =
            "创建自定义监控规则草案：指定应用（或全部应用）、指标（使用时长/启动次数）、阈值、周期（每天/工作日/周末）。" +
                "触发后由本地引擎发通知，不需要模型持续判断。"
        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "app" to JsonSchema.string(
                    "应用包名或应用名；想看全设备合计就填「全部应用」",
                    format = "app_ref",
                ),
                "metric" to JsonSchema.string(
                    "指标：duration=使用时长，launch=启动次数",
                    enum = listOf("duration", "launch"),
                ),
                "operator" to JsonSchema.string(
                    "比较方式：gt=超过，gte=达到，lt=低于",
                    enum = listOf("gt", "gte", "lt"),
                ),
                "threshold" to JsonSchema.integer(
                    "阈值：duration 为分钟（5-1440），launch 为次数（1-500）",
                    minimum = 1,
                    maximum = 1440,
                ),
                "schedule" to JsonSchema.string(
                    "生效周期：daily=每天，weekdays=工作日，weekends=周末",
                    enum = listOf("daily", "weekdays", "weekends"),
                ),
                "name" to JsonSchema.string("规则名称（可空，默认自动生成）"),
            ),
            required = listOf("app", "metric", "operator", "threshold", "schedule"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val appInput = arguments.stringArg("app")?.trim().orEmpty()
            if (appInput.isEmpty()) throw ToolArgumentException("缺少必填参数 app（应用名/包名/全部应用）")
            val metric = when (arguments.stringArg("metric")?.trim()?.lowercase()) {
                "duration", "foreground_duration" -> MonitorMetric.FOREGROUND_DURATION
                "launch", "launch_count" -> MonitorMetric.LAUNCH_COUNT
                else -> throw ToolArgumentException("metric 只能是 duration 或 launch")
            }
            val operator = when (arguments.stringArg("operator")?.trim()?.lowercase()) {
                "gt", ">", "greater_than" -> MonitorOperator.GREATER_THAN
                "gte", ">=", "greater_equal" -> MonitorOperator.GREATER_EQUAL
                "lt", "<", "less_than" -> MonitorOperator.LESS_THAN
                else -> throw ToolArgumentException("operator 只能是 gt / gte / lt")
            }
            val threshold = arguments.intArg("threshold")?.toLong()
                ?: throw ToolArgumentException("缺少必填参数 threshold")

            val isAllApps = appInput in setOf("全部应用", "所有应用", "all", "全部")
            val packageName = if (isAllApps) {
                null
            } else {
                resolvePackageName(usage, source, appInput)
                    ?: throw ToolArgumentException(
                        "没有找到应用「$appInput」。可以先用 get_usage_ranking 查准确的包名。",
                    )
            }
            val appName = packageName?.let { source.appNameOf(it) ?: it } ?: "全部应用"
            val schedule = when (arguments.stringArg("schedule")?.trim()?.lowercase()) {
                "daily", "每天" -> MonitorSchedule.EVERY_DAY
                "weekdays", "工作日" -> MonitorSchedule.WEEKDAYS
                "weekends", "周末" -> MonitorSchedule.WEEKENDS
                else -> throw ToolArgumentException("schedule 只能是 daily / weekdays / weekends")
            }
            val name = arguments.stringArg("name")?.trim().orEmpty().ifBlank {
                "监控：$appName" + when (metric) {
                    MonitorMetric.FOREGROUND_DURATION -> "使用时长"
                    MonitorMetric.LAUNCH_COUNT -> "启动次数"
                }
            }
            val draft = MonitorDefinition(
                id = "",
                name = name,
                packageName = packageName,
                metric = metric,
                operator = operator,
                threshold = threshold,
                schedule = schedule,
                action = MonitorAction.NOTIFY,
            )
            val existing = runCatching { monitors().all() }.getOrDefault(emptyList())
            when (val validation = MonitorValidator.validate(draft, existing)) {
                is MonitorValidator.Result.Rejected -> throw ToolArgumentException(validation.reason)
                MonitorValidator.Result.Ok -> Unit
            }
            val describe = MonitorValidator.describe(draft) { pkg -> source.appNameOf(pkg) }

            val drafts = runCatching { actionDraftsProvider() }.getOrElse {
                throw ToolArgumentException("待确认操作服务尚未就绪，请稍后再试")
            }
            val taskId = runCatching { taskIdProvider() }.getOrDefault("").ifBlank { "agent-task" }
            val params = JSONObject()
                .put("name", draft.name)
                .put("package_name", draft.packageName.orEmpty())
                .put("metric", draft.metric.name)
                .put("operator", draft.operator.name)
                .put("threshold", draft.threshold)
                .put("schedule", draft.schedule.name)
                .put("action", draft.action.name)
                .put("time_window", draft.timeWindow)
            val entity = runCatching {
                drafts.propose(
                    taskId = taskId,
                    type = TYPE_CREATE,
                    packages = setOfNotNull(packageName),
                    paramsJson = params.toString(),
                    beforeJson = JSONObject().put("rules", existing.count { it.enabled }).toString(),
                    afterJson = JSONObject().put("rule", describe).toString(),
                )
            }.getOrElse {
                throw ToolArgumentException("生成监控草案失败：${it.message?.take(120) ?: "未知错误"}")
            }
            return JSONObject().apply {
                put("executed", false)
                put("requiresConfirmation", true)
                put("opId", entity.opId)
                put("type", entity.type)
                put("expiresAt", entity.expiresAt)
                put("rule", describe)
                put("message", "已生成监控规则草案：$describe。请在应用内确认后生效。")
            }
        }
    }

    private inner class ListMonitorsTool : AgentTool {
        override val name: String = "list_monitors"
        override val kind: ToolKind = ToolKind.QUERY
        override val description: String = "查看用户已设置的自定义监控规则及其状态"
        override val parameters: JSONObject = JsonSchema.objectSchema(emptyList(), emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val rules = runCatching { monitors().all() }.getOrDefault(emptyList())
            return JSONObject().apply {
                put("count", rules.size)
                put("enabledCount", rules.count { it.enabled })
                put("maxRules", MonitorValidator.MAX_RULES)
                put("rules", JSONArray().apply {
                    rules.forEach { rule ->
                        put(
                            JSONObject()
                                .put("id", rule.id)
                                .put("name", rule.name)
                                .put("enabled", rule.enabled)
                                .put(
                                    "description",
                                    MonitorValidator.describe(rule) { pkg -> source.appNameOf(pkg) },
                                )
                                .put("threshold", rule.threshold)
                                .put("metric", rule.metric.name)
                                .put("schedule", rule.schedule.label),
                        )
                    }
                })
            }
        }
    }

    private inner class RemoveMonitorTool : AgentTool {
        override val name: String = "remove_monitor"
        override val kind: ToolKind = ToolKind.PROPOSE
        override val description: String = "删除一条自定义监控规则（生成待确认草案）"
        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "rule_id" to JsonSchema.string("规则 id（先用 list_monitors 查）"),
            ),
            required = listOf("rule_id"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val ruleId = arguments.stringArg("rule_id")?.trim().orEmpty()
            if (ruleId.isEmpty()) throw ToolArgumentException("缺少必填参数 rule_id")
            val rule = runCatching { monitors().all().firstOrNull { it.id == ruleId } }.getOrNull()
                ?: throw ToolArgumentException("没有找到规则 $ruleId，请先用 list_monitors 查看")
            val describe = MonitorValidator.describe(rule) { pkg -> source.appNameOf(pkg) }
            val drafts = runCatching { actionDraftsProvider() }.getOrElse {
                throw ToolArgumentException("待确认操作服务尚未就绪，请稍后再试")
            }
            val taskId = runCatching { taskIdProvider() }.getOrDefault("").ifBlank { "agent-task" }
            val entity = drafts.propose(
                taskId = taskId,
                type = TYPE_DELETE,
                paramsJson = JSONObject().put("rule_id", rule.id).toString(),
                beforeJson = JSONObject().put("rule", describe).toString(),
                afterJson = JSONObject().put("rule", "已删除").toString(),
            )
            return JSONObject().apply {
                put("executed", false)
                put("requiresConfirmation", true)
                put("opId", entity.opId)
                put("type", entity.type)
                put("message", "已生成删除草案：$describe。请在应用内确认。")
            }
        }
    }

    private inner class MonitorHistoryTool : AgentTool {
        override val name: String = "monitor_history"
        override val kind: ToolKind = ToolKind.QUERY
        override val description: String = "查看监控规则的触发历史（最近 20 条）"
        override val parameters: JSONObject = JsonSchema.objectSchema(emptyList(), emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val triggers = runCatching { monitors().recentTriggers(20) }.getOrDefault(emptyList())
            return JSONObject().apply {
                put("count", triggers.size)
                put("triggers", JSONArray().apply {
                    triggers.forEach { t ->
                        put(
                            JSONObject()
                                .put("rule", t.ruleName)
                                .put("date", t.date)
                                .put("value", t.value)
                                .put("threshold", t.threshold)
                                .put("message", t.message),
                        )
                    }
                })
            }
        }
    }

    companion object {
        const val TYPE_CREATE = "CREATE_MONITOR"
        const val TYPE_DELETE = "DELETE_MONITOR"
    }
}
