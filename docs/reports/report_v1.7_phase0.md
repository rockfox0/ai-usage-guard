# v1.7 Phase 0 交付报告（按《UsageGuard-v1.7-升级方案》）

- 设备：Pixel 6 / Android 13（API 33），serial `<device>`
- 版本：`com.aid.usageguard` **1.7**（versionCode 8），APK 12 MB，MD5 `18020c78c05425aaeed75f1921663c0c`
- 时间：2026-11-05 18:30–18:45
- 基线提交：`806e535`（公开仓库 ai-usage-guard）

## 一、本阶段改动（对应方案 §4 / §14 Phase 0）

| 方案条目 | 实现 |
|---|---|
| 4.1 会话与每日统计 | 新增 `domain/aggregate/DailyAggregator.kt`：按本地日期对**真实会话区间**做重叠切片；跨午夜会话两天各计实际时长，启动次数按会话开始日归属（拆分不增加启动次数）；`UsageIngester` 改为「窗口重叠回看 6h + 重算受影响的连续日期」，覆盖开始于窗口前、结束于窗口内的会话（T03） |
| 4.2 数据覆盖 | 新增 `DataCoverage` / `DataStatus`（VALID/PARTIAL/NO_PERMISSION/NOT_SYNCED/FAILED）与 `UsageRepository.coverage(from,to)`：返回 requestedRange、availableRange、validDates、missingDates、lastSyncedAt、timezone，缺失日期不补零 |
| 4.3 生效策略统一 | 新增 `restriction/EffectiveRestriction.kt`（受限 + 原因：系统保护/白名单/默认限制/手动限制/未限制）；`RestrictionPolicy.effective()` 统一委托，GuardService 与后续 Agent 工具共用同一判断 |
| 4.4 评分可信度 | `UsageScore` 增加 `dataStatus` / `scoreAvailable`；`ScoreRepository` 用 coverage 判定数据状态，周平均只统计有效天；`daily_score` 落库算法版本与数据状态 |
| 4.6 对话历史 | AgentMessageDao 新增 `recentHistory(session, limit)`（`ORDER BY timestamp DESC, id DESC LIMIT`）；`AgentMemory.loadLlmHistory/history` 改为「取最近 N 条再正序」，修复「先取最旧 500 条再截尾」（T07） |
| §10 预留 | Room v3：新增 `pending_action` 表 + `ActionDraftRepository`（草案 5 分钟有效、opId 幂等、状态机）+ `ActionExecutor`（只执行已确认草案，执行后重新读取实际值核对）——供 Phase 1 使用 |
| §15.2 验证层次 | 新增 Gradle Wrapper（`./gradlew`，8.13）；新增 JVM 单元测试 11 个：跨午夜切片/窗口边界/启动次数不膨胀、有效策略 6 例 |

## 二、真机验收结果

### T22 旧版数据库升级（必测）✅

先安装 v1.6（DB v2）→ 升级 v1.7（DB v3）：

```
升级前 user_version=2   usage_session=102  app_usage_record=28
升级后 user_version=3   usage_session=102  app_usage_record=28
新增: pending_action 表；daily_score 新增 algorithmVersion / dataStatus 列
FATAL EXCEPTION 计数 = 0
```

### T01 跨午夜统计（单元测试 + 数据一致性）✅

- 单元测试：23:50→次日00:20 会话 → 两天分别 10/20 分钟，启动次数只在开始日 +1，总时长不变（`DailyAggregatorTest` 5 例全通过）
- 真机数据一致性：用 102 条真实会话独立计算「按重叠切片 + 开始日计启动」，与 `app_usage_record` 对比 **28 个 (包,日) 组合，时长偏差 0，启动次数偏差 0**
- 说明：本机现有数据中没有跨午夜会话，跨午夜路径由单元测试覆盖

### T05 默认限制模式下放行 ✅（真机端到端）

```
加入白名单前：win-change pkg=com.kugou.android blocked=true  → overlay shown
加入白名单后：win-change pkg=com.kugou.android blocked=false（allowed=5）
移出白名单后：win-change pkg=com.kugou.android blocked=true  → overlay shown
```

（通过 `scripts/ug_set_allowed.py` 直接修改 DataStore 白名单并重启服务验证；系统设置等受保护应用始终 `blocked=false`）

### T07 对话历史顺序 ✅（代码修复 + 单元可测）

`recentHistory` 直接 SQL 取最近 N 条并正序回放，不再受 500 条上限影响。

### T04 数据无效状态 ⚠️ 部分完成

模型与仓储已区分 VALID/PARTIAL/NO_PERMISSION/NOT_SYNCED；**UI 显示「暂无评分」与 AI 工具暴露 scoreAvailable 仍需在后续阶段接线**（Phase 0 剩余 UI 小项）。

## 三、未完成 / 下一阶段

| 项 | 状态 |
|---|---|
| Phase 1 任务权限矩阵 / 草案确认 / 统一预算 | 已派给 agent-runtime 实施中 |
| Phase 2 页面上下文 / 流式输出 / 统一事件 / 确认卡片 | 待 Phase 1 契约落地 |
| Phase 4 记忆与反馈、Phase 5 主动洞察 | 未开始 |
| 评分无效状态 UI/AI 展示（T04 收口） | 待接线 |
| 仪器化测试、CI | 未开始（本阶段仅 JVM 单测 + 手工真机验证） |

## 四、产物

```
out/ai_usage_guard/v1.7_phase0/
├── ai-usage-guard-v1.7-phase0-debug.apk
└── shots/   # 首页、系统放行、第三方拦截、白名单前后、v3 迁移验证
scripts/ug_set_allowed.py   # 无 root 修改白名单（自动化验证用）
AI_d/gradlew + gradle/wrapper/  # 可复现构建
AI_d/app/src/test/...       # 11 个单元测试
```

## 五、回退方式

- 源码：`git checkout 806e535`（公开仓库基线）
- 数据：DB v3 无法降级到 v2（新增列/表为增量，旧 APK 读取会因 schema 版本不匹配触发 Room 迁移失败）；回退前应备份 `usage_guard.db`。本阶段未改动既有表结构语义，删除新增表/列即可兼容 v2（脚本见实现说明）。
