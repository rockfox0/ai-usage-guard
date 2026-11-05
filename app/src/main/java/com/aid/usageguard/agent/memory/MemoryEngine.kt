package com.aid.usageguard.agent.memory

import com.aid.usageguard.data.db.AgentFeedbackEntity
import com.aid.usageguard.data.db.AgentGoalEntity
import com.aid.usageguard.data.db.AgentMemoryEntity
import com.aid.usageguard.data.repo.MemoryRepository
import org.json.JSONObject

/**
 * 记忆引擎（v1.9 Phase 3）：把 [MemoryRepository] 的原始数据变成
 * - 注入系统提示词的「长期记忆摘要」；
 * - 记录用户反馈、目标、决策的写入 API；
 * - 「这个建议以前被拒过吗」的检索（避免原样重复被拒绝的方案）。
 */
class MemoryEngine(private val repo: MemoryRepository) {

    /** 识别并记录用户这句话里的反馈信号；返回识别结果（没有则 null） */
    suspend fun recordFeedbackFromInput(input: String, targetType: String = "general"): DetectedFeedback? {
        val detected = FeedbackDetector.detect(input) ?: return null
        runCatching {
            repo.recordFeedback(
                targetType = targetType,
                targetId = "",
                signal = detected.signal,
                reason = detected.reason,
            )
        }
        return detected
    }

    /** 直接记录一条反馈（供 record_feedback 工具/UI 取消草案使用） */
    suspend fun recordFeedbackDirect(
        signal: String,
        reason: String,
        targetId: String = "",
        targetType: String = "suggestion",
    ): AgentFeedbackEntity = repo.recordFeedback(targetType, targetId, signal, reason)

    suspend fun saveProfile(key: String, value: String) {
        repo.putMemory("profile", key, JSONObject().put("value", value).toString())
    }

    suspend fun recordDecision(opId: String, type: String, message: String, status: String = "PROPOSED") {
        runCatching {
            repo.putMemory(
                "decision",
                opId,
                JSONObject()
                    .put("type", type)
                    .put("message", message.take(200))
                    .put("status", status)
                    .toString(),
            )
        }
    }

    /** 记录一次任务计划（kind=plan），用于真机验证与后续 UI 展示步骤 */
    suspend fun recordPlan(taskId: String, planJson: String) {
        runCatching { repo.putMemory("plan", taskId.ifBlank { "latest" }, planJson) }
    }

    suspend fun saveGoal(goal: String, targetPeriod: String = ""): AgentGoalEntity =
        repo.saveGoal(goal, targetPeriod)

    suspend fun listGoals(): List<AgentGoalEntity> = repo.allGoals()

    suspend fun completeGoal(id: String): Boolean = repo.completeGoal(id)

    /** 供系统提示词使用的记忆摘要（无记忆时返回空串） */
    suspend fun promptSection(): String {
        val profiles = runCatching { repo.memories("profile") }.getOrDefault(emptyList())
        val goals = runCatching { repo.activeGoals() }.getOrDefault(emptyList())
        val feedback = runCatching { repo.recentFeedback(8) }.getOrDefault(emptyList())
        val decisions = runCatching { repo.memories("decision") }.getOrDefault(emptyList())
        return renderPromptSection(profiles, goals, feedback, decisions)
    }

    /** 建议是否与「被拒绝过的决策」高度相似（同一操作类型 + 关键词重合） */
    suspend fun wasRejectedBefore(suggestion: String): Boolean {
        val rejected = runCatching {
            repo.recentFeedback(20).filter { it.signal == "REJECT" }
        }.getOrDefault(emptyList())
        if (rejected.isEmpty()) return false
        val tokens = suggestion.lowercase().split(Regex("[\\s，,。.；;：:]+")).filter { it.length >= 2 }
        return rejected.any { fb ->
            val reason = fb.reason.lowercase()
            tokens.any { reason.contains(it) && it.length >= 3 }
        }
    }

    companion object {
        /** 纯函数：把记忆渲染成提示词片段（单测覆盖；无内容返回空串） */
        fun renderPromptSection(
            profiles: List<AgentMemoryEntity>,
            goals: List<AgentGoalEntity>,
            feedback: List<AgentFeedbackEntity>,
            decisions: List<AgentMemoryEntity> = emptyList(),
        ): String {
            if (profiles.isEmpty() && goals.isEmpty() && feedback.isEmpty() && decisions.isEmpty()) {
                return ""
            }
            return buildString {
                append("【长期记忆（来自用户历史与反馈，仅用于调整建议方式；不能当作统计数据来源）】\n")
                if (profiles.isNotEmpty()) {
                    append("- 用户偏好：")
                    append(
                        profiles.take(6).joinToString("；") { mem ->
                            val value = runCatching {
                                JSONObject(mem.valueJson).optString("value")
                            }.getOrDefault(mem.valueJson)
                            "${mem.memKey}=$value"
                        },
                    )
                    append('\n')
                }
                if (goals.isNotEmpty()) {
                    append("- 进行中的目标：")
                    append(
                        goals.take(4).joinToString("；") { g ->
                            if (g.targetPeriod.isBlank()) g.goal else "${g.goal}（${g.targetPeriod}）"
                        },
                    )
                    append('\n')
                }
                val rejects = feedback.filter { it.signal == "REJECT" }
                if (rejects.isNotEmpty()) {
                    append("- 用户拒绝过的建议：")
                    append(rejects.take(4).joinToString("；") { it.reason.take(50) })
                    append('\n')
                }
                val prefers = feedback.filter { it.signal == "PREFER" }
                if (prefers.isNotEmpty()) {
                    append("- 用户表达过的偏好：")
                    append(prefers.take(4).joinToString("；") { it.reason.take(50) })
                    append('\n')
                }
                append("- 建议：不要原样重复被拒绝过的方案；优先采用用户表达过的偏好（更温和/渐进）。")
            }
        }
    }
}
