package com.aid.usageguard.agent.multi

import android.util.Log
import com.aid.usageguard.agent.llm.LlmClientFactory
import com.aid.usageguard.agent.llm.LlmMessage
import com.aid.usageguard.agent.llm.ModelRouter
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.BehaviorRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 只读专家子 Agent（v1.9 Phase 7）。
 *
 * - 只做「并行分析」，不执行任何工具、不产生副作用；
 * - 本地数据先整理成紧凑快照，再各用 1 次模型请求产出要点；
 * - 结果作为「专家分析摘要」注入主 Agent 的上下文，由主 Agent 统一组织最终回答；
 * - 失败/超时/未配置都静默跳过，主流程不受影响。
 */
class SubAgentManager(
    private val usage: UsageRepository,
    private val behavior: BehaviorRepository,
    private val settings: SettingsRepository,
) {

    data class Finding(val agent: String, val text: String)

    suspend fun run(input: String): List<Finding> {
        val cfg = runCatching { settings.current() }.getOrNull() ?: return emptyList()
        val client = runCatching {
            LlmClientFactory.create(cfg, ModelRouter.modelFor(ModelRouter.Route.SUB_AGENT, cfg))
        }.getOrNull() ?: return emptyList()

        val usageSnapshot = runCatching { buildUsageSnapshot() }.getOrDefault("")
        val behaviorSnapshot = runCatching { buildBehaviorSnapshot() }.getOrDefault("")

        return coroutineScope {
            val jobs = buildList {
                if (usageSnapshot.isNotBlank()) {
                    add(
                        async(Dispatchers.IO) {
                            query(
                                client = client,
                                system = USAGE_ANALYST_PROMPT,
                                user = "用户问题：$input\n\n$usageSnapshot\n\n请输出 2-3 条要点，每条不超过 40 字，只基于以上数字。",
                            )?.let { Finding("使用分析师", it) }
                        },
                    )
                }
                if (behaviorSnapshot.isNotBlank()) {
                    add(
                        async(Dispatchers.IO) {
                            query(
                                client = client,
                                system = BEHAVIOR_ANALYST_PROMPT,
                                user = "用户问题：$input\n\n$behaviorSnapshot\n\n请输出 2-3 条要点，每条不超过 40 字，只基于以上事件。",
                            )?.let { Finding("行为分析师", it) }
                        },
                    )
                }
            }
            jobs.mapNotNull { runCatching { it.await() }.getOrNull() }
        }
    }

    private suspend fun query(
        client: com.aid.usageguard.agent.llm.LlmClient,
        system: String,
        user: String,
    ): String? = withTimeoutOrNull(TIMEOUT_MS) {
        client.complete(system, listOf(LlmMessage.user(user)), emptyList())
            .text
            .trim()
            .take(MAX_FINDING_CHARS)
            .takeIf { it.isNotBlank() }
    }?.also { Log.i(TAG, "sub-agent finding: ${it.take(40)}…") }

    private suspend fun buildUsageSnapshot(): String {
        val today = UsageEventSource.todayDate()
        val daily7 = runCatching { usage.dailyTotalsForLastDays(7) }.getOrDefault(emptyList())
        val daily30 = runCatching { usage.dailyTotalsForLastDays(30) }.getOrDefault(emptyList())
        val weekAgo = UsageEventSource.dateOf(System.currentTimeMillis() - 6L * 24 * 3600 * 1000)
        val ranking7 = runCatching { usage.rankingForRange(weekAgo, today, 6) }.getOrDefault(emptyList())
        return SubAgentSnapshots.formatUsage(daily7, daily30, ranking7, today)
    }

    private suspend fun buildBehaviorSnapshot(): String {
        val events = runCatching { behavior.recent(20) }.getOrDefault(emptyList())
        return SubAgentSnapshots.formatBehavior(events.map { it.description })
    }

    companion object {
        private const val TAG = "SubAgentManager"
        private const val TIMEOUT_MS = 30_000L
        private const val MAX_FINDING_CHARS = 300

        private val USAGE_ANALYST_PROMPT = """
            你是手机使用数据分析专家。只基于给定数字做事实分析：趋势、结构、异常。
            不要编造数字；不做医学/心理诊断；不要给一堆建议（主 Agent 会综合）。
            输出 2-3 条短要点，每条 ≤40 字，用「- 」开头。
        """.trimIndent()

        private val BEHAVIOR_ANALYST_PROMPT = """
            你是手机使用行为分析专家。只基于给定事件描述找模式：频率、时段、连续性、应用切换。
            不编造；不做诊断；不要重复罗列事件。
            输出 2-3 条短要点，每条 ≤40 字，用「- 」开头。
        """.trimIndent()
    }
}

/** 子 Agent 快照格式化（纯函数，单测覆盖）。 */
object SubAgentSnapshots {

    fun formatUsage(
        daily7: List<com.aid.usageguard.domain.model.DailyTotal>,
        daily30: List<com.aid.usageguard.domain.model.DailyTotal>,
        ranking7: List<com.aid.usageguard.domain.model.AppUsageSummary>,
        today: String,
    ): String = buildString {
        if (daily7.isEmpty() && ranking7.isEmpty()) return ""
        append("【本地使用数据快照】\n")
        append("今天：").append(today).append('\n')
        if (daily7.isNotEmpty()) {
            val avg7 = daily7.map { it.minutes }.average().toLong()
            append("最近 7 天日均：").append(avg7).append(" 分钟（共 ")
                .append(daily7.sumOf { it.minutes }).append(" 分钟）\n")
            append("每日：").append(daily7.joinToString("、") { "${it.date.takeLast(5)}=${it.minutes}m" }).append('\n')
        }
        if (daily30.isNotEmpty()) {
            val avg30 = daily30.map { it.minutes }.average().toLong()
            append("最近 30 天日均：").append(avg30).append(" 分钟\n")
        }
        if (ranking7.isNotEmpty()) {
            append("最近 7 天 Top 应用：")
            append(ranking7.take(5).joinToString("、") { "${it.appName}=${it.minutes}m" })
            append('\n')
        }
    }

    fun formatBehavior(events: List<String>): String {
        val cleaned = events.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(10)
        if (cleaned.isEmpty()) return ""
        return buildString {
            append("【近期行为事件】\n")
            cleaned.forEach { append("- ").append(it).append('\n') }
        }
    }
}
