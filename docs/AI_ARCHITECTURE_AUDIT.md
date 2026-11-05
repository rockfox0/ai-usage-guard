# AI Usage Guard — AI 架构审计报告（Phase 0）

- 审计对象：`rockfox0/ai-usage-guard` @ `2cfff02`（v1.8.3，versionCode 20）
- 审计方式：直接读取仓库源码（Kotlin 5,655 行 agent 模块 + UI/数据层），逐项对照《AI Intelligence & Flexibility Upgrade》规格
- 审计日期：2026-11-06
- 结论：**当前实现是一个「单循环 + 全量工具 + 提示词约束」的 tool-calling Agent；规格中 P0 的 Intent / Capability Discovery / Planner / ResultEvaluator / ContextEngine 全部缺失或只有一半；长期记忆、结构化响应、自定义监控、主动 Agent、多 Agent 均未实现。**

---

## 1. 现状架构（读代码得到，不是设计文档）

```
AssistantViewModel (ui/assistant)
   │  chat(sessionId, input, AgentContext)
   ▼
AgentLoop（951 行）── 唯一 Agent 实现，单循环
   │  ├─ 系统提示词 = base(CHAT_SYSTEM 3369 字符) + 本地数据快照 + 页面上下文 + persona
   │  ├─ 历史 = AgentMemory.loadLlmHistory(最近 24 条，逐条截断 600/1200 字)
   │  ├─ tools = ToolRegistry.specs(TaskScope.CHAT)   ← 34 个工具一次性全给
   │  ├─ while (round < 4)：LLM(stream) → tool_calls → ToolExecutor → 结果回灌
   │  └─ 预算 TaskBudget：模型 ≤6 / 工具 ≤12 / 墙钟 ≤180s
   ▼
ToolExecutor（262 行）：白名单 → TaskScope(ToolKind) → JSON/JSON-Schema → withTimeout → 输出截断(6KB)
   ▼
34 个 Tool（Control15 / Usage6 / Quota4 / Whitelist2 / Guard2 / Score1 / Behavior1 / Report1 / Pending1 / Restriction1）
   │  写操作一律 → ActionDraftRepository（pending_action）→ 用户确认 → ActionExecutor
   ▼
数据层：Room（usage_session / app_usage_record / usage_session 聚合 / daily_score / behavior_event / agent_message / app_daily_limit / pending_action）
        + UsageStatsManager 采集（30 天回填）+ DataStore 设置
```

关键常量（源码）：`MAX_ROUNDS=4`、`MAX_TOOL_CALLS=12`、`MAX_MODEL_REQUESTS=6`、`MAX_WALL_CLOCK=180s`、`MEMORY_TURNS=24`、`DEFAULT_MAX_OUTPUT_CHARS=6000`、`CHAT_SYSTEM=3369 字符`、`REPORT_SYSTEM=1151 字符`。

---

## 2. 能力矩阵（规格 vs 现状）

图例：✅ 已实现可用 ｜ 🟡 部分实现 ｜ ❌ 缺失

### 2.1 规格 §3 动态意图理解 Intent Engine

| 要求 | 现状 | 证据 |
|---|---|---|
| 识别用户真实目标（而非仅 QUERY/REPORT） | ❌ 只靠提示词让模型自行理解，无显式意图标签 | `AgentLoop.kt:805` CHAT_SYSTEM 规则 |
| 多意图拆分 | ❌ 无 | — |
| 追问 vs 新任务 | 🟡 仅靠最近 24 条历史隐式判断 | `AgentLoop.kt:364`、`AgentMemory.loadLlmHistory` |
| 缺信息时主动询问 | 🟡 提示词里写了"不清楚就问"，无结构化缺槽检测 | CHAT_SYSTEM 规则 |
| 简单问题不额外增加模型请求 | ✅ 单轮直接回答 | 循环逻辑 |
| 复杂任务允许 LLM 分类 | ❌ 无分类阶段 | — |

### 2.2 规格 §4 动态能力发现 Capability Discovery

| 要求 | 现状 | 证据 |
|---|---|---|
| CapabilityDescriptor / Catalog / Discovery | ❌ 不存在 `agent/capability/` 包 | — |
| 不必每次暴露全部 Tool Schema | ❌ CHAT 域一次性把 34 个工具 schema 全部下发 | `ToolRegistry.specs(scope)`；`AgentLoop` 调 `registry.specs(request.scope)` |
| 模型主动发现未加载能力 | ❌ 工具集合固定，模型无法"再要更多工具" | — |
| 多能力组合 | 🟡 34 个工具同时可用，等于"全加载"，无按能力组合 | — |
| 工具权限仍受 TaskScope 限制 | ✅ | `ToolScopePolicy.allows` + `ToolExecutor` 独立校验 |
| 未注册工具不可执行 | ✅ | `registry.find` 白名单 + executor 拦截 |
| 参数 Schema 清晰 | ✅ | `JsonSchema` + `validateArguments` |
| 根据结果发现还需要其他能力 | ❌ 无 `discover`/`load` 机制 | — |

