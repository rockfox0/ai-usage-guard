# AI Digital Wellbeing（AI 使用管家）实现说明

按 `readme.md` 规格实现的 Android MVP，已在 **Pixel 6（Android 13 / API 33）** 真机逐项验证。
规格原文见仓库根目录 `readme.md`；本次实现的新增说明与验收证据在本文与 `../out/ai_usage_guard/`。

## 一、整体架构

```
┌──────────────────────── Android 端（本地确定性能力）────────────────────────┐
│ UsageStatsManager / UsageEvents                                            │
│        ↓  UsageEventSource（系统 API 薄封装）                               │
│ UsageIngester（滚动窗口 + 先删后插，幂等；熄屏/桌面/自身排除）               │
│        ↓                                                                   │
│ Room（8 张表：app_info / usage_session / app_usage_record / behavior_event  │
│        / agent_report / restriction_rule / sync_state / agent_message）     │
│        ↓                                                                   │
│ BehaviorAnalyzer（6 类本地规则检测）→ BehaviorRepository（去重/冷却落库）   │
│        ↓                                                                   │
│ AgentRuntime（LLM + Function Calling）→ ReportRepository（报告 + 本地降级）  │
│        ↓                                                                   │
│ NotificationHelper / WorkManager（主动提醒与定时任务）                      │
└────────────────────────────────────────────────────────────────────────────┘
        ↑ 统一接口：AppRestrictionController（无障碍实现，DeviceOwner 预留）
Compose UI：首页 / 应用 / AI 助手 / 报告 / 设置（+ 应用详情、行为事件两个二级页）
```

设计原则与规格一致：**本地系统负责采集、检测、确定性任务；Agent 只负责理解、分析、建议，不直接执行系统限制。**

## 二、规格条目 → 实现位置

| 规格要求 | 实现 |
|---|---|
| 1. 使用情况监控（UsageStats + UsageEvents、跨天/锁屏/重启/时区） | `data/UsageEventSource.kt`、`data/UsageIngester.kt`、`data/repo/UsageRepository.kt` |
| 2. Room 数据表（AppInfo / UsageSession / AppUsageRecord / BehaviorEvent / UserPreference / AgentReport / RestrictionRule） | `data/db/Entities.kt` + `Daos.kt` + `AppDatabase.kt`（UserPreference 用 DataStore，规格技术栈要求 DataStore 做用户配置） |
| 3. 首页 Dashboard（总时长/目标/Top5/分布/7 天/异常数/AI 入口/Agent 状态/空态错误态） | `ui/dashboard/*` |
| 4. App 使用详情（名称图标/今日/7 天/日均/启动次数/最近使用/分布/区间） | `ui/applist/AppDetailScreen.kt`、`AppDetailViewModel.kt` |
| 5. 本地行为分析引擎（6 类检测、可配置阈值、个人基线、冷却去重） | `domain/behavior/BehaviorAnalyzer.kt` + `data/repo/BehaviorRepository.kt` |
| 6. AI Agent 模块（LLM 客户端、ToolRegistry、ToolExecutor、AgentLoop、Memory、7 工具） | `agent/llm/*`、`agent/tools/*`、`agent/executor/*`、`agent/runtime/*`、`agent/memory/*` |
| 7. 主动管理（WorkManager 定时同步/分析/日报/周报、去重、失败重试） | `work/AgentScheduler.kt` + 4 个 Worker |
| 8. 通知（每日提醒/超目标/异常/AI 报告、分类开关、Android 13 权限、冷却） | `notify/NotificationHelper.kt` |
| UI 七页 | 首页 / 应用 / 应用详情 / AI 助手 / 报告 / 行为事件 / 设置 |
| 限制功能预留（AppRestrictionController + Validator，Agent 不直接执行） | `restriction/AppRestrictionController.kt` + `RuleValidator`；`agent/tools/RestrictionTools.kt` 只产出建议 |
| 隐私（本地优先、只发聚合数据、可关闭 AI、可删除数据） | 设置页「数据与隐私」；Agent 工具只返回聚合值；报告/行为/使用记录均可清空 |

## 三、Agent 工具（Tool Calling 白名单，8 个）

`get_daily_usage`、`get_weekly_usage`、`get_usage_ranking`、`get_app_usage_history`、
`get_behavior_events`、`get_user_preferences`、`generate_usage_report`、`propose_restriction`

- 支持 OpenAI 兼容（`/v1/chat/completions` + `tools/tool_calls`）与 Anthropic 兼容（`/v1/messages` + `tool_use/tool_result`）两种协议；
- 单任务最多 4 轮、12 次工具调用，LLM 60s / 工具 20s 超时，参数经 JSON Schema 校验，未知参数直接回传模型；
- `propose_restriction` 只返回建议（`executed=false`），必须用户确认才写入 `restriction_rule`；
- 无 Key / 网络失败 / HTTP 错误 / 解析失败 → `AgentOutcome(ok=false)`，由 `ReportRepository` 自动降级本地规则报告。

## 四、构建与部署

