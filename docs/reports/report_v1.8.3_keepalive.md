# v1.8.3：守护保活（App 被清后台后自动恢复）

- 设备：vivo V2527A（Android 16），versionCode 20，MD5 `320bbef07a320fcf117551b47bbd4daa`
- 问题：把 App 后台清掉后，无障碍服务被系统/OEM 关闭，限制功能失效。

## 一、实现（4 层保活）

| 机制 | 说明 |
|---|---|
| **前台常驻服务** | `KeepAliveService`：低优先级常驻通知「AI 使用管家守护中 · 今日已用 X/Y 分钟」，`START_STICKY`，被回收后尽量自拉起 |
| **无障碍自恢复** | `AccessibilityRestorer`：每 60 秒检查 `enabled_accessibility_services`；被关闭时写回组件并置 `accessibility_enabled=1`。需要一次性 adb 授权 `WRITE_SECURE_SETTINGS` |
| **看门狗** | WorkManager `GuardWatchdogWorker` 每 15 分钟兜底检查 + 重新拉起前台服务 |
| **开机/更新自启** | `BootReceiver`（BOOT_COMPLETED / MY_PACKAGE_REPLACED）恢复无障碍 + 启动保活服务 |
| **无法恢复时通知** | 没有自恢复权限或写入失败时，发通知「守护已停止，请手动开启」（30 分钟内不重复） |
| **引导** | 设置页「守护保活」卡：三行状态（无障碍/自恢复授权/电池优化）+ 按钮（申请忽略电池优化、打开自启动/后台设置、打开无障碍设置、复制 adb 命令） |

## 二、vivo 真机验证

| 场景 | 结果 |
|---|---|
| 一次性授权 `adb shell pm grant com.aid.usageguard android.permission.WRITE_SECURE_SETTINGS` | 成功，设置页显示「自恢复授权：已授权」✅ |
| 模拟 OEM 关闭无障碍（`settings put secure enabled_accessibility_services null` + `accessibility_enabled 0`） | **约 20 秒内自动恢复**：`enabled=1`、列表重新包含 GuardService；logcat：`AccessibilityRestorer: accessibility service restored` / `KeepAliveService: restored accessibility from watchdog` ✅ |
| 普通后台清理（`adb shell am kill`，等价从最近任务划掉） | 进程存活（前台服务 + 无障碍绑定），无障碍保持 enabled=1 ✅ |
| 加入 Doze 白名单（`dumpsys deviceidle whitelist +com.aid.usageguard`） | 成功（等价「忽略电池优化」），设置页显示已加入 ✅ |
| 强制停止（`am force-stop`） | **无法自动恢复**：Android 会清除该应用闹钟/广播并禁止后台启动，这是系统限制；需要手动打开一次 App。已在设置页说明，并保留通知兜底 ✅（诚实记录） |
| 常驻通知 / 服务 | `guard_alive` 通知存在；`GuardService` 运行且被系统允许前台启动 |

## 三、使用建议（vivo）

1. 设置 → 守护保活 → 申请忽略电池优化（已通过 adb 加入 Doze 白名单）
2. 打开自启动/后台设置：给「AI 使用管家」开启**自启动**、**后台高耗电允许**、**锁屏后不清理**
3. 最近任务里给卡片**加锁**（vivo 下拉卡片 → 锁定）
4. 不要用「强行停止」——那是用户明确要求彻底停止，任何应用都无法自恢复；普通划掉/一键清理可自愈

## 四、产物

```
out/ai_usage_guard/v1.8.3/
├── ai-usage-guard-v1.8.3-debug.apk   # MD5 320bbef07a320fcf117551b47bbd4daa
└── shots/26_keepalive_card.png
```
