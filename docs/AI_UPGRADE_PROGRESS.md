# AI 升级进度（Phase 0–7）

对照《AI Usage Guard — AI Intelligence & Flexibility Upgrade》规格，记录每阶段交付与验证证据。

| Phase | 内容 | 状态 | 证据 |
|---|---|---|---|
| 0 | 架构审计 + 差距矩阵 + 计划 | ✅ 完成 | `docs/AI_ARCHITECTURE_AUDIT.md`；commit `8f538c0` |
| 1 | 动态能力发现（9 能力/34 工具、discover/load 元工具、执行器门禁） | ✅ 完成 | `agent/capability/*`、`agent/tools/CapabilityTools.kt`；48 单测；真机 `task tools: caps=[usage_analysis] tools=8/36`；commit `3568a4c` |
| 2 | 动态任务规划 + 结果评估（Direct/Analysis/Goal、TaskPlan、NEED_MORE 补轮、数据不足说明） | ✅ 代码/单测完成，真机回归待补 | `agent/plan/*`、`AgentLoop` 集成；66 单测通过；v23 已安装（设备锁屏未跑 Case 2/4） |
| 3 | 上下文工程 + 长期记忆（对话摘要压缩、DB v5 画像/目标/反馈/决策、反馈识别、记忆工具） | ✅ 代码/单测完成，真机待补 | `agent/context/*`、`agent/memory/*`、`data/repo/MemoryRepository.kt`；77 单测；APK v25 未安装（设备占用） |
| 4 | 结构化响应（AgentResponse + Compose 渲染） | ⏳ 未开始 | — |
| 5 | 自定义监控（MonitorDefinition + 规则引擎） | ⏳ 未开始 | — |
| 6 | 主动 Agent + Feature Advisor | ⏳ 未开始 | — |
| 7 | 多 Agent + 模型路由 | ⏳ 未开始 | — |
| 贯穿 | Agent Benchmark（Case 1–8） | ⏳ 未开始 | — |

## Phase 2 回归记录（真机）

- 首次真机验证：`planned mode=ANALYSIS steps=5 caps=[usage_analysis, system_maintenance, score_review, report_management, quota_control, restriction_control] tools=26`，
  但随后命中「模型请求已达上限（6 次）」——计划 5 步 + 预加载 `report_management` 触发**嵌套报告任务**，共享预算被烧穿。
- 修复：计划步数上限 5→3；预算剩余 <3 次不规划；预加载排除 `report` 能力；预算耗尽且无正文时用已执行工具结果组装部分结论。
- 修复后单测 66 通过；真机重测待设备空闲（安装需 vivo 确认弹窗）。

## 已知缺口（随阶段补齐）

- Phase 2 的步骤级状态（StepStarted/StepFinished）尚未通过 AgentEvent 暴露给 UI；当前计划以日志 + 注入指令体现。
- Phase 2 只读步骤仍是串行执行，并行化在 Phase 7 多 Agent 中处理。
- 失败重规划仅支持「评估触发一次补充轮」，完整 replan（改计划）留待 Phase 2.5/3。