```bash
cd AI_d
~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

设备初始化（安装 + 使用情况访问 + 无障碍 + 可选 AI 配置）：

```bash
UG_API_KEY='sk-xxx' UG_BASE_URL='http://host:18080' ./scripts/ug_setup_device.sh <序列号>
```

> ⚠️ 重装/更新 APK 后系统会重置无障碍授权，脚本里每次都重新开启；手动路径：设置 → 无障碍 →「AI 使用管家 · 应用暂停守护」。
> 另：`am force-stop` 也会杀掉无障碍服务，需要重新开启（脚本已包含）。

## 五、14 项验收标准（真机实测）

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 可安装 | ✅ | Pixel 6 安装运行，无崩溃日志 |
| 2 | 引导授予 Usage Access | ✅ | 设置/首页权限卡片 → `ACTION_USAGE_ACCESS_SETTINGS` |
| 3 | 读取真实使用时长 | ✅ | Room `usage_session` 91 条、`app_usage_record` 31 条；Chrome 3 分钟 |
| 4 | 今日排行榜 | ✅ | 首页 Top5 + 应用页 127 个应用带今日时长 |
| 5 | 最近 7 天 | ✅ | 回填 10/30–11/05 真实数据（10/31 145 分钟） |
| 6 | 保存历史数据 | ✅ | Room 持久化；杀进程重启后报告/使用记录仍在 |
| 7 | ≥3 类异常检测 | ✅ | 6 类已实现；实测触发 `OVER_TOTAL_TARGET`（把目标设为 1 分钟） |
| 8 | WorkManager 后台分析 | ✅ | JobScheduler 见 4 个周期任务；约束 batteryNotLow |
| 9 | 调用 DeepSeek/兼容 API | ✅ | 自建中转双协议 200（地址已移除）；日报来源 `AI` |
| 10 | Agent 调用 ≥3 个本地工具 | ✅ | 日报实际调用 5 个：daily_usage / ranking / behavior_events / weekly_usage / user_preferences |
| 11 | 真实数据驱动的 AI 日报 | ✅ | 报告内容引用真实数据与工具结果 |
| 12 | 主动发送提醒 | ✅ | 通知渠道 4 个；`NotificationRecord id=9999 channel=ai_report` 已投递 |
| 13 | 断网基础统计可用 | ✅ | 统计纯本地；把 BaseURL 指向不可达地址后仍生成本地规则日报 |
| 14 | 重启后历史/任务恢复 | ✅ | 重启后报告历史、使用记录、WorkManager 任务均在 |

## 六、已知限制

- 时长统计为前后台会话粒度（秒级误差）；跨零点会话按开始时间归属当天。
- 强制暂停依赖无障碍服务存活（系统或用户关闭即失效，App 内会提示）。
- 行为分析的长会话/深夜/频繁打开只对「今天」有精确会话归属，历史日期只做总量类检测。
- 提醒受免打扰时段（默认 23:00–07:00）与各分类开关约束；Android 13 需授予通知权限。
- 明文 HTTP 接口为接内网/自建中转方便（`usesCleartextTraffic=true`），正式发布建议 HTTPS。
- 真机验证时人类同时在操作手机，个别系统 UI 截图（通知栏排序）可能受其它应用通知影响。

---

## 七、v1.2 增补：默认限制模型 + 白名单 + 临时使用限次

| 需求 | 实现 |
|---|---|
| 默认限制所有应用 | `AppSettings.restrictByDefault`（默认 `true`）+ `RestrictionPolicy.isRestricted()`：除白名单与系统关键应用外，其余应用打开即拦 |
| 不限制某些应用（白名单） | `AppSettings.allowedPackages`（DataStore StringSet）；「应用」页开关在默认限制模式下语义变为「允许」；首次升级自动把已安装的微信/QQ/企业微信/钉钉/支付宝/微信输入法加入白名单 |
| 系统关键应用硬保护 | `RuleValidator`（电话/短信/设置/桌面/本应用）+ **输入法**（`InputMethodManager.inputMethodList`），避免默认限制把键盘拦掉 |
| 临时使用限次 | `TempAllowStore`：每个应用每天只能用一次「临时使用 1 分钟」，第二次进入拦截页按钮变为「今日已用过临时使用」且不可点 |
| 关闭默认限制 | 设置 →「限制模式」关闭后回到「只限制手动勾选的应用」 |
| 输出文本优化 | `ui/MarkdownLite.kt`：加粗/标题/列表/代码/表格（窄屏转成 `a · b · c`）；AI 助手与报告页统一使用；系统提示词要求模型不要输出表格等窄屏不友好格式 |
| 上游 thinking 兼容 | 中转在 thinking 模式要求回传历史思考内容时，`AgentLoop` 自动降级为「不带历史重试一次」，聊天不会直接失败 |

真机验证（2026-11-05 03:00–03:21）：
- 应用页显示「默认限制所有应用 · 已允许 4 个」，Chrome 状态「默认受限」，打开即出现拦截遮罩（日志 `blocked=true byDefault=true allowed=4`）
- 微信（白名单）打开不拦截（`blocked=false`）
- 输入法 Gboard 不再被拦（`blocked=false`），AI 助手可正常打字
- Chrome 首次「临时使用 1 分钟」成功；60 秒后再次打开，按钮显示「今日已用过临时使用」
- AI 助手输出：`**`、`-`、`#`、表格均正确渲染（截图 `31_assistant_retry.png`）

---

## 八、v1.3 增补：AI 调控白名单 / 实时提示 / AI 个性化

### 1) AI 可以调控白名单（2 个新工具）

