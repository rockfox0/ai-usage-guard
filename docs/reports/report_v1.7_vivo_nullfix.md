# v1.7 在 vivo 真机测试 + null 显示问题修复

- 设备：vivo V2527A（Android 16 / API 36，无 root），serial `<device>`
- 版本：`com.aid.usageguard` **1.7**（versionCode 11），MD5 `43bccd0b83754cb5d35ed9e9ace05a73`
- 时间：2026-11-05 19:15–19:30

## 一、用户反馈问题：「AI 回复发出一堆 null」

### 根因
流式解析把 **JSON 的 null 当成文本**：
- OpenAI 兼容 SSE 的工具调用分片里 `choices[0].delta.content` 是 **JSON null**；
- 代码用 `delta.optString("content")` 取值，Android `org.json` 对 `JSONObject.NULL` 返回字符串 `"null"`；
- 该字符串被当作正文增量 `TextDelta("null")` 拼进回复，并持久化到 `agent_message`，于是显示 `nullnullnull…`。

同一问题也存在于 Anthropic 的 `text_delta.text`、非流式 `message.content`、工具名/参数 `optString`。

### 修复
1. 解析层显式判类型：`val content = delta.opt("content").takeIf { it is String } as String? ?: ""`
   （OpenAI 流式/非流式正文、工具名/参数；Anthropic `text_delta` / text block / tool_use.name）。
2. 展示层兼容历史脏数据：`AgentMemory.sanitizeContent()` 对读取的历史消息去掉开头的连续 `(null)+`，
   同时 LLM 回放历史也用清理后的内容（避免把 null 再喂给模型）。

### 验证（vivo 真机）
- 修复前：`[82] 19:22:13 assistant len=1786` 内容以 `nullnullnull…` 开头
- 修复后：`[84] 19:26:06 assistant len=158` 内容 `'嗨，又见面了。…'`，**无 null 前缀**；界面上历史消息也不再显示 nullnull

## 二、v1.7 在 vivo 的验收

| 项 | 结果 |
|---|---|
| 升级迁移 T22 | v1.6 → v1.7：DB `user_version 2 → 3`，会话 10656→10777、记录 224→227、报告/消息/评分保留，`pending_action` 表创建；应用无崩溃（logcat 里唯一的 FATAL 是 `com.dragon.read` 的，与本应用无关） |
| 权限/无障碍 | 使用情况访问、通知、无障碍绑定正常 |
| 首启/首页 | 正常渲染（含今日评分卡） |
| AI 聊天（流式） | 正常出字，无 null（见上） |
| 草案确认 T10/T11 | 聊天「set my daily target to 300 minutes」→ 生成 `SET_DAILY_TARGET 360→300 PENDING`（19:27:42）；随后在应用内确认 → 19:27:57 `CONFIRMED/EXECUTED`，DataStore `daily_target_minutes=300` |
| 拦截/放行 T05 | 第三方「知乎」：`blocked=true` + 拦截浮层；系统「设置」：`blocked=false` |
| 现有配置 | 默认限制开启、白名单 14 个（用户自配）、目标 300 分钟（由用户确认草案后生效） |

## 三、产物

```
out/ai_usage_guard/v1.7_vivo/
├── ai-usage-guard-v1.7-vivo-debug.apk   # MD5 43bccd0b83754cb5d35ed9e9ace05a73
└── shots/  # 首页、修复后对话、待确认卡片、拦截、系统放行
```

## 四、说明

- 本次未改动用户已有配置：目标 300 是用户在应用内确认草案后的结果；默认限制、白名单保持原样。
- 历史脏消息未直接改数据库，而是在读取/展示/回放时清理；若需要物理清理可后续加一次性迁移。
