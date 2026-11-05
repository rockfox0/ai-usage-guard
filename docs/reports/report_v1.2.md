# AI Digital Wellbeing v1.2 · 增补验收

- 设备：Pixel 6 / Android 13（API 33）；APK 12 MB，MD5 `b979b3486cefe52bcbd8ec2c647731ca`
- 构建：Gradle 8.13 + AGP 8.13.2 + Kotlin 2.2.10 + Compose + Room + WorkManager + OkHttp
- 时间：2026-11-05 03:00–03:21

## 本轮三项需求

### 1) 默认限制 + 白名单

- 设置页新增「限制模式」开关，默认**开启**：除白名单与系统关键应用外，所有应用打开即拦。
- 白名单存 DataStore（`allowed_packages`），「应用」页开关在默认限制模式下语义为「允许」，
  行副标题显示 `系统保护 / 白名单允许 / 默认受限 / 手动限制`；系统保护项开关置灰。
- 首次升级自动把已安装的通讯/办公类应用加入白名单（微信、QQ、企业微信、钉钉、支付宝、微信输入法）。
- **发现并修复**：输入法（Gboard）最初也被默认限制拦截，导致无法打字 → 在 `GuardService` 中把
  `InputMethodManager.inputMethodList` 的包名加入豁免集合，日志确认 `blocked=false`。

真机证据：

```
D UsageGuard: win-change pkg=com.android.chrome blocked=true byDefault=true allowed=4 explicit=1
I UsageGuard: overlay shown for com.android.chrome
D UsageGuard: win-change pkg=com.tencent.mm blocked=false byDefault=true allowed=4   # 白名单放行
D UsageGuard: win-change pkg=com.google.android.inputmethod.latin blocked=false      # 输入法放行
```

截图：`shots/23_settings_v12.png`（限制模式）、`24_apps_v12.png`（默认受限/允许开关）、
`25_block_default.png`（默认受限拦截页）。

### 2) 临时使用限次（每 App 每天 1 次）

- 新增 `TempAllowStore`（SharedPreferences 按自然日记录）。
- 首次点「临时使用 1 分钟」→ 记录 `used_<pkg>=yyyy-MM-dd`，放行 60 秒；
- 同一天再次进入该应用 → 按钮变为「今日已用过临时使用」，点击无效。

证据：`shared_prefs/temp_allow.xml` 中 `used_com.android.chrome=2026-11-05`；
截图 `26_temp_allow_1.png`（放行后 Chrome 可见）、`27_temp_allow_used.png`（按钮已禁用）。

### 3) AI 助手输出文本优化

- 新增 `ui/MarkdownLite.kt`：`#` 标题、`**加粗**`、`- ` 列表转圆点、`` `代码` `` 等宽、
  Markdown 表格在窄屏转成 `列1 · 列2 · 列3` 并加粗表头。
- AI 助手与报告页正文统一使用；系统提示词追加「不要输出 Markdown 表格/分割线/HTML」。
- 修复中转 thinking 模式导致的 400：`AgentLoop` 检测到 `thinking` 相关错误时丢弃历史重试一次。

截图 `29_assistant_markdown_v13.png`、`31_assistant_retry.png`：加粗数字、圆点列表、
表格转行均正常，无原始 `**` / `|`。

## 已知限制

- 默认限制模式下，未列入白名单的第三方应用一律被拦；用户可在「应用」页逐个放行，
  或在设置里关闭「限制模式」回到只限制指定应用。
- 输入法与系统关键应用不可被限制（设计如此）。
- 临时使用按本地自然日重置。
