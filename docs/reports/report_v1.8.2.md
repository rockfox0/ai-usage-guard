# v1.8.2：30 天数据 + 更宽的 Agent 上下文 + 白名单每日上限 + 专注时段

- 设备：vivo V2527A（Android 16），versionCode 18，MD5 `4ee200a455e9b30705687365995e6172`
- 单元测试：**34 个全部通过**（新增 FocusWindow 5 个、QuotaPolicy 专注时段 3 个）

## 一、四项需求与实现

### 1) Agent 上下文范围更广（并针对 deepseek-flash 做 token 控制）
- **对话历史**：回放条数 12 → **24 条**，并逐条截断（用户消息 ≤600 字、助手消息 ≤1200 字，超出加「历史消息已截断」），避免小上下文模型被长历史淹没。
- **系统提示词新增「本地数据快照」段**（自动生成、约 6 行）：
  今天已用/额度/剩余、近 7 天日均、近 30 天日均与有效天数、近 30 天 Top3 应用、白名单数量与今日还可添加数、数据范围说明，
  并明确标注「仅作概览；具体数值/排名/结论必须调工具核对」。
- **工具描述精简**：22 条过长描述重写，最长 198 字 → 81 字（工具总数 34，schema 参数名不变），降低 flash 的 prompt 体积。

### 2) 可观察最近 30 天（原来只有 7 天）
- 采集：首次回填 7 → **30 天**，单次同步窗口 8 → **31 天**（`UsageIngester`）。
- 仓储：`UsageRepository.dailyTotals(from,to)`、`dailyTotalsForLastDays(days)`、`rankingForRange(from,to,limit)`。
- 新工具 **`get_usage_trend(days=1..30)`**：总量/日均/有效天数/缺失天数/峰值日/最低日/逐日明细/Top 应用/周环比，缺数据不补零并注明「更早数据不完整」。
- `get_app_usage_history` 天数上限 7 → **30**（>7 天按日聚合）。
- 报告任务也可见 `get_usage_trend`（只读）。

### 3) 每天只能把一个应用加入白名单
- 新设置：`whitelistDailyLimit`（默认 **1**，0=不限）、`whitelistAddDate/Count` 计数，跨天自动重置。
- 新仓储 `WhitelistRepository`（`Container.whitelist`）：`quota()`、`add()`→Added/AlreadyAllowed/**Rejected**、`remove()`、`setDailyLimit()`。
- **所有写入口都走它**：设置页、应用列表/详情开关、`ActionExecutor` 的 `SET_WHITELIST` 与「放行」（SET_APP_RESTRICTION）——即使 AI 草案确认也受同一限制；移出白名单不消耗次数。
- AI 工具 `set_whitelist` 在生成草案前也先检查，已满则直接返回 REJECTED；新增 `set_whitelist_daily_limit` 草案工具。

### 4) 专注时段内只允许白名单应用
- 新设置：`focusWindowEnabled`（默认关）、`focusWindowStart`（22）、`focusWindowEnd`（7），支持跨午夜，起止相同=全天。
- 纯策略 `FocusWindow.isActive(nowHour,start,end)` + `QuotaPolicy` 新原因 **`FOCUS_WINDOW`**；优先级：系统保护 > 单应用限额 > **专注时段** > 白名单 > 全局额度。
- `GuardService` 实时判定：时段内非白名单应用打开即拦截，浮层「专注时段 / 现在是专注时段（22:00-07:00），这段时间只能使用白名单里的应用」；超额提示与专注时段提示分开。
- 设置页「专注时段」卡（开关 + 起止小时 + 当前是否生效）；应用列表/详情/首页显示「专注时段生效中（仅白名单可用）」。
- AI 工具 `set_focus_window`（草案，支持跨午夜）与 `get_app_settings/get_quota_status` 暴露当前状态。

## 二、真机验证（vivo）

| 项 | 结果 |
|---|---|
| 专注时段拦截 | 设置页开启（22:00-07:00，当时 22:0x）→ 打开知乎（非白名单）：`blocked=true rule=false quota=FOCUS_WINDOW total=438/360m`，浮层「专注时段」✅ |
| 专注时段白名单放行 | 同时段打开 DeepSeek（白名单）：`blocked=false quota=NONE`，正常进入 ✅ |
| 白名单每日上限 | 第 1 次把知乎加入白名单：成功，`whitelist_add_count=1`；第 2 次把爱彼迎加入：被拒「今天最多只能添加 1 个应用到白名单（已用 1 个），明天再试」，开关回滚，计数仍为 1 ✅ |
| 30 天数据/趋势 | 采集与仓储已扩到 30 天；**本机只有 8 天数据（2026-10-29 ~ 11-05）**——Android 系统 UsageStats 事件通常只保留约 7~10 天，属系统限制；工具会如实返回 validDays/missingDays 并注明数据不完整 |
| 上下文快照 | 系统提示词已注入今日/7天/30天概览 + 白名单额度（代码路径已验证） |
| 数据库 | 未改 schema（本次无迁移需求），设置项为新增 DataStore key；34 单测通过，无崩溃 |

## 三、测试后状态还原
- 专注时段：已关闭（保留默认 22-7），白名单每日上限恢复默认 1、今日计数已清零
- 测试加入的知乎已移出白名单；额度 360、限制模式「超额后才限制」、无障碍服务已绑定

## 四、产物

```
out/ai_usage_guard/v1.8.2/
├── ai-usage-guard-v1.8.2-debug.apk   # MD5 4ee200a455e9b30705687365995e6172
└── shots/  # 专注时段拦截、设置页两张新卡、白名单加入成功/被拒提示
```