### 2.3 规格 §5/§6 任务规划 + 结果评估

| 要求 | 现状 | 证据 |
|---|---|---|
| Direct / Analysis / Goal 三种模式 | ❌ 只有一种模式：固定 4 轮 tool loop | `runTask` |
| TaskPlan / TaskStep / 依赖 | ❌ 无 | — |
| 动态新增/删除/调整步骤 | ❌ 无 | — |
| 完成条件 | 🟡 只有报告域「至少用 3 个工具」的软提示 | `MIN_TOOLS_FOR_REPORT` |
| 只读工具并行执行 | ❌ 严格串行 `for (call in response.toolCalls)` | `AgentLoop.kt:492` 附近 |
| 副作用不能被并行绕过确认 | ✅ 写操作只生成草案 | `ToolKind.PROPOSE` + `ActionExecutor` |
| 失败后重新规划 | ❌ 失败仅把错误回给模型，无 replan | — |
| ResultEvaluator（数据完整性/目标达成/矛盾检测） | ❌ 无；30 天数据不足只靠工具返回文本 note，模型可选择忽略 | `get_usage_trend` note |
| 预算上限 / 用户中止 | ✅ | `TaskBudget`、`cancelCurrent()` |

### 2.4 规格 §7 Context Engineering

| 要求 | 现状 | 证据 |
|---|---|---|
| ScreenContext（页面/对象/日期/范围） | 🟡 仅 `AgentContext(entryPage/focusPackage/focusAppName/rangeDays/focusDate)` 5 个字段，无筛选条件/数据摘要 | `AgentContracts.kt` |
| ConversationContext（目标/进度/已得结论摘要） | ❌ 只有原始最近 24 条消息 | `AgentMemory` |
| 本地数据快照 | ✅ 今日/7 天/30 天日均/Top3/白名单额度（v1.8.2） | `AgentLoop.snapshotSection` |
| Context Compression（摘要/去重/删无关结果） | ❌ 仅按条数截断与单条字数截断 | `truncateForHistory` |
| 工具结果保留来源/时间范围 | 🟡 单次任务内保留，任务结束丢弃 | — |

### 2.5 规格 §8 长期记忆 Memory Intelligence

| 要求 | 现状 | 证据 |
|---|---|---|
| 聊天历史持久化 | ✅ | `agent_message` 表 + `AgentMemory` |
| UserProfileMemory | 🟡 只有用户手动设置的 persona/设置（`aiNickname/aiTone/aiFocus/aiCustomPrompt`），不是学习来的 | `SettingsRepository` |
| GoalMemory | ❌ 无"用户目标"实体 | — |
| DecisionMemory（建议/接受/拒绝/效果） | ❌ 无（`agent_report` 只存报告文本） | — |
| FeedbackMemory | ❌ 无 | — |
| 反馈影响后续建议 | ❌ 无机制（最多被聊天历史隐式带入） | — |
| Memory Retrieval（按目标/相关度取记忆） | ❌ 无 | — |

### 2.6 规格 §9 结构化响应与动态 UI

| 要求 | 现状 | 证据 |
|---|---|---|
| AgentResponse + 8 类 Block | 🟡 只定义了 `AgentEvent.DataBlock(kind, payloadJson)` 但从未 emit；实际输出仅 Markdown + ActionProposed 卡片 | `AgentContracts.kt`；`AgentLoop.emitEvent` |
| 本地白名单 UI 类型 | 🟡 只有 PendingAction 卡片一套 | `ui/actions/` |
| 图表数据来自本地 | 🟡 报告页有本地图表，但 Agent 回复里没有 | — |
| 解析失败回退 Markdown | ✅（当前只有 Markdown） | `MarkdownLite.kt` |
| 建议卡点击追问 / 行动卡确认 | 🟡 行动卡（待确认草案）支持；建议卡无 | `AssistantViewModel.confirmAction` |

### 2.7 规格 §10 用户自定义监控

