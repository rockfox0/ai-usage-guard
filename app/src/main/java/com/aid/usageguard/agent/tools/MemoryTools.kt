package com.aid.usageguard.agent.tools

import com.aid.usageguard.agent.memory.MemoryEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 长期记忆工具（v1.9 Phase 3）。
 *
 * 与「设置/限制」不同：这些写操作只改 Agent 自己的记忆（偏好/目标/决策/反馈），
 * 不改变系统限制或用户可见配置，因此归为 CONTROL 直接执行（可随时覆盖，不影响权限边界）。
 * 记住：记忆只影响「怎么建议」，不能当作统计数据来源。
 */
class MemoryTools(private val engineProvider: () -> MemoryEngine?) {

    val all: List<AgentTool> = listOf(
        SaveMemoryTool(),
        ListMemoryTool(),
        SaveGoalTool(),
        ListGoalsTool(),
        RecordFeedbackTool(),
    )

    private fun engine(): MemoryEngine =
        engineProvider() ?: throw ToolArgumentException("记忆模块尚未初始化，请稍后重试")

    private inner class SaveMemoryTool : AgentTool {
        override val name: String = "save_memory"
        override val kind: ToolKind = ToolKind.CONTROL
        override val description: String =
            "记住用户的长期偏好或决策（例如语气偏好、关注点）。仅影响建议方式，不影响统计数据。"
        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "kind" to JsonSchema.string("记忆类型：profile（偏好）或 decision（决策）", enum = listOf("profile", "decision")),
                "key" to JsonSchema.string("键，例如 tone / focus / bedtime"),
                "value" to JsonSchema.string("值，例如 温和 / 睡眠"),
            ),
            required = listOf("kind", "key", "value"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val kind = arguments.stringArg("kind")?.trim().orEmpty()
            val key = arguments.stringArg("key")?.trim().orEmpty()
            val value = arguments.stringArg("value")?.trim().orEmpty()
            if (kind.isEmpty() || key.isEmpty() || value.isEmpty()) {
                throw ToolArgumentException("kind / key / value 都不能为空")
            }
            if (kind == "profile") {
                engine().saveProfile(key, value)
            } else {
                engine().recordDecision(key, kind, value)
            }
            return JSONObject().put("ok", true).put("memory", "$kind/$key")
        }
    }

    private inner class ListMemoryTool : AgentTool {
        override val name: String = "list_memory"
        override val kind: ToolKind = ToolKind.QUERY
        override val description: String = "查看当前长期记忆摘要（偏好 / 目标 / 被拒绝的建议）"
        override val parameters: JSONObject = JsonSchema.objectSchema(emptyList(), emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val section = engine().promptSection()
            return JSONObject().apply {
                put("empty", section.isBlank())
                put("summary", section)
            }
        }
    }

    private inner class SaveGoalTool : AgentTool {
        override val name: String = "save_goal"
        override val kind: ToolKind = ToolKind.CONTROL
        override val description: String = "记录用户的长期目标（例如「减少夜间游戏」），用于后续建议对齐"
        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "goal" to JsonSchema.string("目标描述"),
                "target_period" to JsonSchema.string("时间段（可空），例如 22:00-06:00 或 工作日"),
            ),
            required = listOf("goal"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val goal = arguments.stringArg("goal")?.trim().orEmpty()
            if (goal.isEmpty()) throw ToolArgumentException("goal 不能为空")
            val period = arguments.stringArg("target_period")?.trim().orEmpty()
            val saved = engine().saveGoal(goal, period)
            return JSONObject()
                .put("ok", true)
                .put("goalId", saved.id)
                .put("goal", saved.goal)
                .put("targetPeriod", saved.targetPeriod)
        }
    }

    private inner class ListGoalsTool : AgentTool {
        override val name: String = "list_goals"
        override val kind: ToolKind = ToolKind.QUERY
        override val description: String = "查看用户的长期目标（含状态）"
        override val parameters: JSONObject = JsonSchema.objectSchema(emptyList(), emptyList())

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val goals = engine().listGoals()
            return JSONObject().apply {
                put("count", goals.size)
                put("goals", JSONArray().apply {
                    goals.forEach { g ->
                        put(
                            JSONObject()
                                .put("id", g.id)
                                .put("goal", g.goal)
                                .put("targetPeriod", g.targetPeriod)
                                .put("status", g.status),
                        )
                    }
                })
            }
        }
    }

    private inner class RecordFeedbackTool : AgentTool {
        override val name: String = "record_feedback"
        override val kind: ToolKind = ToolKind.CONTROL
        override val description: String =
            "记录用户对某条建议的反馈（REJECT / PREFER / ACCEPT），用于以后避免重复被拒绝的方案"
        override val parameters: JSONObject = JsonSchema.objectSchema(
            properties = listOf(
                "signal" to JsonSchema.string("反馈类型", enum = listOf("REJECT", "PREFER", "ACCEPT")),
                "reason" to JsonSchema.string("用户原话或理由"),
                "target_id" to JsonSchema.string("关联建议/草案 id（可空）"),
            ),
            required = listOf("signal", "reason"),
        )

        override suspend fun execute(arguments: JSONObject): JSONObject {
            val signal = arguments.stringArg("signal")?.trim()?.uppercase().orEmpty()
            val reason = arguments.stringArg("reason")?.trim().orEmpty()
            if (signal.isEmpty() || reason.isEmpty()) throw ToolArgumentException("signal / reason 不能为空")
            val targetId = arguments.stringArg("target_id")?.trim().orEmpty()
            val saved = engine().let { eng ->
                // 直接走仓储：recordFeedback 已封装在 MemoryEngine 内部 via repo
                eng.recordFeedbackDirect(signal, reason, targetId)
            }
            return JSONObject().put("ok", true).put("feedbackId", saved.id)
        }
    }
}
