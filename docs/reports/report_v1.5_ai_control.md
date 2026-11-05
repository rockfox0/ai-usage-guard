# v1.5 验收：AI 可完整调控这个 App（25 个工具）

- 设备：vivo V2527A，Android 16 / API 36，无 root，serial `<DEVICE_SERIAL>`
- 版本：`com.aid.usageguard` **1.5**（versionCode 6），APK 12 MB，MD5 `7b7d63de7916258c8da64b5116156cd0`
- 验证时间：2026-11-05 14:19–14:40

## 一、Agent 工具总览（10 → 25）

| 组 | 工具 |
|---|---|
| 使用数据（5） | get_daily_usage / get_weekly_usage / get_app_usage_history / get_usage_ranking / get_user_preferences |
| 行为（1） | get_behavior_events |
| 报告（1） | get_reports |
| 限制建议（1） | propose_restriction（只建议） |
| 白名单（2） | get_whitelist / set_whitelist |
| **App 控制（15，本轮新增）** | **get_app_settings**、set_daily_target、set_quiet_hours、set_notification_enabled、set_notification_channels、set_reminder_style、set_theme_mode、set_restrict_mode、set_nudge、set_ai_persona、**set_app_restriction**、set_ai_analysis_enabled、refresh_usage_data、**generate_report_now**、**send_test_notification** |

所有修改类工具返回 `{executed, changed:{字段:{from,to}}, message, settingsUrlHint}`，
描述里统一写「仅在用户明确要求修改时调用；只是询问时用 get_app_settings」。

## 二、真机实测（全部由 AI 助手在对话中完成，非人工点设置页）

对话原文见 `agent_message` 表（id 18–35），DataStore/DB 为独立核对：

| # | 用户说 | AI 调用的工具 | 独立验证 |
|---|---|---|---|
| 1 | show me my current settings | `get_app_settings` | 回复含目标/勿扰/通知/主题/限制/白名单/提示/AI 全量 |
| 2 | set my daily target to 90 minute | `set_daily_target` | `daily_target_minutes: 120 → 90` |
| 3 | set theme to dark | `set_theme_mode` | `theme_mode = 'dark'`，界面立即变深色 |
| 4 | turn off all notifications | `set_notification_enabled` | `notification_enabled = False` |
| 5 | send me a test notification | `send_test_notification` | `NotificationRecord id=1099 channel=ai_report` |
| 6 | restrict bilibili | `set_app_restriction` | `restriction_rule` 新增 `tv.danmaku.bili`（bilibili 122 分钟/160 次被限制） |
| 7 | generate a weekly report right now | `generate_report_now` + `get_weekly_usage` | `agent_report` 新增 `WEEKLY / AI / len=610` |
| 8 | set target back 120, notifications on, theme system, unblock bilibili | 4 个工具一轮执行 | `target=120`、`notification_enabled=True`、`theme_mode='system'`、`restriction_rule` 清空 |

第 8 条特别验证了「一句话批量调控」：单轮工具调用里完成 4 项修改，并回显每项旧→新。

## 三、安全边界（仍然保留）

- **系统保护应用**（电话/短信/设置/桌面/本应用/输入法）不能被 `set_app_restriction` 限制，返回 `rejectedReason`。
- **报告生成过程中**禁止调用任何会改状态的控制工具（REPORT_SYSTEM 规则收紧）。
- `generate_report_now` 有递归护栏（报告任务里再调用会直接拒绝）。
- 修改类工具只在用户明确要求时触发；意图不明会先追问（实测「add Zhihu to whitelist」先 `get_whitelist` 再确认）。

## 四、测试结束后的现场状态（已恢复为常用配置）

```
daily_target_minutes   = 120
quiet_hours            = 23:00 - 07:00
notification_enabled   = True（五个渠道全开）
theme_mode             = 'system'
restrict_by_default    = True（默认限制第三方应用）
allowed_packages       = 微信、QQ、钉钉、支付宝、微信输入法、知乎（6 个）
realtime_nudge         = 开启，间隔 15 分钟，本地文案
restriction_rule       = 空（bilibili 已放行）
```

设备状态：无障碍已绑定、使用情况访问 allow、通知已授权、无 FATAL EXCEPTION。

## 五、产物

```
out/ai_usage_guard/v1.5_ai_control/
├── ai-usage-guard-v1.5-debug.apk     # MD5 7b7d63de7916258c8da64b5116156cd0
└── shots/                            # 23 张（AI 改设置、深色主题、群控恢复等）
```

复现：

```bash
adb -s <DEVICE_SERIAL> install -r -t out/ai_usage_guard/v1.5_ai_control/ai-usage-guard-v1.5-debug.apk
adb -s <DEVICE_SERIAL> shell appops set com.aid.usageguard GET_USAGE_STATS allow
adb -s <DEVICE_SERIAL> shell settings put secure enabled_accessibility_services \
  com.aid.usageguard/com.aid.usageguard.service.GuardService
adb -s <DEVICE_SERIAL> shell settings put secure accessibility_enabled 1
# 然后在应用内「AI 助手」说：把每日目标改成 90 分钟 / 关闭通知 / 限制 bilibili ...
```
