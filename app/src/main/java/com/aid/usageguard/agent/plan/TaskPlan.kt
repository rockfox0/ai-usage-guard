package com.aid.usageguard.agent.plan

/** 计划里一步的状态（执行期更新，仅用于日志/调试与后续 UI）。 */
enum class StepStatus {
    PENDING,
    RUNNING,
    DONE,
    SKIPPED,
    FAILED,
}

/**
 * 一个执行步骤：要做什么 + 需要哪些能力（能力 id 必须在 CapabilityCatalog 中）。
 * [requiresData] 表示这一步必须以工具数据为依据（否则结果评估会要求补充查询）。
 */
data class TaskStep(
    val stepId: String,
    val description: String,
    val capabilities: List<String>,
    val requiresData: Boolean = true,
    val status: StepStatus = StepStatus.PENDING,
)

/**
 * LLM 生成、经本地校验的任务计划（v1.9 Phase 2）。
 * 不是固定工作流：执行中允许结果评估触发补充查询或一次重规划。
 */
data class TaskPlan(
    val taskId: String,
    val goal: String,
    val mode: TaskMode,
    val steps: List<TaskStep>,
    val completionCriteria: List<String>,
    val requiresConfirmation: Boolean = false,
)

/** 结果评估结论。 */
enum class EvaluationVerdict {
    OK,
    NEED_MORE,
    INSUFFICIENT_DATA,
    FAILED,
}

data class Evaluation(
    val verdict: EvaluationVerdict,
    val reason: String = "",
    /** NEED_MORE 时给模型的补充指令 */
    val directive: String = "",
    /** INSUFFICIENT_DATA 时追加给用户看的说明 */
    val caveat: String = "",
)