| 要求 | 现状 |
|---|---|
| MonitorDefinition / 规则引擎 / 自然语言建规则 | ❌ 全部缺失。现有的额度/单应用限额/专注时段是**硬编码设置**，不是通用规则引擎；`restriction_rule` 表只存"手动限制的包名" |

### 2.8 规格 §11/§12 主动 Agent / Feature Advisor

| 要求 | 现状 | 证据 |
|---|---|---|
| 本地行为检测 | ✅ 6 类（超目标/使用增加/长会话/深夜/频繁启动/多日增长） | `BehaviorAnalyzer.kt` |
| 定时任务 | ✅ 5 个 Worker（同步/分析/日报/周报/保活看门狗） | `work/AgentScheduler.kt` |
| ProactiveAgent（LLM 深度洞察 + 去重 + 频率 + 偏好） | 🟡 只有本地规则触发通知；无 LLM 洞察、无事件去重表、无偏好开关 | `AnomalyCheckWorker` + `BehaviorAnalyzer` |
| FeatureAdvisor / FeatureProposal | ❌ 无 | — |

### 2.9 规格 §13 多 Agent / 模型路由

| 要求 | 现状 |
|---|---|
| SubAgentManager + 6 个专家 Agent | ❌ 无；唯一"嵌套"是 chat 里 `generate_report_now` 调一次报告 Agent，共享预算、不发事件 |
| 模型路由（按复杂度选模型） | ❌ 只有单一 `LlmClientFactory`（base/model 来自设置），无路由 |

### 2.10 规格 §15 Benchmark

| 要求 | 现状 |
|---|---|
| Cases 1–8 回归 + 指标（完成率/工具准确率/参数正确率/Token/延迟…） | ❌ 无；只有 34 个纯函数 JVM 单测（聚合/策略/权限矩阵/额度） |

---

## 3. 关键差距（按影响排序）

1. **工具全量下发 + 单循环**：34 个工具 schema 一次性进 prompt（估算 6–8k tokens 量级），对 deepseek-flash 是负担，且模型没有"按需加载"能力 → 规格 P0 的 Capability Discovery 是收益最大的改造。
2. **没有"计划"这个状态**：现在 Agent 只有"下一轮模型请求"，无法表达多步骤目标、完成条件、依赖与重规划；复杂任务靠模型自由发挥，容易漏步骤或过早下结论。
3. **没有结果评估**：工具返回 `ok=true` 就继续，数据不足（如只有 8/30 天）只能靠模型自觉；规格 §6.4 明确要求必须识别数据不足。
4. **上下文只有"最近 N 条 + 5 个页面字段"**：没有对话目标摘要、没有压缩策略、没有任务级上下文与工具结果上下文，长对话/多任务会漂移。
5. **记忆只有聊天记录**：用户目标、决策、反馈、偏好都没有持久化实体，导致"用户拒绝过的建议下次还会提"。
6. **回复只有 Markdown**：`DataBlock` 空转；无法给统计卡/图表/建议卡/对比块。
7. **主动能力只有本地规则**：没有 LLM 洞察、没有去重与频率控制、没有按偏好关闭。
8. **没有规则引擎**：自定义监控（规格 §10）完全缺失，生态扩展性受限。
9. **没有 Benchmark**：无法判断改造是否变好，回归全靠手工真机点。

---

## 4. 约束与风险（改造必须遵守）

| 约束 | 说明 |
|---|---|
| 预算模型 | 现有 6 请求/12 工具/180s 必须保留并复用；新增的「规划/评估」不应凭空增加模型请求数，优先**同一次请求内用 JSON 输出计划**，或**本地规则评估** |
| 权限矩阵 | 动态加载的工具必须带 `ToolKind`，`TaskScope` 过滤与 `ToolExecutor` 独立校验不可绕过 |
| 写操作确认 | 任何"行动卡"最终仍落到 `pending_action` + `ActionExecutor`，不允许 AI 直接生效 |
| 数据真实性 | 结构化响应的数字必须来自工具结果（`DataBlock` 由本地组装，不让模型填数） |
| 意图判断 | 不允许仅本地关键词匹配作为唯一手段（规格 §18.7），必须保留 LLM 兜底 |
| 通知频率 | 主动洞察必须去重 + 节流 + 可关闭，禁止医学/心理诊断标签 |
| OEM 存活 | 后台能力受系统限制（force-stop 不可恢复），主动分析不能假设一定能跑 |
| 兼容性 | 不引入第三方 Agent 框架；继续用现有 `LlmClient`/Room/Compose |

---

## 5. 改造计划（Phase 1–7，含文件清单）

