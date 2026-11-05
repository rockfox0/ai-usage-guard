# AI 使用管家 · 真机验证报告

- 设备：**Pixel 6**（oriole），Android **13 / API 33**，USB 连接（serial `<DEVICE_SERIAL>`）
- 应用：`com.aid.usageguard` v1.0（debug），targetSdk 35 / minSdk 26
- APK：`out/ai_usage_guard/ai-usage-guard-v1.0-debug.apk`，MD5 `b177e2e8e7120d59f53c7f2d3f3bee87`，9.3 MB
- 构建：Gradle 8.13 + AGP 8.13.2 + Kotlin 2.2.10 + Compose BOM 2024.12.01
- 测试时间：2026-11-05 01:47–02:02（本地时区）

## 结论

| 需求 | 结果 | 证据 |
|---|---|---|
| ① 监控各 App 使用时长 | ✅ 通过 | 用 Chrome 100 秒后刷新，总览显示「今日 2 分钟 / Chrome 2 分钟 / 活跃应用 1」，7 天柱状图当日出现 2 分钟 |
| ② 指定 App 强制暂停 | ✅ 通过 | 「设置」加入暂停清单后打开，立即出现全屏「已强制暂停 · 设置」遮罩，倒计时走动；「返回桌面」可退出并撤掉遮罩 |
| ③ AI 总结手机使用 | ✅ 通过 | 设置页填 BaseURL+Key 后点生成，返回真实模型总结（来源 `AI · deepseek-chat · 5491ms`），内容引用真实数据（Chrome、10/31 2h25m、YouTube 已暂停） |

## 关键验证过程

### 1. 使用时长监控

```
Chrome 前台 100s → 回到桌面 → App 刷新
今日屏幕使用：2 分钟
活跃应用：1（Chrome，2 分钟）
最近 7 天：10/30 28 分，10/31 145 分，11/01 30 分，11/02~04 0 分，11/05 2 分
```

期间修掉两个统计问题：

- 熄屏时未收口前台会话 → 时长虚高（加入 `SCREEN_NON_INTERACTIVE` 收口）
- 桌面启动器被算进时长 → 只统计「真正打开的应用」（排除 `CATEGORY_HOME` 包）

### 2. 强制暂停（无障碍 overlay）

```
D UsageGuard: win-change pkg=com.android.settings blocked=true size=2
I UsageGuard: overlay shown for com.android.settings
```

`screencap` 截图 `shots/12_final_block.png`：设置页面被暗色遮罩覆盖，显示「已强制暂停 / 设置 /
已暂停 N 秒 / 返回桌面 / 临时使用 1 分钟」，顶部状态栏仍可下拉（系统 UI 不拦截，避免卡死）。

点「返回桌面」后遮罩窗口消失、回到 Launcher，日志与 `dumpsys window` 均确认 overlay 已移除。

### 3. AI 总结

- 配置：`BaseURL=https://api.deepseek.com`，`模型=deepseek-chat`，格式 OpenAI 兼容
  （该中转同时支持 Anthropic 格式，两种都实测 200）。
- 生成结果（存在 `shared_prefs` 的 `last_summary`）：

```
【使用概况】2026-11-05 累计前台使用仅 2 分钟，全部来自 Chrome。最近 7 天中，11/02 至 11/04
均为 0 分钟，11/05 也处于极低水平。
【值得注意】近一周使用量波动明显：10/31 达 2 小时 25 分钟，11/01 降至 30 分钟，随后连续三天为 0。
YouTube 已设置强制暂停，今日未出现在使用排行中，但数据不足以判断其直接效果。
【建议】继续保持 YouTube 强制暂停；为 Chrome 设置单次使用不超过 5 分钟的提醒，用完即退出。…
```

- 来源标记：`AI · deepseek-chat · 5491ms`；接口失败/未填 Key 时自动降级为本地规则并显示失败原因
  （验证过程中曾出现 `本地规则（接口失败：Cannot set request property after connection is made）`，
  定位为「先写 body 后设 Authorization 头」的顺序 bug，已修复）。

## 修复过的问题清单

1. **AI 请求头顺序**：`HttpURLConnection` 在 `outputStream` 触发连接后不能再设请求头 → 改为先设头后写 body。
2. **重装后无障碍失效**：`adb install -r` 会让系统重置无障碍授权，必须重新 `settings put` 或手动开启；
   已写成 `scripts/ug_setup_device.sh` 固化该步骤。
3. **统计口径**：熄屏会话收口 + 排除桌面启动器，今日/7 天/排行三处口径统一。
4. **技能合规**：targetSdk 34→35、`enableEdgeToEdge()` + `WindowInsets.safeDrawing`、去掉 `configChanges`、
   加 `windowSoftInputMode="adjustResize"`、宽屏 `NavigationRail`、列表自适应列数、搜索框状态 `rememberSaveable`。

## 截图

| 文件 | 内容 |
|---|---|
| `shots/09_overview_ai.png` | 总览：7 天柱状图 + 使用排行 + AI 总结（含来源与耗时） |
| `shots/10_apps.png` | 应用页：搜索、图标、今日时长、暂停开关 |
| `shots/11_settings.png` | 设置页：BaseURL / API Key / 模型 / 协议格式 / 权限状态（均「正常」）/ 125 个应用·2 个已暂停 |
| `shots/12_final_block.png` | 强制暂停拦截页（打开「设置」被拦截） |
| `shots/05_block_overlay.png` | 同一拦截页（较早一次验证，含倒计时） |

## 复现命令

```bash
cd <项目目录>
~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle :app:assembleDebug

# 一键部署（会重置并重新开启无障碍）
UG_API_KEY='<你的 key>' UG_BASE_URL='https://api.deepseek.com' \
  ../scripts/ug_setup_device.sh <DEVICE_SERIAL>

# 复验拦截：把 com.android.settings 加入暂停清单后
adb -s <DEVICE_SERIAL> shell input keyevent KEYCODE_HOME
adb -s <DEVICE_SERIAL> shell am start -a android.settings.SETTINGS
adb -s <DEVICE_SERIAL> logcat -d -s UsageGuard | grep "overlay shown"
```
