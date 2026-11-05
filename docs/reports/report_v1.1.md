# AI Digital Wellbeing v1.1 · 真机验收报告

- 设备：**Pixel 6（oriole）**，Android **13 / API 33**，USB（serial `<DEVICE_SERIAL>`）
- 应用：`com.aid.usageguard` v1.1（debug），`minSdk 26 / targetSdk 35 / compileSdk 36`
- 构建：Gradle 8.13 + AGP 8.13.2 + Kotlin 2.2.10 + Compose BOM 2024.12.01 + Room 2.7.1(KSP) + WorkManager 2.9.1 + OkHttp 4.12
- APK：`ai-usage-guard-v1.1-debug.apk`，12 MB，MD5 `1406064723ab974239fd50ffa58b1ba0`
- 代码：**61 个 Kotlin 文件 / 9236 行**
- 验证时间：2026-11-05 02:23–02:43（Asia/Shanghai）

## 一、结果总览（14 项验收）

| # | 验收项 | 结果 | 关键证据 |
|---|---|---|---|
| 1 | 可在 Android 手机安装 | ✅ | Pixel 6 安装，全程无 `FATAL` 日志 |
| 2 | 引导授予 Usage Access | ✅ | 权限卡片 → 系统「使用情况访问」；adb `appops GET_USAGE_STATS: allow` |
| 3 | 读取真实 App 使用时长 | ✅ | 用 Chrome 后首页显示 3 分钟；DB `usage_session` 91 条 |
| 4 | 今日使用排行榜 | ✅ | 首页 Top5；应用页 127 个应用（Chrome 3 分钟） |
| 5 | 最近 7 天使用情况 | ✅ | 回填 10/30–11/05：28、145、30、0、0、0、3 分钟 |
| 6 | 保存历史数据 | ✅ | Room 持久化；杀进程重启后报告历史/使用记录仍在（截图 16） |
| 7 | 检测 ≥3 种异常行为 | ✅ | 实现 6 类；实测触发 `OVER_TOTAL_TARGET`（目标改 1 分钟 → 2 分钟即报，HIGH） |
| 8 | WorkManager 后台分析 | ✅ | JobScheduler 4 个任务，`Requires: batteryNotLow=true` |
| 9 | 调用 LLM（DeepSeek/兼容） | ✅ | 中转 自建中转（地址已移除），OpenAI/Anthropic 双协议均 200 |
| 10 | Agent 调用 ≥3 个本地工具 | ✅ | 日报实际调用 **5 个**工具（见下） |
| 11 | 真实数据驱动 AI 日报 | ✅ | 报告引用真实数据；来源标签 `AI` |
| 12 | 主动发送使用情况提醒 | ✅ | 4 个通知渠道；`NotificationRecord id=9999 channel=ai_report` 投递成功 |
| 13 | 断网时基础统计可用 | ✅ | BaseURL 指向不可达地址 → 报告自动降级「本地规则」，统计照常 |
| 14 | 重启后历史与任务恢复 | ✅ | 重启后报告历史、行为事件、WorkManager 任务均存在 |

## 二、关键证据

### 1. 真实使用数据（Room 直查）

```
app_info: 21     usage_session: 91     app_usage_record: 31
agent_report: 2  agent_message: 2      behavior_event: 1
今日聚合：com.android.chrome 2026-11-05 3 分钟 / 10 次启动
会话明细：Chrome 01:58:03 起 100 秒；01:48 三次短会话
```

### 2. AI 日报（真实 LLM + 5 个工具调用）

```
【使用概况】2026-11-05 今天手机使用总时长仅 2 分钟，全部来自 Chrome…每日目标 2 小时…
【值得注意】今天没有检测到异常行为事件。本周 10/31 使用 2 小时 25 分钟，为最高的一天…
【建议】本周整体低于目标，无需限制任何应用…

— 本次 Agent 调用的工具：get_daily_usage、get_usage_ranking、get_behavior_events、
  get_weekly_usage、get_user_preferences
```

### 3. AI 助手（多轮工具调用 + 会话持久化）

用户问「今天我用得最多的是什么？」，助手回答引用真实数据（Chrome 2 分钟 / 4 次打开 / 目标 2 小时），
并展示 `🔧 本次调用工具：get_daily_usage、get_usage_ranking`；
`agent_message` 表持久化 user/assistant 两条记录与 toolCalls。

### 4. 行为分析（6 类规则）

把每日目标临时设为 1 分钟 → 打开「行为分析 → 立即分析」：

```
OVER_TOTAL_TARGET | severity=HIGH
"今天已使用 2 分钟，超出每日目标（1 分钟）1 分钟，已达目标的 2.0 倍。"
```

其余 5 类（用量激增 / 单次连续过久 / 深夜使用 / 1 小时频繁打开 / 连续多日增长）在
`BehaviorAnalyzer.kt` 中以可配置阈值实现，基线取近 7 天自身均值。

