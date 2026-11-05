# Agent Benchmark（Case 1–8 验收）

- 版本：v1.9（Phase 0–7 完成后的验收基线）
- 运行：`./scripts/agent_bench.sh`（纯 JVM 单测，不需要设备 / API Key / 网络）
- 被测对象：**真实生产代码**的 Agent 决策链（不是 mock 的假实现）：
  模式判定、能力路由、任务规划解析、结果评估、监控规则校验、反馈识别、主动候选检测、功能提案、结构化回复解析。

## 结果（2026-11-06）

```
Case | 场景 | 结果 | 关键组件 | 耗时
1 | 模糊表达：最近手机用得有点多 | PASS | routing + evaluator | 25ms
2 | 多目标：分析一月 + 找问题 + 改善计划 | PASS | classifier + planner + evaluator | 8ms
3 | 上下文：在抖音详情页问「为什么最近用这么久」 | PASS | context + routing + subagent | 3ms
4 | 缺能力：是否总在不同 App 间切换 | PASS | catalog + feature advisor | 1ms
5 | 自定义监控：工作日微信 3 小时，周末不限 | PASS | monitor validator + schedule | 4ms
6 | 长期反馈：建议太严格 → 以后别重复 | PASS | feedback detector + memory | 2ms
7 | 主动分析：连续一周增长触发洞察 | PASS | insight detector + proactive parser | 5ms
8 | 功能建议：无意识启动监控 | PASS | feature advisor + response parser | 3ms
—— 通过 8/8；总耗时 51ms
```

## 每个 Case 的判定标准

| Case | 期望行为 | 断言点 |
|---|---|---|
| 1 模糊表达 | 不直接下结论，先查数据；无数字的"感觉"被打回 | 路由含 `usage_analysis`/`score_review`；`ResultEvaluator` 对无数字结论返回 `NEED_MORE` |
| 2 多目标 | 识别为 GOAL；生成 ≤3 步计划与完成条件 | `TaskModeClassifier=GOAL`；`TaskPlanner.parse` 通过且 criteria=3；空回答 `NEED_MORE` |
| 3 上下文 | 自动定位当前应用，不需要用户重复说 App 名 | 页面上下文 → 路由含 `usage_analysis`；`AgentContext.describe()` 含应用名；复杂问题允许并行专家 |
| 4 缺能力 | 不虚构"切换统计"能力，而是识别缺失并形成功能提案 | 目录中无 `switch_analysis`；`FeatureAdvisor.validate` 拒绝未知能力、接受基于现有能力的提案 |
| 5 自定义监控 | 生成正确规则（工作日、微信、180 分钟），周末不生效 | `MonitorValidator` 通过；`describe` 含工作日/180 分钟；`MonitorSchedule.matches` 周六=false |
| 6 长期反馈 | 记录拒绝信号，后续建议避开同类方案 | `FeedbackDetector=REJECT`；记忆摘要含被拒项与"不要原样重复"约束 |
| 7 主动分析 | 本地先发现周环比增长，再生成洞察 | `InsightDetector` 命中 `TOTAL_INCREASE`；`ProactiveParser` 解析出洞察 |
| 8 功能建议 | 结构化提案（问题/指标/能力/优先级）+ 结构化回复 | `FeatureProposal` 校验通过、JSON 往返；`ResponseParser` 解析统计/洞察/后续操作 3 个 block |

## 指标（本轮）

- 任务完成率：**8/8 = 100%**
- 无效能力引用：0（Case 4 断言拒绝未知能力）
- 规划步数上限：≤3（预算安全）
- 结构化回复解析：通过；非法 block 由 `ResponseParserTest`/`ProactiveParserTest` 覆盖回退
- 单测总量：**125 个**（124 常规 + 1 Benchmark），全部通过
- Benchmark 总耗时：约 51ms（不含 Gradle 启动）

## 说明

- Case 1–8 中涉及 LLM 的环节用「脚本化输入」（固定 JSON）验证解析/校验路径，网络与 Key 依赖为 0；
- 真机端到端（真实模型 + 真实手机数据）仍需要设备回归：Phase 4/5/6/7 的 UI/通知链路在设备恢复后补测；
- 每次修改 Prompt、工具 Schema、Planner 或 Evaluator 后都应重跑本 Benchmark。
