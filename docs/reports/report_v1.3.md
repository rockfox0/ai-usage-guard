# AI Digital Wellbeing v1.3 · 增补验收（AI 调控白名单 / 实时提示 / AI 个性化）

- 设备：Pixel 6 / Android 13（API 33）
- 版本：`com.aid.usageguard` **1.3**（versionCode 4），APK 12 MB，MD5 `50668a1499be5c53523a28c17cf20d15`
- 验证时间：2026-11-05 03:52–04:03

## 1) AI 调控白名单 ✅

实测对话（真机，AI 助手页）：

```
用户：add Chrome to the whitelist
AI  ：🔧 本次调用工具：get_whitelist
      老王，你这句话我没读准…白名单里已经有 QQ、微信、微信输入法、电话这 4 个应用。
      我的理解：你想把 Chrome 加进白名单。确认的话回我一句「确认加 Chrome」。
用户：yes add Chrome to whitelist please
AI  ：🔧 本次调用工具：set_whitelist
      已生效，老王。Chrome 已加入白名单，当前白名单共 5 个：Chrome、QQ、微信、微信输入法、电话。
      默认限制仍然开启，但 Chrome 现在不会被暂停了。
```

验证：
- DataStore `allowed_packages` 新增 `com.android.chrome`（run-as 读取原文确认）
- 「应用」页头部变为 **「默认限制所有应用 · 已允许 5 个」**
- 系统保护应用会被拒绝（电话/短信/设置/桌面/输入法），工具返回 `rejectedReason="系统保护应用无需白名单"`

## 2) 使用中的实时提示（陪伴模式）✅

- 设置里开启「使用过程中给提示」，间隔设为 1 分钟（测试值；正式使用已恢复 15 分钟）。
- 打开白名单应用「微信」并保持前台，日志：

```
D UsageGuard: win-change pkg=com.tencent.mm blocked=false byDefault=true allowed=4
I UsageGuard: hint shown: 「微信」开了 1 分钟，眼睛和脖子都该休息一下。
I UsageGuard: hint shown: 提醒一下：你已经连续用「微信」2 分钟。
```

- 截图 `shots/03_nudge_hint.png`：屏幕顶部细长卡片「AI 陪伴 / 提醒一下：你已经连续用「微信」2 分钟。」，
  覆盖在微信界面之上但不遮挡中部操作，8 秒自动消失。
- 文案默认走本地模板；打开「用 AI 生成提示语」时走 `AgentRuntime.quickHint()`（≤30 字），失败自动回退本地。

## 3) AI 个性化 ✅

设置页新增两个卡片（截图 `shots/01_settings_personalize.png`、`02_settings_ai_persona.png`）：

- **AI 个性化**：称呼、语气（温和/直接/严厉）、关注重点（睡眠/学习/娱乐/效率，多选）、给 AI 的额外要求
- **实时提示（陪伴模式）**：开关、间隔 5/10/15/30 分钟、是否用 AI 生成

实测生效证据（同一条 AI 回答里）：

```
AI：老王，你这句话我没读准…
    …时间不早了，早点睡。
```

- 「老王」→ `aiNickname` 生效；
- 直接、少铺垫的语气 → `aiTone=direct` 生效；
- 「时间不早了，早点睡」→ `aiCustomPrompt="别用感叹号，多提早点睡"` 生效。

## 涉及文件

| 层 | 文件 |
|---|---|
| 数据 | `data/settings/SettingsRepository.kt`（个性化 + 提示配置字段/读写） |
| Agent | `agent/tools/WhitelistTools.kt`（新）、`agent/runtime/AgentLoop.kt`（persona 注入 + quickHint）、`agent/llm/*`（maxTokens 可选参数） |
| 服务 | `service/GuardService.kt`（前台计时 + 提示卡片 + 输入法保护注册）、`service/LocalHints.kt`（新） |
| 限制 | `restriction/AppRestrictionController.kt`（`RuleValidator.registerProtected`）、`restriction/TempAllowStore.kt` |
| UI | `ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt`（两个新卡片） |
| 契约 | `agent/contract/AgentContracts.kt`（`quickHint`） |

## 说明

- 陪伴提示仅在本应用有无障碍服务且系统未杀进程时生效；系统关闭无障碍会停止（设置页会提示）。
- 提示间隔最小 5 分钟（测试时用 1 分钟验证，现已恢复 15 分钟）。
- AI 生成提示会增加 token 消耗，默认关闭。