### 5. 强制暂停（无障碍 overlay）

把 Chrome 打开暂停开关 → 启动 Chrome：

```
D UsageGuard: win-change pkg=com.android.chrome blocked=true rules=1
I UsageGuard: overlay shown for com.android.chrome
```

截图 `shots/13_block_overlay.png`：Chrome 被全屏遮罩覆盖，显示「已强制暂停 / Chrome /
已暂停 0 秒 / 返回桌面 / 临时使用 1 分钟」；点「返回桌面」遮罩移除、回到 Launcher。
规则来自 Room `restriction_rule`，`RuleValidator` 禁止把电话/短信/系统设置/桌面加入限制。

### 6. 主动提醒与定时任务

- 通知渠道（dumpsys notification）：`anomaly`、`daily_reminder`、`goal_exceeded`、`ai_report`
- 投递证据：`NotificationRecord(pkg=com.aid.usageguard id=9999 channel=ai_report)`
- JobScheduler：4 个 `SystemJobService` 任务（同步 30min / 分析 60min / 日报 22:30 / 周报周日 21:00）
- 免打扰时段默认 23:00–07:00（测试时临时改到 08:00–09:00 才触发，符合设计）

## 三、修复过的问题

1. **AI 请求头顺序**：`HttpURLConnection` 写 body 后再设 Authorization 会抛 `IllegalStateException` → 改为先设头后写 body。
2. **统计口径**：熄屏未收口会话导致时长虚高、桌面启动器被计入 → 加入 `SCREEN_NON_INTERACTIVE` 收口与 HOME 排除，今日/7 天/排行三处统一。
3. **无障碍服务被重置**：`install -r` 与 `am force-stop` 都会清除无障碍绑定 → 部署脚本每次重新开启，`GuardService` 增加 onServiceConnected 日志与规则变更日志。
4. **设置页 Key 不回显**：ViewModel 初始化与 DataStore 首次发射的时序问题 → 改为 `remember(settings.xxx)` 同步。
5. **助手 Markdown 原样显示**：模型返回 `**加粗**` → 纯文本渲染时剥离标记。
6. **并发构建污染**：三名 teammate 同时 `compileDebugKotlin` 导致 `kotlin-classes` 增量缓存损坏（`FileNotFoundException Container.class`）→ 改为 Lead 统一构建，清理 tmp 后一次通过。

## 四、产物

```
out/ai_usage_guard/v1.1/
├── ai-usage-guard-v1.1-debug.apk
├── spec-readme.md            # 规格原文快照
└── shots/                    # 23 张真机截图
    ├── 01_dashboard.png      首页：今日 2 分钟 / 目标进度 / 7 天趋势 / Top5 / 分布 / 异常数
    ├── 06_assistant_reply.png AI 助手：真实回答 + 🔧 工具调用
    ├── 07_report_ai.png      AI 日报（来源 AI）
    ├── 08_report_expanded.png  报告全文 + 工具清单
    ├── 09_behavior.png       行为分析：OVER_TOTAL_TARGET（HIGH）
    ├── 13_block_overlay.png  强制暂停遮罩（Chrome）
    ├── 14_app_detail.png     App 详情（今日/7 天/日均/启动次数/最近使用）
    ├── 15_report_local_fallback.png  AI 不可用时的本地规则报告
    ├── 17_apps_list.png      应用列表（127 个可启动应用）
    ├── 18_settings.png       设置：权限状态 / AI 接口 / 目标 / 通知 / 隐私
    └── 22_final_dashboard.png  最终状态
```

## 五、复现命令

```bash
cd <项目目录>
~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle :app:assembleDebug

# 一键部署（安装 + 使用情况访问 + 无障碍 + 可选 AI 配置）
UG_API_KEY='<key>' UG_BASE_URL='https://api.deepseek.com' \
  ../scripts/ug_setup_device.sh <DEVICE_SERIAL>

# 复验拦截
adb -s <DEVICE_SERIAL> logcat -c
adb -s <DEVICE_SERIAL> shell am start -n com.android.chrome/com.google.android.apps.chrome.Main
adb -s <DEVICE_SERIAL> logcat -d -s UsageGuard | grep "overlay shown"
```

## 六、说明与限制

- 验收过程中人类用户同时在使用手机，因此个别截图（通知栏、最近任务）可能包含其它应用内容；功能证据以 dumpsys/logcat/DB 为准。
- 行为分析的会话级检测（长会话/深夜/频繁打开）只对「今天」精确归属，历史日期做总量类检测。
- 强制暂停依赖无障碍服务存活；系统关闭或 force-stop 后需重新开启（脚本与 App 内均有提示）。
- 测试用的 BaseURL/API Key 来自用户提供的中转；正式发布建议 HTTPS + 后端代理，不要把 Key 打进 APK。
