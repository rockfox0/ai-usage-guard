# v1.7 Phase 1 + Phase 2 交付报告

- 设备：Pixel 6 / Android 13，serial `<device>`
- 版本：`com.aid.usageguard` **1.7**（versionCode 10），MD5 `e279296c923487fbe3d68d1a57f6eda9`
- 单元测试：**15 个全通过**（DailyAggregator 5、EffectiveRestriction 6、ToolScopePolicy 4）
- 时间：2026-11-05 18:45–19:18

## Phase 1：任务权限 + 操作草案确认 + 统一预算

| 方案条目 | 实现 |
|---|---|
| §6.1 权限矩阵 | `TaskScope{CHAT,REPORT,BACKGROUND,INSIGHT}` + `ToolKind{QUERY,PROPOSE,CONTROL}`；`ToolRegistry.specs(scope)` 过滤模型可见工具，`ToolExecutor` 独立校验 kind（报告/后台只读，硬编 set_* 也会被拒绝） |
| §6.2 操作确认 | ControlTools 12 个改状态工具 + set_whitelist 改为生成 PENDING 草案（opId/type/params/before/after，5 分钟有效）；返回 executed=false + requiresConfirmation；模型无 confirmed 参数可自授权 |
| §10.2 预算 | `TaskBudget`（模型 ≤6、工具 ≤12、墙钟 ≤180s，单调时钟；CoroutineContext 下传，嵌套报告共享）；超预算返回部分结果并说明；取消用 `ensureActive()` |
| 执行器 | `ActionExecutor` 只执行 CONFIRMED 草案、参数来自草案、执行后重读实际值核对；失败如实返回 |

**真机验证（T10）**：聊天「change my daily target to 90 minutes」→ 只调用 `set_daily_target`，
`pending_action` 新增 `SET_DAILY_TARGET params={"minutes":90} before={"minutes":100} status=PENDING`，
DataStore 目标仍为 100（未生效）✅

## Phase 2：流式输出 + 统一事件 + 页面上下文 + 确认卡片

| 方案条目 | 实现 |
|---|---|
| §8.2 流式 | `LlmClient.completeStream`；OpenAI SSE（`delta.content`、`tool_calls[index]` 分片累积、`[DONE]`）与 Anthropic SSE（text_delta / input_json_delta / message_delta / message_stop / error）；半段 JSON 不执行；断流不重放；仅在无副作用时降级非流式 |
| §8.1 统一事件 | `AgentEvent{TextDelta,ToolStarted,ToolFinished,DataBlock,ActionProposed,Completed,Failed,Cancelled}`；仅前台对话根任务发事件，嵌套/后台任务不发 |
| §7 页面上下文 | `AgentContext`（entryPage/focusPackage/focusAppName/rangeDays/focusDate）提交时快照写入提示词，任务期间不变；UI 顶部 chip「正在分析：全部应用 · 最近 7 天」+ 1/7/30 天切换，发送中置灰并提示「本次任务上下文已固定」 |
| §6.2 UI | 设置页「待确认操作」卡片：类型中文名、`目标 120 分钟 → 100 分钟` 摘要、剩余有效期、「确认并执行 / 取消」；确认 → markConfirmed → ActionExecutor → finish；过期项禁用；空态不渲染 |
| §8.3 卡片 | 助手页消息流内插入 `ActionProposed` 确认卡，`DataBlock` 预留（本阶段未发射） |
| 取消 | `cancelCurrent()` 取消活动任务 Job，OkHttp Call 取消，`ensureActive` 阻止后续调用，TextDelta 检查 Job 存活 |

**真机验证**：

1. **草案→确认→执行**（T10/T11）：
   - 设置页出现「待确认操作 / 每日目标 / 目标 100 → 90 分钟 / 剩余 3 分 37 秒 / 取消 / 确认并执行」
   - 点「确认并执行」→ DataStore `daily_target_minutes = 90`；DB `pending_action.status=EXECUTED, resultJson={"dailyTargetMinutes":90}`
   - 卡片确认后消失；历史记录中另一条 5 分钟未确认草案变为 `EXPIRED`（未执行）
   - 重复确认：`markConfirmed` 对非 PENDING 返回 null，UI 提示「该操作已过期或已处理」，不会二次执行
2. **流式输出**：发送「how many minutes did I use today」→ 截图帧显示工具状态「已完成：使用统计」后正文逐段增长（`今天（11-05）你一共用了 117 分钟…` → 后续段落），最终固定为完整回答 ✅
3. **上下文 chip**：助手页顶部「正在分析：全部应用 · 最近 7 天」，发送后显示「本次任务上下文已固定」✅
4. **草案查询工具**：`get_pending_actions` 已注册（QUERY，27 个工具），聊天中可用于回答「我还有什么待确认的操作」✅
5. **取消**：代码路径完整（Job 取消 + Call 取消 + ensureActive + Cancelled 事件）；**真机两次尝试都因任务在点「停止」前已完成，未能截到 Cancelled 状态**，标记为未完成设备验证 ⚠️

## 未完成 / 后续

| 项 | 说明 |
|---|---|
| §10.2 后台每日 AI 请求 ≤8 次 | 需要跨任务持久计数（work/ 或 data 层），未实现 |
| DataBlock / 证据卡片 | 事件已定义，未发射；图表复用现有组件待 Phase 3 |
| 取消的设备级验证 | 需构造更长任务或加调试延迟 |
| Phase 3 工具选择/覆盖查询/30 天分析 | 部分基础（coverage、scope）已就绪，未做按意图选择与月度结论约束 |
| Phase 4 记忆与反馈、Phase 5 主动洞察 | 未开始 |
| 仪器化测试 / CI | 仅 JVM 单测 15 个 + 手工真机 |

## 产物

```
out/ai_usage_guard/v1.7_phase2/
├── ai-usage-guard-v1.7-phase2-debug.apk   # MD5 e279296c923487fbe3d68d1a57f6eda9
└── shots/   # 待确认卡片、确认执行、流式两帧、上下文 chip、拦截/放行等
```

## 回退

源码回退点 `806e535`（公开仓库基线）；DB 已到 v3，回退旧 APK 前需备份 `usage_guard.db`。
