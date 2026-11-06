package com.aid.usageguard.agent.bench

import com.aid.usageguard.agent.capability.CapabilityCatalog
import com.aid.usageguard.agent.capability.CapabilityDiscovery
import com.aid.usageguard.agent.contract.AgentContext
import com.aid.usageguard.agent.feature.FeatureAdvisor
import com.aid.usageguard.agent.feature.FeatureProposal
import com.aid.usageguard.agent.memory.FeedbackDetector
import com.aid.usageguard.agent.memory.MemoryEngine
import com.aid.usageguard.agent.multi.SubAgentPolicy
import com.aid.usageguard.agent.plan.EvaluationVerdict
import com.aid.usageguard.agent.plan.ResultEvaluator
import com.aid.usageguard.agent.plan.TaskMode
import com.aid.usageguard.agent.plan.TaskModeClassifier
import com.aid.usageguard.agent.plan.TaskPlan
import com.aid.usageguard.agent.plan.TaskPlanner
import com.aid.usageguard.agent.plan.TaskStep
import com.aid.usageguard.agent.proactive.ProactiveParser
import com.aid.usageguard.agent.response.ResponseParser
import com.aid.usageguard.data.db.AgentFeedbackEntity
import com.aid.usageguard.domain.insight.InsightCandidate
import com.aid.usageguard.domain.insight.InsightDetector
import com.aid.usageguard.domain.insight.InsightKind
import com.aid.usageguard.domain.model.AppUsageSummary
import com.aid.usageguard.domain.model.DailyTotal
import com.aid.usageguard.domain.monitor.MonitorDefinition
import com.aid.usageguard.domain.monitor.MonitorMetric
import com.aid.usageguard.domain.monitor.MonitorOperator
import com.aid.usageguard.domain.monitor.MonitorSchedule
import com.aid.usageguard.domain.monitor.MonitorValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * Agent Benchmark（v1.9 验收，规格 §15 / §17 Case 1–8）。
 *
 * 说明：这里测的是「Agent 的决策链」——模式判定、能力路由、规划校验、结果评估、
 * 监控规则、反馈识别、主动候选、功能提案——全部是真实生产代码，不是 mock 出来的假实现。
 * 需要 LLM 的环节用「脚本化输入」（例如固定 JSON）验证解析与校验，避免依赖网络/Key。
 *
 * 运行：`./gradlew :app:testDebugUnitTest --tests "*AgentBenchmarkTest*"`
 */
class AgentBenchmarkTest {

    private data class CaseResult(
        val id: Int,
        val name: String,
        val passed: Boolean,
        val components: String,
        val elapsedMs: Long,
        val detail: String,
    )

    private fun runCase(id: Int, name: String, components: String, block: () -> String): CaseResult {
        val start = System.nanoTime()
        val passed: Boolean
        val detail: String
        try {
            detail = block()
            passed = true
        } catch (t: Throwable) {
            return CaseResult(id, name, false, components, (System.nanoTime() - start) / 1_000_000, "FAILED: ${t.message}")
        }
        return CaseResult(id, name, passed, components, (System.nanoTime() - start) / 1_000_000, detail)
    }

