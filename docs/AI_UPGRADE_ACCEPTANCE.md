# AI Usage Guard — AI 升级最终验收报告（v1.9）

- 规格：《AI Usage Guard — AI Intelligence & Flexibility Upgrade》
- 仓库：https://github.com/rockfox0/ai-usage-guard
- 版本：v1.9（Phase 0–7 + Benchmark），versionCode 35 / versionName 1.9
- GitHub Release：https://github.com/rockfox0/ai-usage-guard/releases/tag/v1.9（含 APK）
- 日期：2026-11-06

## 1. 交付概览

| Phase | 内容 | 关键文件 | 状态 |
|---|---|---|---|
| 0 | 架构审计 + 差距矩阵 + 计划 | `docs/AI_ARCHITECTURE_AUDIT.md` | ✅ |
| 1 | 动态能力发现（10→12 能力、元工具、执行器门禁） | `agent/capability/*`、`agent/tools/CapabilityTools.kt` | ✅ 真机验证（首轮 8/36 工具） |
| 2 | 动态任务规划 + 结果评估（Direct/Analysis/Goal、TaskPlan、NEED_MORE 补轮、数据不足说明） | `agent/plan/*`、`AgentLoop` 集成 | ✅ 真机验证（3 步计划落库） |
| 3 | 上下文工程 + 长期记忆（对话摘要、DB v5、反馈识别、记忆工具） | `agent/context/*`、`agent/memory/*`、`data/repo/MemoryRepository.kt` | ✅ 真机验证（REJECT/PREFER、save_memory） |
| 4 | 结构化响应（白名单 Block + 解析器 + Compose 渲染 + 本地图表） | `agent/response/*`、`ui/assistant/StructuredResponseView.kt` | ✅ 代码/单测（真机 UI 待解锁补） |
| 5 | 自定义监控（规则校验、DB v6、15 分钟本地引擎、工具、设置卡） | `domain/monitor/*`、`work/MonitorCheckWorker.kt`、`agent/tools/MonitorTools.kt` | ✅ 真机验证（trigger + 通知） |
| 6 | 主动 Agent + Feature Advisor（本地候选、每日 1 条、DB v7、提案工具） | `agent/proactive/*`、`agent/feature/*`、`domain/insight/*` | ✅ 代码/单测（LLM 路径真机待 Key） |
| 7 | 模型路由 + 只读专家子 Agent | `agent/llm/ModelRouter.kt`、`agent/multi/*` | ✅ 代码/单测 |
| 贯穿 | Benchmark（Case 1–8） | `agent/bench/AgentBenchmarkTest.kt`、`docs/AGENT_BENCHMARK.md` | ✅ 8/8 |

## 2. 验收标准对照（规格 §17 Case 1–8）

| Case | 期望 | 实现 | 证据 |
|---|---|---|---|
| 1 模糊表达 | 不直接下结论，先查数据 | 能力路由 + ResultEvaluator 打回"无数字结论" | Benchmark Case 1；`ResultEvaluatorTest` |
| 2 多目标 | 自主规划多步并完成 | TaskMode GOAL → TaskPlanner（≤3 步 + 完成条件）→ 执行 → 评估 | Benchmark Case 2；真机 `agent_memory(plan)` |
| 3 上下文感知 | 自动识别当前 App | AgentContext + 能力路由 + 允许并行专家 | Benchmark Case 3 |
| 4 动态能力发现 | 缺能力要说明而不是编结论 | CapabilityCatalog 无"切换分析" → FeatureAdvisor 拒绝未知能力并可提案 | Benchmark Case 4 |
| 5 自定义监控 | 自然语言生成规则、本地执行 | create_monitor（草案）→ ActionExecutor 落库 → 15 分钟引擎 → 通知 | Benchmark Case 5；Pixel 真机 trigger |
| 6 长期反馈 | 拒绝后不再重复同类建议 | FeedbackDetector + FeedbackMemory + 记忆摘要约束 | Benchmark Case 6；真机 REJECT/PREFER |
| 7 主动分析 | 发现连续增长并给洞察 | InsightDetector + ProactiveAgent（每天 1 条、失败退化本地事实） | Benchmark Case 7；`InsightDetectorTest` |
| 8 功能建议 | 具体、基于现有能力的提案 | FeatureAdvisor + propose_feature + list_feature_proposals | Benchmark Case 8 |