| 工具 | 能力 |
|---|---|
| `get_whitelist()` | 只读：返回白名单数量、包名+应用名、当前是否默认限制 |
| `set_whitelist(package_name, allowed)` | **真正执行**：加入/移出白名单（写 DataStore），返回操作后完整列表；`RuleValidator` 保护的系统关键应用（电话/短信/设置/桌面/本应用/**输入法**）会被拒绝并说明「系统保护应用无需白名单」 |

- 工具描述明确要求「仅在用户明确要求时调用」；实测 AI 会先 `get_whitelist` 再请用户确认，确认后才 `set_whitelist`。
- 输入法保护在 `GuardService` 启动时通过 `RuleValidator.registerProtected(imePackages)` 注册，因此 Agent 侧同样拒绝。
- 工具总数 8 → 10。

### 2) 使用过程中的实时提示（陪伴模式）

- `GuardService` 记录当前前台应用与连续使用时长，每 20s 检查一次；达到设定间隔（5/10/15/30 分钟，可配置）时，
  在屏幕顶部弹出一个**不打断操作**的小卡片（无障碍 overlay，8 秒自动消失），内容形如：
  `AI 陪伴 / 提醒一下：你已经连续用「微信」2 分钟。`
- 文案来源二选一：
  - 本地模板（默认，零成本，`service/LocalHints.kt`，6 条轮换）；
  - AI 生成（设置里开启，调用 `AgentRuntime.quickHint(appName, minutes)`，≤30 字、贴合语气；失败自动回退本地）。
- 切到桌面/系统界面/被限制应用、或锁屏时停止计时，避免误报。

### 3) AI 个性化选项（设置 → AI 个性化 / 实时提示）

| 选项 | 作用 |
|---|---|
| 称呼 | AI 怎么称呼你（实测「老王」生效） |
| 语气 | 温和 / 直接 / 严厉，影响聊天、报告、实时提示的措辞 |
| 关注重点 | 睡眠 / 学习 / 娱乐 / 效率（可多选），写入系统提示词 |
| 自定义要求 | 例如「别用感叹号，多提早点睡」，追加进系统提示词（≤300 字，不能覆盖安全约束） |
| 实时提示开关 / 间隔 / 是否用 AI 生成 | 控制陪伴模式频率与文案来源 |

`AgentLoop` 的 `chatSystemPrompt()` / `reportSystemPrompt()` 统一追加 persona 段；`quickHint` 也使用同一 persona。

---

## 九、v1.4：新机（vivo / Android 16，无 root）适配

- **系统应用保护**：默认限制只作用于第三方应用；`FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP` 的预装/OEM 应用一律不限制，
  并同步到 `RuleValidator`，Agent 的白名单工具也会拒绝系统应用（修复「系统导航被拦」问题）。
- **无 root 部署**：安装、Usage Access（appops）、无障碍（settings put secure）、通知（pm grant）均可由 adb 完成；
  debug 包可用 `run-as` 写入 DataStore 配置。
- **云备份数据**：`allowBackup=true` 会把旧机白名单/临时使用记录带到新机，换机后建议重置配置（后续可加包名有效性过滤）。
- 真机验收见 `../out/ai_usage_guard/report_vivo_v1.4.md`。

---

## 十、v1.5：AI 可完整调控 App（25 个工具）

- 新增 `agent/tools/ControlTools.kt`（15 个工具）：`get_app_settings`、`set_daily_target`、`set_quiet_hours`、
  `set_notification_enabled`、`set_notification_channels`、`set_reminder_style`、`set_theme_mode`、`set_restrict_mode`、
  `set_nudge`、`set_ai_persona`、`set_app_restriction`（真正 block/unblock）、`set_ai_analysis_enabled`、
  `refresh_usage_data`、`generate_report_now`、`send_test_notification`。
- `AgentLoop` 注入 `ReportRepository`（`AppWiring` 同步传入 `Container.reports`），工具超时 20s → 120s（报告生成会嵌套一次 Agent 任务）。
- 统一返回结构 `{executed, changed:{from,to}, message, settingsUrlHint}`；修改类描述均注明「仅在用户明确要求时调用」。
- 安全边界：系统保护应用拒绝限制；报告生成过程中禁止调用控制类工具；`generate_report_now` 有递归护栏。
- 真机验收：见 `../out/ai_usage_guard/report_v1.5_ai_control.md`（AI 在对话中完成改目标/主题/通知/限制应用/发通知/生成周报/批量恢复）。

---

## 十一、v1.6：使用时长+时段评分、个性化词条、每日/每周总结

- **打分引擎** `domain/score/UsageScoreEngine.kt`：100 分起，按「超目标比例（≤45）／深夜使用（≤20）／超长连续（≤15）／高频解锁（≤12）」扣分，
  克制与无深夜作息加 3 分；等级 `优秀/良好/一般/偏多/超负荷`。
- **时段判断词条**：5 个时段（清晨/上午/下午/晚上/深夜），词条由「时长档位(6) × 深夜档位(4) × 语气 × 称呼」生成，
  headline（如「有点舍不得放下 · 熬夜偏多」）+ verdict（时长句 + 时段句 + 深夜句 + 语气化建议）。
- **数据**：新增 `daily_score` 表（Room v1→v2 迁移，历史数据保留）、`ScoreRepository`（todayScore/scoreFor/week/persist）、`Container.score`。
- **展示**：首页「今日评分」卡、报告页「今日评分」+「本周总结」卡；日报/周报文本最前面加【今日评分】【本周总结】；通知首行带分数。
- **Agent**：新增 `get_usage_score(scope=today|week, date?)` 工具（工具 25→26）；CHAT_SYSTEM 要求问分数时先调用；
  REPORT_SYSTEM 要求日报/周报先调用并把分数与词条写到开头。
- 真机验收见 `../out/ai_usage_guard/report_v1.6_score.md`。

---

## 十二、v1.7 Phase 0：数据与限制状态正确性（按升级方案 §4）

| 方案条目 | 实现 |
|---|---|
| 4.1 会话与每日统计 | `domain/aggregate/DailyAggregator.kt` 对真实会话做按日重叠切片；跨午夜两天各计实际时长、启动次数归属开始日；`UsageIngester` 窗口前移 6h 重叠回看 + 重算受影响日期 |
| 4.2 数据覆盖 | `DataCoverage`/`DataStatus` + `UsageRepository.coverage()`（requested/available/valid/missing/lastSyncedAt/timezone） |
| 4.3 生效策略 | `restriction/EffectiveRestriction.kt` + `RestrictionPolicy.effective()`，UI/Agent/GuardService 共用 |
| 4.4 评分可信度 | `UsageScore.dataStatus/scoreAvailable`；周平均只统计有效天；`daily_score` 增加 `algorithmVersion`/`dataStatus` |
| 4.6 对话历史 | `AgentMessageDao.recentHistory()` + `AgentMemory` 改为「最近 N 条再正序」 |
| Phase 1 基础设施 | Room v3 `pending_action` 表 + `ActionDraftRepository`（草案/确认/幂等/过期）+ `ActionExecutor`（确认后执行并核对实际值） |
| 可复现构建/测试 | `gradlew`（8.13）+ 11 个 JVM 单元测试（跨午夜切片、有效策略） |

真机（Pixel 6 / Android 13）：v1.6→v1.7 迁移保留 102 会话/28 记录、DB v3、无崩溃；
聚合与独立重算 0 偏差；白名单加入→`blocked=false`、移出→`blocked=true`（T05）。
报告见 `../out/ai_usage_guard/report_v1.7_phase0.md`。


---

## 十三、v1.7 Phase 1 + Phase 2

### Phase 1（权限与确认）
- `TaskScope{CHAT,REPORT,BACKGROUND,INSIGHT}` + `ToolKind{QUERY,PROPOSE,CONTROL}`；模型可见工具与执行器允许集合共用 `ToolScopePolicy`（报告/后台/洞察只读）。
- ControlTools 12 个改状态工具 + set_whitelist 改为 `ActionDraftRepository.propose(...)` 生成 PENDING 草案（opId、before/after、5 分钟 TTL），不再直接改设置。
- `TaskBudget`：模型 ≤6、工具 ≤12、墙钟 ≤180s（单调时钟，CoroutineContext 下传、嵌套共享）；超预算返回部分结果；取消用 `ensureActive()`。
- `ActionExecutor` 只执行 CONFIRMED 草案，参数来自草案，执行后重读实际值核对。

### Phase 2（交互升级）
- 流式：`LlmClient.completeStream`，OpenAI/Anthropic SSE 分别实现，工具参数按 index/id 累积，半段 JSON 不执行、断流不重放、仅无副作用时降级非流式。
- 统一事件：`AgentEvent{TextDelta,ToolStarted,ToolFinished,DataBlock,ActionProposed,Completed,Failed,Cancelled}`，仅前台对话根任务发射。
- 上下文：`AgentContext` 提交时快照注入提示词；助手页 chip「正在分析：… · 最近 N 天」可切换、发送中固定。
- 确认 UI：设置页「待确认操作」卡片（类型/目标/`before→after`/有效期/确认/取消），助手页消息流内插入 `ActionProposed` 卡片。
- 新工具 `get_pending_actions`（QUERY，工具 27 个）。

真机（Pixel 6）：草案生成不生效 → UI 确认后 DataStore 生效（90 分钟）、DB EXECUTED、过期草案 EXPIRED、重复确认不再执行；流式正文逐段增长；上下文 chip 正常。
报告见 `../out/ai_usage_guard/report_v1.7_phase1_2.md`。


---

## 十四、v1.7 真机回归（vivo）与 null 显示修复

- 缺陷：流式解析把 JSON 的 `null` 当正文。OpenAI SSE 工具调用分片的 `delta.content` 是 JSON null，
  `optString` 返回字符串 "null"，被当作 TextDelta 拼进回复并落库，界面出现 nullnull…。
- 修复：两个客户端的流式/非流式正文与工具名/参数均改为「显式判断是 String 才使用」；
  `AgentMemory.sanitizeContent()` 清理历史脏数据前缀，并用于 LLM 回放。
- 真机（vivo V2527A / Android 16）：修复后新消息无 null、历史消息显示已清理；DB v2→v3 迁移数据完整；
  草案（360→300）生成后由用户在应用内确认并执行，DataStore 生效；第三方应用拦截、系统应用放行正常。

---

## 十五、v1.8：全局每日额度 + 单应用每日限额

- `domain/quota/QuotaPolicy`：系统保护 > 单应用限额 > 白名单 > 全局额度（9 个单测）。
- `app_daily_limit` 表（DB v4）+ `QuotaRepository`（limits/limitOf/setLimit/todayUsage/status）。
- `GuardService`：实时计账（DB 基线 + 前台累计 + 跨日重置），窗口切换与 20s 巡检双通道拦截；浮层区分「今日额度已用完」/「今日用量已超限」。
- 设置：`dailyBudgetEnforced`（超出每日目标后拦截非白名单应用）。
- UI：App 详情「每日限额」卡、列表限额徽章、设置「每日使用额度」卡、首页额度剩余。
- AI：`set_app_daily_limit` / `set_daily_budget_enforced`（草案确认）、`get_quota_status`（只读）；工具 30 个。
- 真机（vivo）：全局额度用尽拦截 ✅、白名单应用被单应用限额拦截 ✅、UI 读写与 DB 迁移 ✅（26 单测通过）。

---

## 十六、v1.8.1：限制模式三档（额度内全放行）

- 新增 `restriction/RestrictionMode`：`QUOTA_AFTER_BUDGET`（默认）/ `ALWAYS_NON_WHITELIST` / `MANUAL_ONLY`；
  `SettingsRepository.restrictionMode` + `setRestrictionMode()`（同步旧布尔键，旧布尔值迁移为 ALWAYS/MANUAL）。
- `GuardService`：QUOTA 模式下额度内只按「手动限制」判断，超额后由 `QuotaPolicy` 拦非白名单；另外两种模式保持原语义。
- 单应用每日限额在所有模式下独立生效，且优先于白名单；系统保护应用永不受限。
- UI：设置页「限制模式」三选一；应用列表/首页文案随模式变化（「额度内自由使用 · 超额后仅白名单可用（今日剩余 X 分钟）」）。
- AI：新工具 `set_restriction_mode`（草案），`get_app_settings`/`get_quota_status` 暴露当前模式。
- 真机（vivo）：额度内非白名单应用可打开；超额后被拦截并提示；超额时白名单应用仍可用；26 单测通过。

---

## 十七、v1.8.2：30 天数据、更宽上下文、白名单每日上限、专注时段

- **30 天**：首次回填 7→30 天、单次窗口 8→31 天；`UsageRepository.dailyTotals/ForLastDays/rankingForRange`；工具 `get_usage_trend(1..30)` 与 `get_app_usage_history`(≤30)。
- **上下文**：历史回放 12→24 条并逐条截断（用户 600/助手 1200 字）；系统提示词加「本地数据快照」（今日/7 天/30 天日均、Top3 应用、白名单额度），标注仅作概览、数值须调工具；22 条工具描述精简（最长 198→81 字）。
- **白名单每日上限**：`WhitelistRepository`（默认每天 1 个，跨天重置，移出不计次），所有写入口（UI、ActionExecutor、AI 草案）统一走它；`set_whitelist_daily_limit` 草案工具。
- **专注时段**：`FocusWindow`（支持跨午夜）+ `QuotaReason.FOCUS_WINDOW`，GuardService 实时拦截并显示专用浮层；`set_focus_window` 草案工具；设置/列表/详情/首页均有状态展示。
- 真机（vivo）：专注时段拦截与白名单放行、白名单第 2 个添加被拒（开关回滚）、34 单测通过。

---

## 十八、v1.8.3：守护保活（被清后台后自动恢复）

- `KeepAliveService`：前台常驻通知（`guard_alive`）+ `START_STICKY`，每 60 秒检查无障碍开关。
- `AccessibilityRestorer`：检测/写回 `enabled_accessibility_services` 与 `accessibility_enabled`（需一次性 adb 授予 `WRITE_SECURE_SETTINGS`）；失败时通知用户手动开启（30 分钟节流）。
- `GuardWatchdogWorker`：WorkManager 每 15 分钟兜底；`BootReceiver`：开机/更新后恢复并拉起服务。
- `KeepAliveGuide`：忽略电池优化申请、vivo/小米/OPPO/华为 自启动页深链、应用详情、无障碍设置、adb 授权命令。
- 设置页「守护保活」卡：三行状态 + 四个操作按钮 + 命令复制；无障碍关闭时红字提示。
- 真机（vivo）：OEM 关闭无障碍后约 20 秒自动恢复；`am kill` 后进程与服务存活；Doze 白名单已加入。force-stop 无法自恢复（Android 限制），已如实说明。

---

## 十九、v1.9 Phase 1：动态能力发现（Capability Discovery）

- 新增 `agent/capability/`：
  - `CapabilityDescriptor`（id/名称/描述/分类/工具/示例/关键词）
  - `CapabilityCatalog`：9 个能力覆盖 34 个业务工具（工具→能力唯一映射，单测保证无遗漏/无重复）
  - `CapabilityDiscovery`：本地快速路由（关键词 + 页面上下文，命中不了回退常用能力），以及模型侧 discover 搜索
- 新增元工具 `discover_capabilities` / `load_capability`（QUERY）：
  - 模型可搜索能力、请求加载；`load_capability` 返回 `{loadedCapability}` 标记，AgentLoop 在**下一轮**扩展可见工具集
- `ToolRegistry.specsForCapabilities(caps, scope, metaTools)`：按「已加载能力 + 元工具」下发 schema，仍受 TaskScope 过滤
- `AgentLoop`：CHAT 域动态工具集（初始 = 路由命中能力 + 2 个元工具），加载后重算；日志记录 `task tools: caps=... tools=x/36`
- **执行器能力门禁**：`ToolExecutor.execute(allowedToolNames)` —— 未加载能力的工具即使已注册也拒绝执行
  （设备实测发现模型会凭历史工具名直接调用，仅靠"不下发 schema"不足以防住）
- 提示词：CHAT_SYSTEM 新增规则 11（能力发现流程、不可臆造工具、先 discover/load 再调）
- 测试：新增 `CapabilityCatalogTest`（4）、`CapabilityDiscoveryTest`（10），总计 **48 个单测全部通过**

真机（vivo，versionCode 22）：
- 「how many minutes today」→ `caps=[usage_analysis] tools=8/36`，模型仅调用 `get_daily_usage` + `get_usage_ranking`，回答「今天 19 分钟，剩余 341 分钟」
- 能力目录、路由、权限过滤、门禁均有单测/代码路径覆盖；APK MD5 `9d5ba7d9122f952d27961ca4e01bb987`

---

## 二十、v1.9 Phase 2：动态任务规划 + 结果评估

- 新增 `agent/plan/`：
  - `TaskMode` + `TaskModeClassifier`：本地零 token 判定 DIRECT / ANALYSIS / GOAL（简单问题保持直答，不额外消耗模型请求）
  - `TaskPlan` / `TaskStep` / `StepStatus`：目标、步骤（能力、是否需要数据）、完成条件、是否需要确认
  - `TaskPlanner`：一次模型请求产出 JSON 计划；`parse` 为纯函数（去围栏、≤5 步、能力 id 必须存在、空计划/非法 JSON 返回 null → 回退 DIRECT），`directive` 把计划注入主循环
  - `ResultEvaluator`：本地规则评估（空回答 / 分析任务没调数据工具 / 结论无数字 / 30 天请求但有效数据不足），返回 OK / NEED_MORE（最多补一轮）/ INSUFFICIENT_DATA（追加"数据不完整"说明）
- `AgentLoop` 集成：
  - CHAT 域先按模式判定；ANALYSIS/GOAL 先规划（消耗 1 次模型请求），计划能力一次性预加载后再进入工具循环
  - 记录每次工具输出；模型想结束时先评估，NEED_MORE 时注入「结果评估」指令并补一轮（每次任务最多一次）
  - 任务结束对 30 天/月维度请求检查数据覆盖，不足则追加说明（不补零、不假装完整）
  - 预算仍为 6 请求 / 12 工具 / 180s，规划与补轮都计入
- 测试：新增 `TaskModeClassifierTest`(5)、`TaskPlannerTest`(6)、`ResultEvaluatorTest`(7)，总计 **66 个单测全部通过**
  （为在 JVM 单测里解析 JSON，新增 test 依赖 `org.json:json`）

真机：APK 已安装（versionCode 23）。设备当时锁屏/被占用，Phase 2 的规划/评估链路真机回归未完成，留待下轮设备空闲时补测（Case 2/4）。MD5 `bd98aac80109390a44a6fac7786847f7`

---

## 二十一、v1.9 Phase 3：上下文工程 + 长期记忆

### 上下文工程
- `agent/context/ContextEngine.kt`：`conversationSummary(sessionId)` 取最近 12 条之外的更早消息，
  用 `ConversationCompressor`（纯函数）压缩成「更早对话摘要」注入系统提示词（不额外消耗模型请求）；
  压缩规则：只取用户消息、去重、单条截断 40 字、最多 6 条、总长 ≤500 字。
- 系统提示词顺序：基础规则 → 本地数据快照 → 页面上下文 → persona → **长期记忆** → **更早对话摘要**；
  日志 `prompt memory: N chars` / `prompt conversation summary: N chars` 可观测。

### 长期记忆（DB v5）
- 新增表：
  - `agent_memory(kind, memKey, valueJson, confidence, updatedAt)`：kind=profile（偏好）/ decision（建议记录）
  - `agent_goal(id, goal, targetPeriod, status, createdAt, updatedAt)`：长期目标
  - `agent_feedback(id, targetType, targetId, signal, reason, createdAt)`：REJECT / PREFER / ACCEPT
- `data/repo/MemoryRepository.kt`：画像、目标、反馈、决策的读写；目标按文本去重（同目标更新而不是新增）。
- `agent/memory/MemoryEngine.kt`：
  - `promptSection()` 渲染记忆摘要（偏好 / 进行中目标 / 被拒建议 / 表达过的偏好 + "不要原样重复被拒方案"）；
  - `recordFeedbackFromInput()` 用 `FeedbackDetector`（纯本地规则，中英文）自动记录自然语言反馈；
  - `recordDecision()` 把生成的待确认草案记入决策记忆；`wasRejectedBefore()` 关键词重合检索。
- `agent/memory/FeedbackDetector.kt`：太严格/别提醒/别推荐/温和/渐进/关注睡眠… → REJECT / PREFER。
- 新增工具（5 个，归入能力 `memory_management`）：`save_memory`、`list_memory`、`save_goal`、`list_goals`、`record_feedback`；
  它们只改 Agent 自己的记忆（不涉及系统限制/用户配置），因此为 CONTROL 直接执行。
- `AgentLoop`：chat 入口自动识别反馈；系统提示词注入记忆摘要与对话摘要；PROPOSE 草案成功后记入 DecisionMemory。
- `AssistantViewModel.cancelAction`：用户取消草案 → 写入 REJECT 反馈（以后避免原样重复该建议）。
- 能力目录从 9 → 10 个能力、工具 36 → 41 个（含 5 个记忆工具），目录单测同步更新。

### 测试
- 新增 `FeedbackDetectorTest`(4)、`MemoryPromptTest`(3)、`ConversationCompressorTest`(4)
- **总计 77 个单测全部通过**；APK 已构建并安装（versionCode 26，MD5 `38c5b0df7e1357c6d6b603fe90a36e41`）
- 真机验证（vivo，versionCode 26）：
  - 反馈识别：发送 "too strict, do not remind me" → `agent_feedback` 写入 `REJECT`（`reason` 为原话）✅
  - 记忆写入：发送 "remember I prefer gentle reminders" → 模型调用 `save_memory` + `record_feedback`，
    写入 `agent_memory(kind=profile, memKey=reminder_style)` 与 `PREFER` 反馈；回答明确引用偏好 ✅
  - 记忆影响行为：后续复杂分析回答出现「按你的偏好，我就不再劝你设限额了」✅
  - 计划落库（本版新增 `kind=plan`）：`agent_memory(plan)` 记录 mode=ANALYSIS、3 步、能力与 4 条完成条件 ✅
  - 测试数据已清理（删除测试对话/假反馈/测试记忆，保留用户真实历史 49 条）

---

## 二十二、v1.9 Phase 4：结构化响应

- 新增 `agent/response/`：
  - `ResponseBlocks.kt`：`AgentResponse(title, blocks)` + 白名单 block：
    `Text / StatisticCard / InsightCard / RecommendationCard / ComparisonBlock / ChartBlock / FollowUpAction`；
    `ChartBlock` 只允许 `daily_usage_7d`（数据由本地聚合填充，模型不能填数）。
  - `ResponseParser.kt`（纯函数）：从模型输出里找最后一个 ```json 代码块 → 校验类型/字段/数量（≤6 blocks、统计项 ≤6、对比行 ≤6）；
    任何一个非法 block → 整体放弃、保留 Markdown 原文（不会出现"半卡片"）。
- `AgentEvent.StructuredResponse(title, blocks)`：AgentLoop 在收尾时解析并 emit，同时把 JSON 块从正文剥离
  （落库的对话内容不含 ```json，历史回放不会污染上下文）。
- 提示词：CHAT_SYSTEM 规则 12 —— 有多个关键数字/对比/建议/后续操作时才追加 JSON 块；数字必须来自工具；
  `requires_confirmation` 只表示"需用户确认"，不得声称已执行。
- UI：
  - `ui/assistant/StructuredResponseView.kt`：Compose 渲染统计卡、洞察卡（severity 配色）、建议卡（确认提示）、
    对比表、本地 7 天柱状图、FollowUp chip（点击填入输入框继续追问）。
  - `AssistantViewModel`：新增 `structuredTitle/structuredBlocks/chartDaily`，收到事件后渲染；
    图表数据由本地 `usage.dailyTotalsForLastDays(7)` 填充；发送新消息/清空对话时重置。
- 测试：新增 `ResponseParserTest`(9)，**总计 86 个单测全部通过**。
- APK：versionCode 27，MD5 `f34600a9fac8864b96434113eefab410`。
- 真机：本轮设备断开（vivo 拔线、Pixel 未装本 App），未安装/未回归；下轮设备恢复后验证
  （验证点：结构化卡片出现、正文无 JSON、图表来自本地数据、FollowUp 可点击）。

---

## 二十三、v1.9 Phase 5：用户自定义监控

目标（规格 §10）：用户用自然语言定义监控，AI 只负责把话变成规则并解释，**本地引擎定时判断，不反复调用模型**。

### 领域与存储
- `domain/monitor/MonitorDefinition.kt`：指标 `FOREGROUND_DURATION`（分钟）/ `LAUNCH_COUNT`（次）、
  比较 `GT/GE/LT`、周期 `EVERY_DAY/WEEKDAYS/WEEKENDS`、动作 `NOTIFY`；`packageName=null` 表示全部应用（仅时长）。
- `domain/monitor/MonitorValidator.kt`（纯函数）：名称/阈值范围/全部应用限制/同应用同指标去重/最多 5 条；`describe()` 生成中文描述。
- DB v6：`monitor_rule`（规则）+ `monitor_trigger`（触发历史，按「规则+日期」去重）。
- `data/repo/MonitorRepository.kt`：规则 CRUD、启用开关、触发去重查询与记录。

### 执行
- `domain/monitor/MonitorEngine.kt`：读取已落库数据（今日总时长 / 应用排行 / 启动次数），按周期判断阈值，
  触发时写 `monitor_trigger` 并返回结果；同规则同一天最多一次。
- `work/MonitorCheckWorker.kt` + `AgentScheduler`：每 15 分钟检查一次（KEEP），不调模型。
- `notify/NotificationHelper`：新增 `monitor_alert` 渠道与 `notifyMonitorTriggers()`（单条通知汇总最多 3 条触发）。

### AI 接入
- `agent/tools/MonitorTools.kt`（4 个工具，归入能力 `monitor_management`）：
  `create_monitor`（PROPOSE 草案）、`list_monitors`、`remove_monitor`（PROPOSE）、`monitor_history`。
- `ActionExecutor` 新增 `CREATE_MONITOR` / `DELETE_MONITOR`：确认后落库/删除；创建时再次走校验。
- `PendingActionUi` 增加「自定义监控 / 删除监控」类型名与摘要。
- 提示词规则 13：监控类需求走 create_monitor；不要用限额/专注时段冒充监控（那是拦截，监控只通知）。

### 设置页「我的监控」卡（UI）
- 「守护保活」之后新增卡片：顶部说明「监控由本地引擎每 15 分钟检查一次，不调用模型」；
  空态提示用 AI 创建；每条规则显示名称 + 中文描述 + 启用 Switch + 删除按钮；
  底部显示最近 3 条触发历史（`• 11-06 规则名 183 分钟 · 阈值 180`）；
  进入页面/从后台返回刷新（`LaunchedEffect` + `LifecycleEventEffect(ON_RESUME)`），全部 runCatching 兜底。

### 测试
- 新增 `MonitorValidatorTest`(8)，**总计 94 个单测全部通过**；能力目录 11 个能力 / 45 个工具；APK versionCode 29，MD5 `50318e669760e073b71a65a75f6f6509`。
- 真机：设备仍未连接（vivo 未插回），Phase 4/5 真机回归待设备恢复。

---

## 二十四、v1.9 Phase 6：主动 Agent + Feature Advisor

### 主动洞察（ProactiveAgent）
- `domain/insight/InsightDetector.kt`（纯函数）：本地先筛候选——
  总时长周环比 +25% 且日均多 30 分钟；单应用 +50% 且基数 ≥30 分钟；应用间时间转移（一降一升 ≥30%、总量变化 <10%）；今日超目标 20%。
- `agent/proactive/ProactiveAgent.kt`：
  - 每天最多 1 条（按日期去重），关「异常使用提醒」开关即停用；
  - 仅当存在候选时调用 1 次模型（无工具、45s 超时），拿 `{title, detail, suggestion}`；
  - 解析失败/模型不可用 → 退化成本地事实提醒（不编造、不静默丢弃）；
  - 提示词明确「不做医学/心理诊断、不贴成瘾标签、不重复被拒方案」；带长期记忆（偏好/目标）。
- DB v7：`proactive_insight`（date/kind/title/detail/suggestion/read）+ `InsightRepository`。
- `AnomalyCheckWorker`（60 分钟）在本地行为分析后调用 `maybeGenerate()`，有洞察则通知
  （新渠道 `proactive_insight`，低打扰）。

### 功能提案（FeatureAdvisor）
- `agent/feature/FeatureAdvisor.kt`：`FeatureProposal`（问题/收益/所需指标/所需能力/复杂度/优先级）+ 校验
  （能力 id 必须存在、指标必填、复杂度 P1-P3、优先级 HIGH/MEDIUM/LOW）+ JSON 往返。
- 工具（归入能力 `proactive_insight`）：`get_proactive_insights`、`propose_feature`、`list_feature_proposals`；
  提案存 `agent_memory(kind=feature)`。
- 提示词规则 14：主动洞察与功能提案的触发条件；要求基于现有能力与真实数据；明确「上线新功能仍需版本更新，不能改自身 APK」。

### 测试与产物
- 新增 `InsightDetectorTest`(7)、`ProactiveParserTest`(4)、`FeatureAdvisorTest`(6)；
  **总计 111 个单测全部通过**；能力 12 个 / 工具 48 个；APK versionCode 30，MD5 `c48f75c39d73eaa021e39933143e4c52`。
- 已知缺口：洞察列表暂无独立 UI（先通过通知 + 助手工具查看）；主动分析复用「异常提醒」开关（未新增独立偏好项）。
- 真机：设备仍未连接，Phase 4/5/6 真机回归待设备恢复。

---

## 二十五、v1.9 Phase 7：模型路由 + 只读专家子 Agent

### 模型路由（ModelRouter）
- 新增设置项 `ai_planner_model`（`AppSettings.aiPlannerModel`，空 = 与 `aiModel` 相同）：
  - 直答/简单工具调用：`aiModel`（快、便宜）；
  - 规划（TaskPlanner）、只读子 Agent、日报/周报：`aiPlannerModel`。
- `LlmClientFactory.create(settings, modelOverride)` 支持按请求覆盖模型；
  `ModelRouter.modelFor(route, settings)` 统一决策，日志/界面可用 `describe()`。

### 只读专家子 Agent（SubAgentManager）
- `agent/multi/SubAgentPolicy`（纯函数）：仅 `ANALYSIS` + 剩余模型请求 ≥5 且问题复杂（30 天/一个月/全面/多维度/习惯/趋势…）时启用；
  简单问题、目标型任务、预算紧张一律不启用（避免"每个问题都开多 Agent"）。
- `agent/multi/SubAgentManager`：两个并行专家（使用分析师 / 行为分析师），
  - 本地先整理紧凑快照（7/30 天日均、Top 应用、近期行为事件），再各 1 次模型请求（30s 超时、无工具、无副作用）；
  - 结果作为「专家分析摘要」注入主 Agent 上下文，由主 Agent 统一给最终结论；
  - 失败/超时/未配置静默跳过；预算按 2 次请求预扣，跑完跳过规划把额度留给主循环。
- `SubAgentSnapshots`（纯函数）：快照格式化，单测覆盖。

### 测试与产物
- 新增 `ModelRouterTest`(4)、`SubAgentPolicyTest`(5)、`SubAgentSnapshotsTest`(4)；
  **总计 124 个单测全部通过**；APK versionCode 31，MD5 `2ce17a06b51e662aecc6140ac4a1180c`。
- 真机：设备仍未连接，Phase 4/5/6/7 真机回归待设备恢复。

---

## 二十六、真机验证记录（Pixel 6 / Android 13，v33）

设备：Pixel 6（oriole，Android 13），versionCode 33，`adb` 可连但锁屏（无法解锁 UI）。

已完成：
- 安装 + 权限：`WRITE_SECURE_SETTINGS`、`POST_NOTIFICATIONS`、`GET_USAGE_STATS`（appops）、无障碍绑定成功；
- 保活：关闭无障碍（`enabled=0`）后 **约 20 秒自动恢复**（`enabled=1` 且服务重新绑定）；
- 常驻通知：`guard_alive` 渠道存在；
- 监控引擎（Phase 5）：注入「全部应用 1 分钟」规则 → 调试广播触发
  `MonitorEngine: monitor triggered ... value=47 threshold=1`，写入 `monitor_trigger`，发出 `monitor_alert` 通知；
- 清理了测试规则/触发记录，避免留下假数据。

新增调试入口（**仅 debuggable 构建生效**，release 直接 return）：
- `debug/DebugReceiver` + manifest 声明；
- `adb shell am broadcast -n com.aid.usageguard/.debug.DebugReceiver -a com.aid.usageguard.DEBUG_RUN_MONITORS`
  （另有 `DEBUG_RUN_SYNC` / `DEBUG_RUN_PROACTIVE`）；
- 用于真机验收绕过 15/60 分钟周期等待。

未完成（需要解锁设备 + 配置 AI Key）：设置页两张卡片截图、结构化回复、监控创建 UI、Phase 2/3 对话回归。

---

## 二十七、v1.9 收尾：主动分析独立开关 + 洞察/模型路由 UI（v34）

### 数据层
- `AppSettings.notifyProactive`（DataStore `notify_proactive`，默认 true）+ `setNotifyProactive()`：
  **主动分析独立开关**（与「异常提醒」解耦，用户可只关主动洞察）。
- `ProactiveAgent` 与 `NotificationHelper.notifyProactiveInsight` 改用该开关。
- `set_notification_channels` 工具与 `ActionExecutor` 增加 `proactive` 渠道；`PendingActionUi` 同步「主动洞察」标签。
- `SettingsRepository.setAiPlannerModel()` 补齐（规划模型可持久化）。

### UI（设置页）
- **「AI 洞察」卡**（我的监控之后）：主动分析 Switch、说明「每天最多 1 条 / 关闭后不再分析」、
  最近 5 条洞察（标题+日期+detail+建议）、空态文案；进入/返回自动刷新。
- **「模型路由（可选）」卡**（AI 接口之后）：规划模型输入框（留空=与默认模型相同）、保存提示、说明
  「用于规划/复杂分析/报告；日常问答仍用默认模型」。
- 设置页顺序：权限状态 → 守护保活 → 我的监控 → AI 洞察 → 限制模式… → AI 接口 → 模型路由 → AI 个性化。

### 验证
- 编译通过；**125 单测全部通过**；APK versionCode 34，MD5 `dd559038a22905503ecb6ae6eb6d237a`，已安装到 Pixel 6（锁屏状态，UI 截图待解锁）。

---

## 二十八、v1.9.1：修复「手动限制列表」破坏限制模式语义

**问题（用户真机报告）**：在「超额后才限制」模式下、今日额度未超（78/360 分钟），哔哩哔哩仍被强制暂停浮层拦截。

**原因**：
- `restriction_rule` 是旧版「手动限制列表」（`packageName / dailyLimitMinutes / blockedStartTime / blockedEndTime / enabled`），
  由应用页开关或历史 AI 草案写入；
- `GuardService` 把 `explicitBlocked` **无条件**传给 `RestrictionPolicy`，因此只要表里有该应用且 enabled，
  就与额度无关地拦截 —— 与 `RestrictionMode` 注释（「额度内所有应用都能用」）自相矛盾；
- 真机上 `tv.danmaku.bili` 正好有一条 enabled 的手动限制行，导致用户“没超额也被拦”。
  同时设置里还存在 `restrict_by_default=true` 与 `restriction_mode=QUOTA_AFTER_BUDGET` 的历史矛盾状态，会被 UI/AI 工具误读。

**修复**：
1. `RestrictionPolicy.manualBlocksFor(mode, explicitBlocked)`：**手动名单只在 `MANUAL_ONLY` 生效**；
   `QUOTA_AFTER_BUDGET`/`ALWAYS_NON_WHITELIST` 下手动名单不参与判断。`GuardService` 改用该函数并修正注释。
2. `RestrictionMode.legacyFlag(mode)`：模式成为唯一真源，`restrictByDefault` 由 `restriction_mode` 派生，
   消除历史矛盾状态（避免 UI/AI 说“默认限制中”但实际是超额模式）。
3. 新增 `RestrictionModeGateTest`（6 条）：额度模式忽略手动名单、只限制模式保留手动名单、白名单全模式豁免等；
   **142 个单测全部通过**。
4. 真机数据处理：移除 `tv.danmaku.bili` 的手动限制行（保留用户的抖音/王者行，但在额度模式下不再生效）。

**真机验证（vivo v39）**：
- 额度内：哔哩哔哩正常打开（前台 `UnitedBizDetailsActivity`，无守护浮层）；
- 把每日目标临时设为 1 分钟后：哔哩哔哩被拦截（额度逻辑未被破坏）；恢复 360 分钟后正常打开。

---

## 二十九、v1.9.2：AI 写操作权限（每次确认 / 自动执行，用户可选）

**需求**：用户希望 AI 的写操作可以**自动执行**（不必每次点确认），但由用户自己选择。

### 实现
- 设置项 `auto_execute_writes`（`AppSettings.autoExecuteWrites`，默认 false）：
  - false = **每次确认**（原行为：写操作只生成草案，用户点「确认并执行」）；
  - true = **自动执行**（草案生成后立即 `markConfirmed` + `ActionExecutor.execute`）。
- `agent/action/AutoExecutePolicy.kt`：
  - `isWriteTool(kind)`（PROPOSE/CONTROL）、`shouldAutoExecute(...)`；
  - `rewriteExecuted(originalJson, success, message)`：把工具输出改写成 `executed=true/false + autoExecuted + requiresConfirmation=false`，
    模型据此直接汇报「已生效」，不再提示用户确认；
  - 两种模式的系统提示词（`AUTO_EXECUTE_HINT` / `CONFIRM_REQUIRED_HINT`）。
- `AgentLoop`：`ToolKind.PROPOSE` 成功后、且自动执行开启时，取 `opId` → `markConfirmed` → `execute` → `finish` →
  改写工具输出喂回模型；失败保留原因（如系统保护应用、白名单每日上限）。
- 新增 AI 工具 `set_ai_auto_execute(enabled)`（PROPOSE→`SET_AI_AUTO_EXECUTE`），支持对话切换；
  `ActionExecutor` 新增分支、`PendingActionUi` 增加「AI 操作权限」标签。
- 安全边界不变：自动执行仍走同一 `ActionExecutor`（系统保护应用、白名单每日添加上限、参数校验、防重复执行都保留）。

### UI
- 设置页「AI 操作权限」卡（模型路由之后）：两个单选行（每次确认（推荐）/ 自动执行）+ 自动执行时红色警示 +
  「当前：…」+ 提示可直接对 AI 说切换；`autoExecuteWrites` 来自设置 StateFlow，对话切换后实时跟随。

### 验证
- 单测：新增 `AutoExecutePolicyTest`(7 条) → **149 个单测全部通过**。
- 真机（vivo v41）：
  - 设置页卡片出现；点「自动执行」→ `auto_execute_writes=true`；点「每次确认」→ false（均已持久化）；
  - 开启自动执行后对 AI 说「set my daily target to 350 minutes」→ **无需确认**：`pending_action SET_DAILY_TARGET=EXECUTED`（360→350），
    `daily_target_minutes=350`，回复「已把每日目标改为 350 分钟（原为 360）。这次是自动执行，已经生效」；
  - 测试后已恢复 `daily_target_minutes=360`、`auto_execute_writes=false`（默认每次确认）。

---

## 三十、v1.9.3：Bug 修复（真机排查）

用户报告「似乎还有 bug」，逐项排查后确认并修复 2 个真实缺陷：

### Bug 1：轮次/预算耗尽时把工具 JSON 原样喷进对话
- 现象：复杂任务（如建监控）达到 4 轮上限时，回答里出现
  `（已达到本次任务的最大推理轮次 4 轮…）已调用工具：… {"executed":false,"requiresConfirmation":true,…}` 原始 JSON。
- 原因：`fallbackFromTools` 直接把最后一条 tool 消息（JSON）`take(1500)` 拼进正文。
- 修复：新增 `agent/runtime/ToolEvidence.kt`——把工具结果转成可读要点（优先 `message`/`error`，否则提取少量关键字段，
  绝不回显花括号），兜底文案改为「已查询：… + - 要点 + 可再问一次」；预算耗尽路径同样使用。
- 测试：`ToolEvidenceTest`(6)。

### Bug 2：请求失败后用户消息没有对应回复（孤儿/重复消息）
- 现象：真机数据库中 [199]「我被识典古籍拦截了」之后没有 assistant 回复；[204]/[205] 两条相同用户消息只得到一条回复。
- 原因：`runTask` 在 appendUser 之后，异常/未配置路径只发 Failed 事件，**故意不写 assistant 记忆**（注释写着“保持历史干净”），
  结果历史里残留无回复的用户消息；用户重发时看起来像“问了两遍”。
- 修复：异常路径与 not_configured 路径都把失败说明写入对话记忆（一行「模型调用失败：…」），修正相关注释。
- 效果：历史始终“用户消息→对应回复”，重试不会再出现孤儿问题。

### 排查中确认「不是 bug」的两项
1. 23:xx 哔哩哔哩被拦：当时处于**专注时段 00:00–07:00**（focus_window_enabled=true），只允许白名单 → 被拦符合设计；
   临时关闭专注时段后 B 站正常打开（已恢复原设置）。
2. 之前「没超额也被拦」：已由 v1.9.1 修复（手动限制列表只在 MANUAL_ONLY 模式生效）。

### 仍未处理（已知，非阻断）
- 结构化卡片仅存在于当前会话内存，重进助手页只剩文本（要持久化需 DB v8/新列）
- `agent_memory(kind=plan/decision)` 只增不减（DB 轻微膨胀，可后续加清理策略）
- 失败后输入框不会自动恢复原文（历史已完整，可手动重发）
- `set_restrict_mode`（旧）与 `set_restriction_mode`（新）语义重叠，建议后续合并工具

- 版本：versionCode 42 / versionName 1.9.3；**155 个单测全部通过**；vivo 真机安装并验证聊天/限制/专注时段行为正常。

---

## 三十一、v1.9.4：修复「单应用已超限额仍可使用」

**用户报告**：抖音今日已用 62 分钟（限额 60 分钟），但仍然可以使用。

**真机数据**：
- `app_daily_limit`: `com.ss.android.ugc.aweme = 60`、`tv.danmaku.bili = 30`
- `app_usage_record` 今日：抖音 3,739,478ms（62.3 分钟）、B 站 7,764,497ms（129.4 分钟）
- 手动重启无障碍服务后再打开抖音 → **立即被拦截**（浮层出现），说明策略与限额数据本身正确

**根因（两个并发缺陷）**：
1. `GuardService` 的「今日已用」只在服务启动时从数据库读一次，之后靠前台切换 + 20 秒 ticker 累加。
   若服务被 OEM 冻结/回收、或数据库同步滞后于实际使用，**内部计数会低于真实值**，
   于是实际 62 分钟、内部仍 <60 分钟，限额不触发；
2. 服务在用户停留于某应用内时重启（或被冻结后恢复），`foregroundPkg` 为空，
   要等下一次窗口切换才判定 —— 用户一直停在抖音里就一直不拦。

**修复（v1.9.4）**：
1. **每 60 秒用数据库统计校正内部计数**：新增 `QuotaBaseline.mergeTotalMinutes/mergePerApp`（纯函数，取 max，只增不减），
   ticker 里调用 `refreshQuotaFromDatabase()`，校正后立即再判一次当前前台应用；
2. **服务重启/冻结恢复后 3 秒**用 `rootInActiveWindow` 重新判定当前前台（`reevaluateCurrentForeground()`），不等窗口切换；
3. **浮层自愈**：若 `overlay` 引用还在但 `isAttachedToWindow != true`（被系统移除），先丢弃再重新挂载；
4. 抽出 `evaluateBlocked(pkg)` 统一「规则 + 额度」判定，窗口事件与服务重启复用同一份逻辑。

**验证（vivo v43 / versionName 1.9.4）**：
- 安装后打开抖音（62 分钟 > 60）→ 立即出现「已强制暂停 抖音」浮层（截图 `out/shots_v17_vivo/52_douyin_blocked.png`）；
- B 站（129 分钟 > 30）同样被拦；
- 单测新增 `QuotaBaselineTest`(4) → **159 个单测全部通过**。