> 原则：复用 `AgentLoop / ToolRegistry / ToolExecutor / TaskScope / TaskBudget`，增量演进，每阶段可独立编译/测试/回归。

### Phase 1：动态能力发现（P0，收益最大）
**新增**
- `agent/capability/CapabilityDescriptor.kt`（id/name/description/category/toolNames/examples）
- `agent/capability/CapabilityCatalog.kt`（34 个工具 → 8~10 个能力，静态目录 + 校验：每个工具必须归属且只属于一个能力）
- `agent/capability/CapabilityDiscovery.kt`（模式 A 本地快速路由：基于关键词/页面上下文缩小候选；模式 B 暴露 `discover_capabilities` 元工具，由模型搜索）
- 新元工具：`discover_capabilities(query)`（QUERY）、`load_capability(id)`（QUERY，返回该能力工具 schema）
**修改**
- `ToolRegistry`：`specsFor(capabilities)`；`AgentLoop`：首轮只给「路由命中的能力 + 元工具」，模型可 `load_capability` 后**下一轮**扩展工具集
- `CHAT_SYSTEM`：说明能力发现流程；`TaskScope`：元工具仅 CHAT/REPORT 允许 QUERY 版
**测试**：能力目录完整性（34 工具全覆盖、无重复）、路由准确性（20 条样例）、权限不泄漏（REPORT 域加载不到写工具）
**验收**：简单查询只下发 ~6 个工具；复杂请求模型会先 discover/load；工具集变化不影响预算/权限。

### Phase 2：任务规划 + 结果评估（P0）
**新增**
- `agent/plan/TaskMode.kt`（DIRECT/ANALYSIS/GOAL）、`TaskPlan.kt`、`TaskStep.kt`、`TaskPlanner.kt`（一次模型请求返回 JSON 计划；本地校验：步数 ≤6、仅引用已加载能力、写步骤必须 `requiresConfirmation`）
- `agent/plan/ResultEvaluator.kt`（本地规则：数据天数/字段完整性/目标关键词覆盖/矛盾检测；不达标→产出 `ReplanDirective`）
- `AgentEvent` 扩展：`PlanCreated / StepStarted / StepFinished / Replanned / EvaluationFailed`
**修改**
- `AgentLoop.runTask`：DIRECT 走现有单循环；ANALYSIS/GOAL 先 `TaskPlanner`，按 steps 顺序执行（只读可并行 `async`，写操作串行且只出草案），每步后 `ResultEvaluator` 决定继续/补工具/重规划/结束
- 预算：规划请求计入 6 次额度；重规划最多 1 次（可配）
**测试**：计划 JSON 校验、越权能力引用拒绝、数据不足（8/30 天）必须 `EvaluationFailed` 而非给结论、重规划触发条件
**验收**：Case 2（一月习惯分析+计划）、Case 4（缺能力识别）。

### Phase 3：上下文工程 + 长期记忆（P0/P1）
**新增**
- `agent/context/ScreenContext.kt`、`ConversationContext.kt`、`ContextEngine.kt`（组装/压缩：目标优先、结论摘要、工具结果去重、超长摘要本地模板+可选 LLM）
- `agent/memory/UserProfileMemory.kt`、`GoalMemory.kt`、`DecisionMemory.kt`、`FeedbackMemory.kt`、`MemoryEngine.kt`（写入/检索）
- DB v5：`agent_memory(kind,key,valueJson,confidence,updatedAt)`、`agent_goal(goal,targetPeriod,status)`、`agent_feedback(targetType,targetId,signal,reason)`
- 工具：`remember_preference`（PROPOSE，草案确认后写记忆）、`list_goals`、`get_feedback_history`
**修改**：`AgentLoop` 系统提示词注入「长期记忆摘要」；`AssistantViewModel` 捕获"太严格/不要提醒/以后别推荐"等反馈信号 → FeedbackMemory
**测试**：记忆写入/检索、反馈影响下次推荐（同建议第二次不得原样出现）、上下文压缩后 token 下降且目标保留
**验收**：Case 1（模糊表达）、Case 3（页面上下文）、Case 6（长期偏好）。

### Phase 4：结构化响应（P1）
**新增**
- `agent/response/AgentResponse.kt`（Text/Statistic/Card/Chart/Insight/Recommendation/Action/Comparison/FollowUp 白名单 block）
- `agent/response/ResponseParser.kt`（模型输出 JSON → 校验 → 失败回退 Markdown）
- `ui/assistant/render/AgentResponseRenderer.kt` + 各 Block Composable（数据来自 `DataBlock`/工具结果，本地组装）
**修改**：`AgentLoop` 末尾按需 emit `DataBlock`（本地数字）；`AssistantScreen` 渲染；图表复用现有本地聚合
**测试**：非法 block 回退、数字必须与本地数据一致、行动卡仍走确认
**验收**：Case 1/8 的回复出现统计卡/洞察卡/建议卡且可点击追问。

