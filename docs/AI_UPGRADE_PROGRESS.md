# AI 升级进度（Phase 0–7）

对照《AI Usage Guard — AI Intelligence & Flexibility Upgrade》规格，记录每阶段交付与验证证据。

| Phase | 内容 | 状态 | 证据 |
|---|---|---|---|
| 0 | 架构审计 + 差距矩阵 + 计划 | ✅ 完成 | `docs/AI_ARCHITECTURE_AUDIT.md`；commit `8f538c0` |
| 1 | 动态能力发现（9 能力/34 工具、discover/load 元工具、执行器门禁） | ✅ 完成 | `agent/capability/*`、`agent/tools/CapabilityTools.kt`；48 单测；真机 `task tools: caps=[usage_analysis] tools=8/36`；commit `3568a4c` |
| 2 | 动态任务规划 + 结果评估（Direct/Analysis/Goal、TaskPlan、NEED_MORE 补轮、数据不足说明） | ✅ 完成（真机已验证） | `agent/plan/*`、`AgentLoop` 集成；66 单测；真机：`agent_memory(plan)` 记录 mode=ANALYSIS / 3 步 / 能力 / 4 条完成条件；执行工具 `get_usage_trend`+`get_usage_score`；回答明确「30 天只有 9 天有记录」 |
| 3 | 上下文工程 + 长期记忆（对话摘要压缩、DB v5 画像/目标/反馈/决策、反馈识别、记忆工具） | ✅ 完成（真机已验证） | `agent/context/*`、`agent/memory/*`、`data/repo/MemoryRepository.kt`；77 单测；真机：REJECT/PREFER 写入 `agent_feedback`、`save_memory` 写 `agent_memory(profile)`、回答引用偏好、计划落库 |
| 4 | 结构化响应（白名单 Block + 解析器 + Compose 渲染 + 本地图表） | ✅ 代码/单测完成，真机待补（设备断开） | `agent/response/*`、`ui/assistant/StructuredResponseView.kt`；86 单测；APK v27 未安装 |
| 5 | 自定义监控（MonitorDefinition、校验、DB v6、15 分钟本地引擎、4 个工具、设置页卡片） | ✅ 代码/单测完成，真机待补（设备未连接） | `domain/monitor/*`、`data/repo/MonitorRepository.kt`、`work/MonitorCheckWorker.kt`、`agent/tools/MonitorTools.kt`；94 单测；APK v29 |
| 6 | 主动 Agent + Feature Advisor（本地候选、每日 1 条洞察、DB v7、功能提案） | ✅ 代码/单测完成，真机待补（设备未连接） | `domain/insight/*`、`agent/proactive/*`、`agent/feature/*`、`data/repo/InsightRepository.kt`；111 单测；APK v30 |
| 7 | 模型路由 + 只读专家子 Agent（ai_planner_model、并行 2 专家、预算/策略门控） | ✅ 代码/单测完成，真机待补（设备未连接） | `agent/llm/ModelRouter.kt`、`agent/multi/*`、`LlmClientFactory` 覆盖模型；124 单测；APK v31 |
| 贯穿 | Agent Benchmark（Case 1–8） | ✅ 完成 | `app/src/test/.../agent/bench/AgentBenchmarkTest.kt`；`scripts/agent_bench.sh`；`docs/AGENT_BENCHMARK.md`；8/8 通过、总耗时 51ms |

## 真机验证（Pixel 6 / Android 13，v33）

- 安装/权限（WRITE_SECURE_SETTINGS、POST_NOTIFICATIONS、GET_USAGE_STATS）+ 无障碍绑定 ✅
- 保活自恢复：关闭无障碍后约 20 秒自动恢复 ✅
- 监控引擎：注入规则 → 调试广播 → `monitor triggered value=47 threshold=1` + 写入触发记录 + 通知 ✅
- 调试入口：`debug/DebugReceiver`（仅 debuggable 构建），支持手动触发监控/同步/主动分析
- 待解锁后补：设置页卡片、结构化回复、监控创建 UI、Phase 2/3 对话回归（另需 AI Key）

## Phase 2/3 真机验证（vivo，v26）

- 规划：`agent_memory(kind=plan)` → mode=ANALYSIS，3 步（30 天口径/排行趋势/评分健康度），4 条完成条件；执行只调用 `get_usage_trend`、`get_usage_score`
- 数据不足：回答明确「30 天里只有 9 天有记录，21 天没有数据」
- 记忆：`too strict, do not remind me` → REJECT；`remember I prefer gentle reminders` → `save_memory` 写 profile，后续回答引用偏好（「按你的偏好，我就不再劝你设限额了」）
- 测试数据已清理，保留用户真实历史 49 条

## Phase 2 回归记录（真机）

- 首次真机验证：`planned mode=ANALYSIS steps=5 caps=[usage_analysis, system_maintenance, score_review, report_management, quota_control, restriction_control] tools=26`，
  但随后命中「模型请求已达上限（6 次）」——计划 5 步 + 预加载 `report_management` 触发**嵌套报告任务**，共享预算被烧穿。
- 修复：计划步数上限 5→3；预算剩余 <3 次不规划；预加载排除 `report` 能力；预算耗尽且无正文时用已执行工具结果组装部分结论。
- 修复后单测 66 通过；真机重测待设备空闲（安装需 vivo 确认弹窗）。

## 收尾补充（v34）

- 主动分析独立开关 `notify_proactive`（ProactiveAgent / 通知 / 工具 / 草案卡全链路）
- 设置页「AI 洞察」卡（开关 + 最近 5 条 + 空态）与「模型路由」卡（`ai_planner_model` 可视化配置）
- `SettingsRepository.setAiPlannerModel` 补齐；**126 单测通过**（含脚本化端到端管线测试）；APK v34 已装 Pixel
- 最终验收文档：`docs/AI_UPGRADE_ACCEPTANCE.md`（Case 对照 + 真机记录 + 已知限制）

## vivo 真机端到端（v37，2026-11-06）

- 设置页四卡：守护保活 / 我的监控 / AI 洞察 / 模型路由 ✅
- Phase 4 结构化回复：统计卡 + 本地 7 天柱状图 + 后续 chip ✅（正文无 JSON）
- Phase 5：AI 一句话建监控 → 确认 → 落库 → 设置页可见；本地引擎触发 `value=80 > threshold=1` + 通知 ✅
- Phase 6：主动洞察由真实模型生成「哔哩哔哩近七天大幅上升」→ 落库 + 通知 + 设置卡显示 ✅
- Phase 3 反馈：`too strict…` → REJECT 记忆，回复不再推进该建议 ✅

## v1.9.1 修复（2026-11-06，真机事故）

- 事故：超额模式下哔哩哔哩未超额仍被拦
- 根因：`restriction_rule`（旧手动限制列表）被 GuardService 无条件执行，与限制模式语义冲突
- 修复：手动名单只在 `MANUAL_ONLY` 生效；`restriction_mode` 成为唯一真源；新增 6 条回归测试（142 单测）
- 真机：v39 额度内 B 站正常打开；目标设为 1 分钟时仍能拦截（额度逻辑未破坏）

## 已知缺口（随阶段补齐）

- Phase 2 的步骤级状态（StepStarted/StepFinished）尚未通过 AgentEvent 暴露给 UI；当前计划以日志 + 注入指令体现。
- Phase 2 只读步骤仍是串行执行，并行化在 Phase 7 多 Agent 中处理。
- 失败重规划仅支持「评估触发一次补充轮」，完整 replan（改计划）留待 Phase 2.5/3。
