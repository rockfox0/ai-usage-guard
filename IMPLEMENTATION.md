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
UG_API_KEY='sk-xxx' UG_BASE_URL='https://api.deepseek.com' ./scripts/ug_setup_device.sh <序列号>
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