    @Test
    fun `Agent Benchmark Case 1-8`() {
        val results = mutableListOf<CaseResult>()

        // ---------- Case 1：模糊表达 ----------
        results += runCase(1, "模糊表达：最近手机用得有点多", "routing + evaluator") {
            val input = "我感觉最近手机用得有点多"
            val caps = CapabilityDiscovery.initialCapabilities(input, null)
            assertTrue("应路由到使用分析", caps.contains("usage_analysis"))
            assertTrue("应带上评分能力", caps.contains("score_review"))

            // 结论只有「感觉」没有数据 → 评估器必须要求补充
            val plan = TaskPlan("t", "分析最近使用", TaskMode.ANALYSIS, listOf(TaskStep("s1", "查数据", listOf("usage_analysis"))), emptyList())
            val vague = ResultEvaluator.evaluate("你最近用得确实有点多，建议少用一点。", listOf("get_usage_trend"), plan)
            assertEquals("无数字的结论应被打回", EvaluationVerdict.NEED_MORE, vague.verdict)
            val solid = ResultEvaluator.evaluate("最近 7 天日均 180 分钟，比上周多 30 分钟。", listOf("get_usage_trend"), plan)
            assertEquals(EvaluationVerdict.OK, solid.verdict)
            "caps=$caps；无数据结论被打回；有数字结论通过"
        }

        // ---------- Case 2：多目标复杂任务 ----------
        results += runCase(2, "多目标：分析一月 + 找问题 + 改善计划", "classifier + planner + evaluator") {
            val input = "分析一下我最近一个月的使用习惯，找出三个问题，然后制定一个改善计划"
            val mode = TaskModeClassifier.classify(input)
            assertEquals("应识别为目标型任务", TaskMode.GOAL, mode)

            val planJson = """
                {"goal":"分析 30 天并给出改善计划","steps":[
                  {"description":"查询近 30 天总量与趋势","capabilities":["usage_analysis"]},
                  {"description":"找出 3 个主要问题","capabilities":["usage_analysis","score_review"]},
                  {"description":"给出改善建议","capabilities":["quota_control"]}],
                 "completion_criteria":["引用真实数字","指出 3 个问题","给出可执行建议"]}
            """.trimIndent()
            val plan = TaskPlanner.parse(planJson, mode)
            assertNotNull("计划应解析成功", plan)
            assertTrue("计划步骤应 ≤3（预算安全）", plan!!.steps.size <= 3)
            assertEquals(3, plan.completionCriteria.size)

            val empty = ResultEvaluator.evaluate("", emptyList(), plan)
            assertEquals(EvaluationVerdict.NEED_MORE, empty.verdict)
            "mode=$mode；steps=${plan.steps.size}；criteria=${plan.completionCriteria.size}"
        }

        // ---------- Case 3：上下文感知（当前应用详情页） ----------
        results += runCase(3, "上下文：在抖音详情页问「为什么最近用这么久」", "context + routing + subagent") {
            val ctx = AgentContext(entryPage = "app_detail", focusPackage = "com.ss.android.ugc.aweme", focusAppName = "抖音")
            val input = "为什么最近这个软件使用时间这么长"
            val caps = CapabilityDiscovery.initialCapabilities(input, ctx)
            assertTrue("页面有当前应用时必须带使用分析", caps.contains("usage_analysis"))
            assertTrue("上下文描述应包含应用名", ctx.describe().contains("抖音"))
            assertEquals("复杂问题应触发模式判定为分析", TaskMode.ANALYSIS, TaskModeClassifier.classify(input, ctx))
            assertTrue("分析 + 预算充足时应允许并行专家", SubAgentPolicy.shouldFanOut(TaskMode.ANALYSIS, 6, input))
            "caps=$caps；describe=${ctx.describe()}"
        }

        // ---------- Case 4：动态能力发现 / 缺能力识别 ----------
        results += runCase(4, "缺能力：是否总在不同 App 间切换", "catalog + feature advisor") {
            val input = "我想知道自己是不是总是在不同软件之间来回切换"
            val caps = CapabilityDiscovery.initialCapabilities(input, null)
            assertTrue("应路由到使用分析（切换关键词）", caps.contains("usage_analysis"))
            assertNull("当前没有「切换分析」专用能力，不能假装有", CapabilityCatalog.byId("switch_analysis"))

            // 识别缺失能力 → 形成功能提案；引用不存在的能力必须被拒绝
            val bad = FeatureAdvisor.validate(
                "App 切换分析", "想知道是否频繁切换", listOf("应用切换序列", "会话时长"),
                listOf("usage_analysis", "switch_analysis"), "P1", "HIGH",
            )
            assertNotNull("引用不存在的能力必须被拒绝", bad)
            val good = FeatureAdvisor.validate(
                "App 切换分析", "想知道是否频繁切换", listOf("应用切换序列", "会话时长"),
                listOf("usage_analysis"), "P1", "HIGH",
            )
            assertNull("基于现有能力提出提案应通过", good)
            "caps=$caps；缺能力被识别并可形成提案"
        }

        // ---------- Case 5：自定义监控 ----------
        results += runCase(5, "自定义监控：工作日微信 3 小时，周末不限", "monitor validator + schedule") {
            val rule = MonitorDefinition(
                id = "",
                name = "工作日微信 3 小时",
                packageName = "com.tencent.mm",
                metric = MonitorMetric.FOREGROUND_DURATION,
                operator = MonitorOperator.GREATER_THAN,
                threshold = 180,
                schedule = MonitorSchedule.WEEKDAYS,
            )
            assertEquals(MonitorValidator.Result.Ok, MonitorValidator.validate(rule, emptyList()))
            val describe = MonitorValidator.describe(rule) { "微信" }
            assertTrue(describe.contains("工作日"))
            assertTrue(describe.contains("180 分钟"))
            assertTrue("工作日规则周一应生效", rule.schedule.matches(DayOfWeek.MONDAY))
            assertFalse("工作日规则周六不应生效", rule.schedule.matches(DayOfWeek.SATURDAY))
            "describe=$describe；周末不生效"
        }

        // ---------- Case 6：长期反馈 ----------
        results += runCase(6, "长期反馈：建议太严格 → 以后别重复", "feedback detector + memory") {
            val detected = FeedbackDetector.detect("这个建议太严格了，不要频繁提醒我")
            assertNotNull("应识别为拒绝信号", detected)
            assertEquals("REJECT", detected!!.signal)

            val feedback = listOf(
                AgentFeedbackEntity("f1", "suggestion", "", "REJECT", "每天只玩 30 分钟太严格", 1L),
                AgentFeedbackEntity("f2", "suggestion", "", "PREFER", "更喜欢温和提醒", 2L),
            )
            val section = MemoryEngine.renderPromptSection(emptyList(), emptyList(), feedback)
            assertTrue("记忆摘要应包含被拒绝方案", section.contains("太严格"))
            assertTrue("记忆摘要应要求不重复被拒方案", section.contains("不要原样重复被拒绝过的方案"))

            // 同一类建议不能原样重复
            assertTrue(
                "「每天只玩 30 分钟」应命中被拒方案",
                com.aid.usageguard.agent.memory.FeedbackSimilarity.isSimilar(
                    "每天只玩 30 分钟",
                    listOf("每天只玩 30 分钟太严格"),
                ),
            )
            assertFalse(
                "「设置夜间专注时段」不应被误伤",
                com.aid.usageguard.agent.memory.FeedbackSimilarity.isSimilar(
                    "设置夜间专注时段",
                    listOf("每天只玩 30 分钟太严格"),
                ),
            )
            "signal=${detected.signal}；记忆摘要含拒绝项与约束；相似度判定正确"
        }

        // ---------- Case 7：主动分析 ----------
        results += runCase(7, "主动分析：连续一周增长触发洞察", "insight detector + proactive parser") {
            val daily = (1..7).map { DailyTotal("2026-10-${it.toString().padStart(2, '0')}", 120) } +
                (1..7).map { DailyTotal("2026-11-0$it", 180) }
            val candidates = InsightDetector.detect(daily, emptyMap(), emptyMap(), 0, 360, "2026-11-07")
            val total = candidates.firstOrNull { it.kind == InsightKind.TOTAL_INCREASE }
            assertNotNull("周环比 +50% 应被识别", total)

            val insight = ProactiveParser.parse(
                """{"title":"使用时间上升","detail":"最近一周日均 180 分钟，比前一周多 60 分钟。","suggestion":"试试 22 点后把短视频移出首屏。"}""",
                total!!,
                "2026-11-07",
            )
            assertNotNull("脚本化模型输出应能解析成洞察", insight)
            assertEquals(InsightKind.TOTAL_INCREASE, insight!!.kind)
            "candidates=${candidates.map { it.kind.name }}；insight=${insight.title}"
        }

        // ---------- Case 8：功能建议 ----------
        results += runCase(8, "功能建议：无意识启动监控", "feature advisor + response parser") {
            val proposal = FeatureProposal(
                title = "无意识启动监控",
                problem = "经常短时间内反复打开某个 App，但自己没有意识到",
                benefit = "发现高频低时长的碎片化使用",
                requiredMetrics = listOf("启动次数", "会话时长", "短会话比例"),
                requiredCapabilities = listOf("usage_analysis"),
                complexity = "P2",
                priority = "HIGH",
            )
            assertNull(
                FeatureAdvisor.validate(
                    proposal.title, proposal.problem, proposal.requiredMetrics,
                    proposal.requiredCapabilities, proposal.complexity, proposal.priority,
                ),
            )
            val roundTrip = FeatureAdvisor.fromJson(FeatureAdvisor.toJson(proposal))
            assertNotNull(roundTrip)
            assertEquals(proposal.title, roundTrip!!.title)
            assertEquals(3, roundTrip.requiredMetrics.size)

            // 结构化回复（统计卡 + 洞察卡 + 后续操作）也应能用
            val structured = ResponseParser.parse(
                """{"title":"本周概览","blocks":[
                   {"type":"statistic","title":"使用","items":[{"label":"日均","value":"180 分钟"}]},
                   {"type":"insight","title":"碎片化启动偏多","severity":"medium"},
                   {"type":"follow_up","label":"设个监控","prompt":"工作日微信超过 3 小时提醒我"}]}""",
            )
            assertNotNull("结构化回复应解析通过", structured)
            assertEquals(3, structured!!.blocks.size)
            "proposal=${roundTrip.title}；结构化 blocks=${structured.blocks.size}"
        }

        // ---------- 汇总 ----------
        val passed = results.count { it.passed }
        val total = results.size
        val table = buildString {
            appendLine("════════ Agent Benchmark（Case 1–8）════════")
            appendLine("Case | 场景 | 结果 | 关键组件 | 耗时")
            results.forEach { r ->
                appendLine(
                    "${r.id} | ${r.name} | ${if (r.passed) "PASS" else "FAIL"} | ${r.components} | ${r.elapsedMs}ms",
                )
            }
            appendLine("—— 通过 $passed/$total；总耗时 ${results.sumOf { it.elapsedMs }}ms")
            results.forEach { r -> if (!r.passed) appendLine("FAIL[${r.id}] ${r.detail}") }
        }
        println(table)

        assertEquals("Agent Benchmark 必须全部通过：\n$table", total, passed)
    }
}