### Phase 5：自定义监控（P1，差异化）
**新增**
- `domain/monitor/MonitorDefinition.kt`（规格 §10.2 字段）、`MonitorValidator.kt`（指标/聚合/操作符/时间窗/应用真实性校验）、`MonitorEngine.kt`（本地计算 + 阈值判断 + 去重）
- DB v6：`monitor_rule`、`monitor_trigger`
- 工具：`create_monitor`（PROPOSE 草案）、`list_monitors`、`remove_monitor`、`get_monitor_history`
- `work/MonitorCheckWorker.kt`（15 分钟，只读本地数据判断，不调模型）
- UI：设置页「我的监控」列表 + 规则预览卡 + 确认流程
**测试**：规则解析（工作日/周末、单/多 App、时间段）、阈值边界、去重与每日最多通知数
**验收**：Case 5（工作日微信 3 小时提醒、周末不限制）。

### Phase 6：主动 Agent + Feature Advisor（P2）
**新增**
- `agent/proactive/ProactiveAgent.kt`、`InsightCandidate.kt`（候选事件 → 上下文/记忆 → LLM 洞察 → 去重/节流 → 通知/卡片）
- `agent/feature/FeatureAdvisor.kt`、`FeatureProposal.kt`（能力清单 + 数据可用性 → 结构化提案）
- 工具：`analyze_insight`（内部）、`propose_feature`、`list_feature_proposals`
- DB v7：`proactive_insight`（去重键/已读/反馈）
**修改**：`AnomalyCheckWorker` 调用 ProactiveAgent；通知带"有用/没用/以后别提醒"反馈入口
**测试**：去重、频率上限、关闭开关生效、无医学诊断词
**验收**：Case 7（连续增长触发洞察）、Case 8（缺功能提案）。

### Phase 7：多 Agent + 模型路由（P2/P3）
**新增**：`agent/multi/SubAgentManager.kt` + 4 个专家（Usage/Behavior/Habit/Report，均为只读、独立 prompt/工具子集/预算子份额）；`agent/llm/ModelRouter.kt`（复杂度→模型，默认仍 flash）
**测试**：并行只读聚合、预算不超、专家失败降级主 Agent
**验收**：30 天复杂分析的步骤缩短/质量提升，简单任务不启动多 Agent。

### 贯穿：Benchmark（§15）
- `app/src/test/java/com/aid/usageguard/agent/bench/AgentBenchmark.kt`：FakeLlmClient 回放固定响应 + 本地数据夹具，覆盖 Cases 1–8
- 指标：完成率、工具选择准确率、参数正确率、无效调用数、Token（响应字符估算）、轮次、耗时
- `scripts/agent_bench.sh` 一键跑；CI 可选

---

## 6. 里程碑与建议顺序

1. **Phase 1（能力发现）**：最直接降低 token、提升工具选择准确率，且不改变交互语义 → 先做。
2. **Phase 2（规划+评估）**：把"多步骤、数据不足、重规划"补上，直接对应 Case 2/4。
3. **Phase 3（上下文+记忆）**：对应 Case 1/3/6，并为 Phase 6 打基础。
4. **Phase 4（结构化）**：体验升级，依赖 Phase 2 的步骤事件。
5. **Phase 5（监控）**：差异化能力，独立性强，可与 4 并行。
6. **Phase 6/7**：依赖前面全部；P2 优先级。

预计改动规模：新增 ~25–30 个文件、DB 迁移 3 次（v5–v7）、修改 `AgentLoop/ToolRegistry/AgentContracts/AssistantViewModel/AssistantScreen/AgentScheduler` 与提示词；每阶段保持"编译 + 单测 + 真机回归"三步。

---

## 7. 审计结论

- 现有基础（工具、权限、预算、流式、确认、数据层）**质量足够**，不需要重写 Agent；规格要求的多数能力是"在 AgentLoop 外围增加引擎/状态/持久化"。
- 最大缺口是 **Capability Discovery（工具全量下发）** 与 **Planning/Evaluation（没有计划与结果校验）**，这两个 P0 直接决定"是否像 Agent"。
- 建议按 Phase 1 → 2 → 3 → 4 → 5 → 6 → 7 推进，每阶段独立可用、可回滚、可回归。
