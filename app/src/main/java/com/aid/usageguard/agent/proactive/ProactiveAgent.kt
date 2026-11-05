package com.aid.usageguard.agent.proactive

import android.util.Log
import com.aid.usageguard.agent.llm.LlmClientFactory
import com.aid.usageguard.agent.llm.LlmMessage
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.InsightRepository
import com.aid.usageguard.data.repo.MemoryRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.insight.InsightCandidate
import com.aid.usageguard.domain.insight.InsightDetector
import com.aid.usageguard.domain.insight.InsightKind
import com.aid.usageguard.domain.insight.ProactiveInsight
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate

/**
 * 主动 Agent（v1.9 Phase 6）：
 *
 * 1. 本地规则先筛候选事件（[InsightDetector]，零 token）；
 * 2. 每天最多生成 1 条洞察（去重 + 节流），避免打扰；
 * 3. 只有存在候选时才调用一次模型（无工具、45s 超时），拿到结构化结论后落库 + 通知；
 * 4. 模型失败时退化为本地事实提醒（不编造、不静默丢弃）。
 *
 * 用户可以通过关闭「异常使用提醒」开关停用整个主动分析（复用 notifyAnomaly 偏好）。
 */
class ProactiveAgent(
    private val usage: UsageRepository,
    private val insights: InsightRepository,
    private val memory: MemoryRepository,
    private val settings: SettingsRepository,
) {

    suspend fun maybeGenerate(now: Long = System.currentTimeMillis()): ProactiveInsight? {
        val cfg = runCatching { settings.current() }.getOrNull() ?: return null
        if (!cfg.notificationEnabled || !cfg.notifyProactive) return null

        val today = UsageEventSource.dateOf(now)
        if (runCatching { insights.countForDate(today) }.getOrDefault(0) > 0) return null

        val daily = runCatching { usage.dailyTotalsForLastDays(14) }.getOrDefault(emptyList())
        val from14 = UsageEventSource.dateOf(now - 13L * 24 * 3600 * 1000)
        val mid = UsageEventSource.dateOf(now - 7L * 24 * 3600 * 1000)
        val last7From = UsageEventSource.dateOf(now - 6L * 24 * 3600 * 1000)
        val appLast7 = runCatching { usage.rankingForRange(last7From, today) }
            .getOrDefault(emptyList()).associate { it.packageName to it.minutes }
        val appPrev7 = runCatching { usage.rankingForRange(from14, mid) }
            .getOrDefault(emptyList()).associate { it.packageName to it.minutes }
        val todayMinutes = runCatching { usage.todayTotalMinutes() }.getOrDefault(0L)

        val candidates = runCatching {
            InsightDetector.detect(
                dailyTotals = daily,
                appLast7 = appLast7,
                appPrev7 = appPrev7,
                todayMinutes = todayMinutes,
                dailyTargetMinutes = cfg.dailyTargetMinutes,
                today = today,
            )
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return null

        // 优先挑「最值得说」的一个：总时长上升 > 时间转移 > 单应用增长 > 超目标
        val candidate = candidates.sortedBy { priority(it.kind) }.first()
        val memorySection = runCatching { memorySection() }.getOrDefault("")
        val llm = runCatching { LlmClientFactory.create(cfg) }.getOrNull()
            ?: return fallback(candidate, today)

        val parsed = runCatching {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                val response = llm.complete(
                    system = systemPrompt(),
                    messages = listOf(
                        LlmMessage.user(
                            buildString {
                                append("今天：").append(today).append('\n')
                                append("候选事实：").append(candidate.fact).append('\n')
                                append("今日总时长：").append(todayMinutes).append(" 分钟；每日目标：")
                                append(cfg.dailyTargetMinutes).append(" 分钟\n")
                                if (memorySection.isNotBlank()) append(memorySection).append('\n')
                                append("请输出 JSON：{\"title\":\"不超过 18 字\",\"detail\":\"2-3 句事实与原因，最多 120 字\",\"suggestion\":\"1 条可执行建议，最多 80 字\"}")
                            },
                        ),
                    ),
                    tools = emptyList(),
                )
                ProactiveParser.parse(response.text, candidate, today)
            }
        }.getOrNull() ?: return fallback(candidate, today)

        return runCatching { insights.save(parsed) }.getOrNull() ?: parsed
    }

    private suspend fun fallback(candidate: InsightCandidate, today: String): ProactiveInsight? = runCatching {
        insights.save(
            ProactiveInsight(
                id = "",
                date = today,
                kind = candidate.kind,
                title = candidate.kind.label,
                detail = candidate.fact,
                suggestion = "可以在「我的监控」里给这个应用设一条提醒，或者和 AI 助手聊聊怎么调整。",
            ),
        )
    }.getOrNull()

    private suspend fun memorySection(): String {
        val profiles = memory.memories("profile")
        val goals = memory.activeGoals()
        if (profiles.isEmpty() && goals.isEmpty()) return ""
        return buildString {
            append("用户偏好/目标（不要重复被拒绝过的方案）：\n")
            profiles.take(4).forEach { mem ->
                val value = runCatching { JSONObject(mem.valueJson).optString("value") }.getOrDefault(mem.valueJson)
                append("- ").append(mem.memKey).append("=").append(value).append('\n')
            }
            goals.take(3).forEach { goal -> append("- 目标：").append(goal.goal).append('\n') }
        }
    }

    private fun priority(kind: InsightKind): Int = when (kind) {
        InsightKind.TOTAL_INCREASE -> 0
        InsightKind.TIME_SHIFT -> 1
        InsightKind.APP_INCREASE -> 2
        InsightKind.TARGET_EXCEEDED -> 3
    }

    companion object {
        private const val TAG = "ProactiveAgent"
        private const val REQUEST_TIMEOUT_MS = 45_000L

        private val SYSTEM = """
            你是手机使用管家的主动分析员。系统在本地检测到一条使用变化，你要用简洁、具体、不评判的中文解释它。
            必须遵守：
            1. 只基于给定事实与数字，不编造统计；不确定就少说。
            2. 不做医学/心理诊断，不贴「成瘾」「抑郁」等标签；只描述行为与可能原因。
            3. 建议要小、可执行、尊重用户既有偏好与目标；不要重复用户拒绝过的方案。
            4. 只输出 JSON，不要多余文字：{"title":"...","detail":"...","suggestion":"..."}
            title ≤18 字，detail ≤120 字（2-3 句），suggestion ≤80 字。
        """.trimIndent()

        fun systemPrompt(): String = SYSTEM
    }
}

/** 主动洞察 JSON 解析（纯函数，单测覆盖）。 */
object ProactiveParser {

    private const val MAX_TITLE = 40
    private const val MAX_DETAIL = 240
    private const val MAX_SUGGESTION = 160

    fun parse(raw: String, candidate: InsightCandidate, date: String): ProactiveInsight? {
        val json = extract(raw) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val title = obj.optString("title").trim().take(MAX_TITLE)
        val detail = obj.optString("detail").trim().take(MAX_DETAIL)
        val suggestion = obj.optString("suggestion").trim().take(MAX_SUGGESTION)
        if (title.isEmpty() || detail.isEmpty()) return null
        return ProactiveInsight(
            id = "",
            date = date,
            kind = candidate.kind,
            title = title,
            detail = detail,
            suggestion = suggestion,
        )
    }

    private fun extract(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").trim()
            val end = text.lastIndexOf("```")
            if (end >= 0) text = text.substring(0, end).trim()
        }
        val start = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (start < 0 || last <= start) return null
        return text.substring(start, last + 1)
    }
}
