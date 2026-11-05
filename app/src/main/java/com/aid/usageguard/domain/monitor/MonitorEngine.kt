package com.aid.usageguard.domain.monitor

import android.util.Log
import com.aid.usageguard.data.repo.MonitorRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.UsageEventSource
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 本地监控引擎（v1.9 Phase 5）：定时用本地已采集数据判断阈值，**不调用模型**。
 * 同一规则同一天只触发一次（去重）；触发记录落库，供历史与避免重复通知。
 */
class MonitorEngine(
    private val usage: UsageRepository,
    private val monitors: MonitorRepository,
) {

    suspend fun evaluate(now: Long = System.currentTimeMillis()): List<MonitorTriggerResult> {
        val date = UsageEventSource.dateOf(now)
        val day = runCatching { LocalDate.parse(date).dayOfWeek }.getOrNull() ?: DayOfWeek.MONDAY
        val rules = runCatching { monitors.enabled() }.getOrDefault(emptyList())
        if (rules.isEmpty()) return emptyList()

        val todayTotal = runCatching { usage.todayTotalMinutes() }.getOrDefault(0L)
        val ranking = runCatching { usage.todayRanking() }.getOrDefault(emptyList())
        val launches = runCatching { usage.launchCount(date) }.getOrDefault(emptyMap())

        val triggered = ArrayList<MonitorTriggerResult>()
        for (rule in rules) {
            if (!rule.schedule.matches(day)) continue
            if (runCatching { monitors.hasTriggered(rule.id, date) }.getOrDefault(false)) continue

            val value = when (rule.metric) {
                MonitorMetric.FOREGROUND_DURATION ->
                    rule.packageName?.let { pkg -> ranking.firstOrNull { it.packageName == pkg }?.minutes ?: 0L }
                        ?: todayTotal

                MonitorMetric.LAUNCH_COUNT ->
                    launches[rule.packageName]?.toLong() ?: 0L
            }
            if (!compare(value, rule.threshold, rule.operator)) continue

            val message = buildMessage(rule, value)
            val result = MonitorTriggerResult(
                ruleId = rule.id,
                ruleName = rule.name,
                packageName = rule.packageName,
                metric = rule.metric,
                value = value,
                threshold = rule.threshold,
                message = message,
            )
            runCatching { monitors.recordTrigger(result, date) }
            triggered += result
            Log.i(TAG, "monitor triggered: ${rule.name} value=$value threshold=${rule.threshold}")
        }
        return triggered
    }

    private fun compare(value: Long, threshold: Long, operator: MonitorOperator): Boolean = when (operator) {
        MonitorOperator.GREATER_THAN -> value > threshold
        MonitorOperator.GREATER_EQUAL -> value >= threshold
        MonitorOperator.LESS_THAN -> value < threshold
    }

    private fun buildMessage(rule: MonitorDefinition, value: Long): String {
        val unit = rule.metric.unit
        val who = rule.packageName?.let { "「$it」" } ?: "全部应用"
        val head = when (rule.metric) {
            MonitorMetric.FOREGROUND_DURATION -> "今日${who}已使用 $value $unit"
            MonitorMetric.LAUNCH_COUNT -> "今日${who}已启动 $value $unit"
        }
        return "$head，${rule.name}（阈值 ${rule.threshold}$unit）"
    }

    companion object {
        private const val TAG = "MonitorEngine"
    }
}
