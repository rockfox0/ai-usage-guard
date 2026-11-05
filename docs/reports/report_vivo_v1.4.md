# 新机适配与验收报告：vivo V2527A（Android 16，无 root）

- 设备：**vivo V2527A（PD2527）**，Android **16 / API 36**，serial `<DEVICE_SERIAL>`
- 权限状态：**未 root**（adb shell uid 2000），全部通过 adb + 系统界面完成授权
- 应用：`com.aid.usageguard` **1.4**（versionCode 5），APK 12 MB，MD5 `dac3b1ef6a6db1abe44f0509c8e10612`
- 验证时间：2026-11-05 13:45–14:03

## 一、无 root 部署结果（全部成功）

| 步骤 | 命令 | 结果 |
|---|---|---|
| 安装 | `adb install -r -t app-debug.apk` | Success |
| 使用情况访问 | `adb shell appops set com.aid.usageguard GET_USAGE_STATS allow` | `GET_USAGE_STATS: allow` |
| 无障碍 | `settings put secure enabled_accessibility_services …` + `accessibility_enabled 1` | `Bound services` 含本应用服务 |
| 通知权限 | `pm grant … POST_NOTIFICATIONS` | `granted=true` |
| AI 配置 | debug 包 `run-as` 写入 DataStore protobuf | 界面显示「已连接 deepseek-chat · OpenAI 兼容」 |

> 全程没有 root、没有 `adb root`、没有改系统镜像。

## 二、发现并修复的问题：OEM 系统组件被默认限制误拦

新机首次启动时出现：**「系统导航」被「已强制暂停」遮罩拦截**（vivo 的系统 UI 组件不是桌面包名，也不在白名单）。

根因：`restrictByDefault` 只保护「桌面 + 输入法 + 少量硬编码包名」，OEM 的系统导航/游戏助手等不在其中。

修复（v1.4）：
- `GuardService` 启动时用 `PackageManager.getInstalledApplications` 收集
  `FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP` 的包名，加入保护集合；
- 默认限制**只作用于用户安装的第三方应用**；系统应用（含 OEM 定制）一律放行；
- 同步到 `RuleValidator.registerProtected()`，Agent 的白名单工具也会拒绝系统应用；
- 设置页文案改为「默认限制第三方应用（系统应用与白名单除外）」。

验证（截图 `shots/17_block_v14.png`、`18_system_v14.png`）：

```
第三方「王者荣耀」→ 全屏遮罩「已强制暂停 / 王者荣耀 / 已暂停 4 秒」
系统「设置」→ 正常打开，无任何遮罩
```

## 三、旧手机云备份数据的处理

首次安装后 App 数据从云备份还原（`temp_allow.xml`、白名单都是旧机状态），导致：
- 按钮显示「今日已用过临时使用」（旧机记录）；
- 白名单里是旧机的包，vivo 的系统组件不在其中 → 加重了上面的误拦。

处理：`run-as` 清空 `files/datastore` 与 `shared_prefs` 后重新注入 AI 配置，
让白名单在本机重新 seed。**说明：正式发布建议关闭 `allowBackup` 或对白名单做包名有效性过滤。**

## 四、功能验收（vivo 真机）

| 功能 | 结果 | 证据 |
|---|---|---|
| 真实使用数据 | ✅ | 首页「今日 3 小时 25 分」，7 天趋势 690/569/911/1049/672/798/205 分钟，Top5 含哔哩哔哩 122 分、王者荣耀 33 分、QQ 29 分 |
| 第三方应用拦截 | ✅ | 知乎、王者荣耀打开即出现遮罩；按钮「返回桌面 / 临时使用 1 分钟」 |
| 系统应用放行 | ✅ | 设置、系统导航、输入法均不再被拦 |
| AI 调控白名单 | ✅ | 对话「add Zhihu to whitelist」→ 工具调用 `set_whitelist`，DataStore 出现 `com.zhihu.android`，应用页「已允许 6 个」 |
| 实时提示（陪伴模式） | ✅ | 知乎前台 1 分钟后顶部弹出「AI 陪伴 / 「知乎」开了 1 分钟，眼睛和脖子都该休息一下。」（截图 `15_nudge_75.png`） |
| 通知 | ✅ | `NotificationRecord pkg=com.aid.usageguard id=9999 channel=ai_report`；4 个渠道（anomaly / daily_reminder / goal_exceeded / ai_report） |
| 后台任务 | ✅ | JobScheduler 中本应用 4 个周期任务（同步 30min / 分析 60min / 日报 22:30 / 周报周日 21:00） |
| 稳定性 | ✅ | 全程无 `FATAL EXCEPTION`，无障碍服务保持绑定 |
| 页面适配 | ✅ | 1216×2640 屏幕下首页/应用/AI 助手/设置排版正常，边到边与 insets 正确 |

## 五、Android 16 / vivo 注意事项

1. **无 root 完全可用**，授权的三个特权能力（使用情况访问、无障碍、通知）都能由 adb 一次授予。
2. vivo 对后台较激进：长期使用建议在「设置 → 电池 → 后台耗电管理」把本应用设为**允许后台运行**，否则 WorkManager 定时任务可能被延迟。
3. `am force-stop` 会杀掉无障碍服务，需要重新开启（脚本已处理）；日常不要手动强行停止。
4. 免打扰时段默认 23:00–07:00，测试「发送测试通知」时若在时段内会被静默跳过。
5. 若从旧机云备份还原，白名单/临时使用记录会带过来，建议在设置里确认（后续版本可加包名校验）。

## 六、产物

```
out/ai_usage_guard/v1.4_vivo/
├── ai-usage-guard-v1.4-debug.apk        # MD5 dac3b1ef6a6db1abe44f0509c8e10612
└── shots/                               # 19 张真机截图
    ├── 02_after_fix.png                 # 修复后首页（真实数据）
    ├── 05_block_zhihu.png               # 第三方应用拦截
    ├── 06_system_not_blocked.png        # 系统设置放行
    ├── 14_notification.png              # 通知
    ├── 15_nudge_75.png                  # 实时提示
    ├── 17_block_v14.png / 18_system_v14.png  # v1.4 回归
    └── ...
```

复现命令：

```bash
SERIAL=<DEVICE_SERIAL>
adb -s $SERIAL install -r -t out/ai_usage_guard/v1.4_vivo/ai-usage-guard-v1.4-debug.apk
adb -s $SERIAL shell appops set com.aid.usageguard GET_USAGE_STATS allow
adb -s $SERIAL shell pm grant com.aid.usageguard android.permission.POST_NOTIFICATIONS
adb -s $SERIAL shell settings put secure enabled_accessibility_services \
    com.aid.usageguard/com.aid.usageguard.service.GuardService
adb -s $SERIAL shell settings put secure accessibility_enabled 1
```

> 当前手机上：默认限制**开启**，白名单 6 个（微信、QQ、钉钉、支付宝、微信输入法、知乎）；
> 实时提示每 15 分钟；AI 配置已填写。若要关闭限制：设置 → 限制模式 → 关闭。
