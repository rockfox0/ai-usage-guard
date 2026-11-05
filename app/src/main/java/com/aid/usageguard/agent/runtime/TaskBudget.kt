package com.aid.usageguard.agent.runtime

import android.os.SystemClock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 单任务统一资源预算（v1.7 方案 10.2）。
 *
 * - 模型请求 ≤ [MAX_MODEL_REQUESTS]（重试与降级重试都计入）；
 * - 工具调用 ≤ [MAX_TOOL_CALLS]（重试计入）；
 * - 墙钟 ≤ [MAX_WALL_CLOCK_MS]（180s，不含用户等待确认）。
 *
 * 通过 CoroutineContext 传递：嵌套/报告子任务（例如 chat 里 generate_report_now 触发的报告任务）
 * 会复用同一个 [TaskBudget]，不会新建子任务重置额度。
 *
 * 计时使用 [SystemClock.elapsedRealtime]（单调时钟，符合方案 §4.3），不做线程安全保证：
 * 团队约定同一对话同时只有一个活跃任务（方案 §10.1）。
 */
class TaskBudget(
    val maxModelRequests: Int = MAX_MODEL_REQUESTS,
    val maxToolCalls: Int = MAX_TOOL_CALLS,
    private val maxWallClockMs: Long = MAX_WALL_CLOCK_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : AbstractCoroutineContextElement(TaskBudget) {

    private val startedAt = clock()
    private var modelRequestsUsed = 0
    private var toolCallsUsed = 0
    private var stoppedReason: String? = null

    val modelRequests: Int get() = modelRequestsUsed
    val toolCalls: Int get() = toolCallsUsed

    /** 触发停止的原因（null 表示预算未耗尽） */
    val stopReason: String? get() = stoppedReason

    fun elapsedMs(): Long = clock() - startedAt

    fun remainingWallClockMs(): Long = (maxWallClockMs - elapsedMs()).coerceAtLeast(0L)

    /** 申请一次模型请求额度；返回 null 表示可以继续，否则返回停止原因。 */
    fun tryConsumeModelRequest(): String? {
        stoppedReason?.let { return it }
        if (remainingWallClockMs() <= 0L) {
            return stop("已超过单任务总时长上限（${maxWallClockMs / 1000} 秒）")
        }
        if (modelRequestsUsed >= maxModelRequests) {
            return stop("模型请求已达上限（$maxModelRequests 次）")
        }
        modelRequestsUsed++
        return null
    }

    /** 申请一次工具调用额度；返回 null 表示可以继续，否则返回停止原因。 */
    fun tryConsumeToolCall(): String? {
        stoppedReason?.let { return it }
        if (remainingWallClockMs() <= 0L) {
            return stop("已超过单任务总时长上限（${maxWallClockMs / 1000} 秒）")
        }
        if (toolCallsUsed >= maxToolCalls) {
            return stop("工具调用已达上限（$maxToolCalls 次）")
        }
        toolCallsUsed++
        return null
    }

    /** 按「剩余墙钟」收紧单次操作超时，保证总时长不超过 180s。 */
    fun timeoutForRequest(preferredMs: Long): Long =
        minOf(preferredMs, remainingWallClockMs().coerceAtLeast(1L))

    fun stop(reason: String): String {
        if (stoppedReason == null) stoppedReason = reason
        return stoppedReason ?: reason
    }

    companion object Key : CoroutineContext.Key<TaskBudget> {
        /** 单任务模型请求上限（重试计入） */
        const val MAX_MODEL_REQUESTS: Int = 6

        /** 单任务工具调用上限（重试计入） */
        const val MAX_TOOL_CALLS: Int = 12

        /** 单任务墙钟上限：180s（不含用户确认等待） */
        const val MAX_WALL_CLOCK_MS: Long = 180_000L
    }
}