Benchmark 运行：`./scripts/agent_bench.sh` → **8/8 PASS**；**135 个 JVM 单测全绿**。

## 3. 真机验证记录

### vivo V2527A（Android 16）
- Phase 1：`task tools: caps=[usage_analysis] tools=8/36`（工具从 36 → 8）
- Phase 2：`agent_memory(kind=plan)` 记录 3 步计划；执行 `get_usage_trend` + `get_usage_score`；回答明确「30 天只有 9 天有记录」
- Phase 3：`too strict…`→REJECT、`remember I prefer gentle reminders`→`save_memory` + PREFER；后续回答引用偏好
- 保活：关闭无障碍后约 20 秒自动恢复；force-stop 无法恢复（Android 限制，已文档化）

### Pixel 6（Android 13）
- 安装 v34 + 权限（WRITE_SECURE_SETTINGS/通知/使用情况）+ 无障碍绑定
- 保活自恢复：关闭后约 20 秒恢复
- **Phase 5 监控引擎**：注入规则 → `monitor triggered value=47 threshold=1` → `monitor_trigger` 落库 → `monitor_alert` 通知
- 调试入口（仅 debug）：`DebugReceiver` 手动触发监控/同步/主动分析

## 4. 已知限制（如实记录）

1. **设备 UI 端到端**：Pixel 处于锁屏，Phase 4 结构化卡片、Phase 6 洞察卡、Phase 2/3 对话 UI 截图尚未完成（代码/单测已覆盖；解锁后可补）。
2. **AI 依赖配置**：Pixel 未配置 Key，主动 Agent 的 LLM 路径与聊天类验收需配置后才可跑。
3. **force-stop**：系统级彻底停止无法自恢复（任何应用都无法），已说明。
4. **洞察去重粒度**：同一自然日最多 1 条，不同类型不会同日并列。
5. **模型路由**：仅在配置 `ai_planner_model` 时生效；未配置即全部使用默认模型。
6. **子 Agent**：只在 ANALYSIS + 预算 ≥5 + 复杂问题时启用；GOAL 任务不启用（预算保护）。

## 5. 复现命令

```bash
# 单测 + Benchmark
~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle :app:testDebugUnitTest
./scripts/agent_bench.sh

# 构建
gradle :app:assembleDebug

# 真机调试入口（debug 构建）
adb shell am broadcast -n com.aid.usageguard/.debug.DebugReceiver -a com.aid.usageguard.DEBUG_RUN_MONITORS
adb shell am broadcast -n com.aid.usageguard/.debug.DebugReceiver -a com.aid.usageguard.DEBUG_RUN_PROACTIVE
```

## 6. 最终验证（2026-11-06 02:06）

- `:app:testDebugUnitTest`：**135 tests / 0 failures**
- `./scripts/agent_bench.sh`：**Case 1–8 = 8/8 PASS**（总耗时 52ms）
- 远端 `main` = `b724dce`，工作区干净；GitHub Release `v1.9` 资产存在（12.4MB APK）
- 设备：Pixel 6 连续 4 轮锁屏（`mDreamingLockscreen=true`，需指纹/密码），UI 截图与带 Key 的对话端到端无法执行；
  该项已如实记录为外部环境限制，**不影响规格 §17 Case 1–8 与 §15 Benchmark 的验收结论**。

## 7. 结论

规格中的 P0（能力发现、规划与重规划、上下文、结果评估）与 P1（长期记忆、结构化响应、自定义监控）均已落地并有单测/真机证据；
P2（主动 Agent、Feature Advisor、多 Agent 与模型路由）已实现主要能力；Benchmark Case 1–8 全部通过。
剩余工作仅为「设备解锁后的 UI 截图与带 Key 的端到端对话验收」，不涉及架构改动。
