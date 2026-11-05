# 项目开发任务：AI Digital Wellbeing —— 主动式 AI 手机使用管理应用

你是一名资深 Android 工程师、Android Framework 开发者和 AI Agent 架构工程师。

请帮助我从零开发一个 Android 应用：**AI Digital Wellbeing（AI 数字健康助手）**。

这不是一个简单的手机使用时间统计器，而是一个具备主动监控、行为分析、AI 总结和个性化建议能力的 Android Agent 应用。

你的任务是完成第一版可运行、可测试、架构清晰的 MVP，而不是只生成项目结构、示例代码或静态 UI。

---

# 一、项目目标

开发一个能够了解用户手机使用情况，并通过 AI 主动帮助用户改善手机使用习惯的 Android 应用。

第一版需要实现以下核心能力：

1. 统计手机中各个 App 的使用时间。
2. 记录和分析 App 的前后台使用行为。
3. 定期检测异常使用模式。
4. 接入 LLM，生成每日使用情况总结。
5. 实现 Agent Tool Calling，使 AI 能够主动查询手机使用数据。
6. 实现主动提醒机制。
7. 为后续 App 使用限制功能预留完整的架构接口。

核心设计理念：

**Android 本地系统负责数据采集、行为检测和确定性任务，AI Agent 负责理解、分析、总结和提出建议。**

不要让 LLM 直接负责实时监控或执行系统权限操作。

---

# 二、技术栈

优先采用以下技术：

- 开发语言：Kotlin
- UI：Jetpack Compose
- UI 设计：Material 3
- 架构：MVVM + Repository
- 异步处理：Kotlin Coroutines + Flow
- 数据库：Room
- 用户配置：DataStore
- 网络请求：Retrofit + OkHttp
- 定时任务：WorkManager
- AI：DeepSeek API
- Agent：自行实现 Kotlin Agent Runtime + Function Calling
- 使用统计：UsageStatsManager + UsageEvents
- 通知：NotificationManager

优先采用 Android 官方 API，不要引入不必要的第三方框架。

在开始开发前，检查当前项目的 Gradle、Kotlin、AGP、SDK 和依赖版本，选择相互兼容的版本，不要随意升级整个工程。

如果项目已经存在，先分析现有代码，不要直接覆盖或重建项目。

---

# 三、核心功能模块

## 1. 手机使用情况监控模块

使用 Android 官方 UsageStatsManager 获取应用使用数据。

需要实现：

- 查询手机中各 App 的使用时间。
- 获取 App 最近一次使用时间。
- 获取指定日期的使用情况。
- 获取最近 7 天的使用情况。
- 获取 App 使用排行榜。
- 获取每天的总使用时长。
- 通过 UsageEvents 分析前后台切换。
- 计算 App 连续使用时间。
- 处理跨天、锁屏、解锁和应用切换等情况。

权限：

PACKAGE_USAGE_STATS

引导用户前往系统设置手动授予 Usage Access。

重点：

不要只依赖 queryUsageStats() 的聚合结果计算实时使用限额。

应当合理结合 UsageEvents 和本地使用区间记录。

需要考虑：

- UsageEvents 数据可能存在延迟。
- 不同 Android 版本的事件类型可能不同。
- 查询区间边界问题。
- 手机重启后的数据恢复。
- 系统时间和时区变化。

## 2. 本地数据存储模块

使用 Room 设计数据库。

至少包含以下数据表：

### AppInfo

保存应用基本信息：

- packageName
- appName
- icon 信息或图标加载方式
- firstInstallTime
- category（可选）

### UsageSession

保存应用使用区间：

- id
- packageName
- startTime
- endTime
- duration
- date

### AppUsageRecord

保存每日聚合数据：

- id
- packageName
- date
- totalDuration
- launchCount
- lastUsedTime

### BehaviorEvent

保存行为分析结果：

- id
- eventType
- packageName
- severity
- description
- timestamp

### UserPreference

保存用户偏好：

- dailyUsageTarget
- quietHoursStart
- quietHoursEnd
- notificationEnabled
- aiAnalysisEnabled
- preferredReminderStyle

### AgentReport

保存 AI 分析报告：

- id
- reportType
- content
- createdAt
- relatedDate

### RestrictionRule

为后续版本预留：

- id
- packageName
- dailyLimitMinutes
- blockedStartTime
- blockedEndTime
- enabled

数据库需要支持历史数据查询、分页和合理的索引。

不要每次打开页面都重新扫描全部历史数据。

## 3. 首页 Dashboard

使用 Jetpack Compose 实现现代化、简洁的数字健康仪表盘。

首页展示：

- 今日手机总使用时间。
- 今日使用目标及完成比例。
- 使用时间最长的 5 个 App。
- 各 App 使用时间分布。
- 最近 7 天使用趋势。
- 今日异常行为数量。
- AI 今日总结入口。
- Agent 当前状态。

提供合理的数据可视化。

要求：

- 使用 Material 3。
- 页面支持深色模式。
- 支持加载、空数据和错误状态。
- 图表数据必须来自真实统计结果。
- 不要使用伪造的统计数据作为正式功能。

## 4. App 使用详情

用户点击某个 App 后，进入详情页面。

展示：

- App 名称和图标。
- 今日使用时间。
- 最近 7 天使用时间。
- 每日平均使用时间。
- 启动次数。
- 最近使用时间。
- 使用时间分布。

后续可以在此页面增加单独的 App 限制设置。

## 5. 本地行为分析引擎

实现 BehaviorAnalyzer。

不需要调用 LLM，通过 Kotlin 本地算法分析使用数据。

第一版实现以下检测：

1. 某 App 今日使用时间明显超过最近 7 天平均值。
2. 某 App 连续使用时间过长。
3. 深夜使用时间过长。
4. 短时间内频繁打开同一个 App。
5. 手机总使用时间超过用户目标。
6. App 使用时间连续多日增长。

每种检测结果生成结构化 BehaviorEvent。

例如：

{
  "eventType": "USAGE_INCREASE",
  "packageName": "com.example.app",
  "severity": "MEDIUM",
  "description": "今日使用时间比近期平均水平明显增加"
}

检测参数应该可配置。

注意：

- 不要将正常使用行为直接认定为沉迷。
- 需要考虑个人历史基线。
- 避免同一个异常反复生成。
- 对异常设置冷却时间。
- 允许用户关闭某些类型的提醒。

## 6. AI Agent 模块

这是项目的核心特色。

实现一个独立的 Agent 模块，而不是把 LLM 请求直接写进 Activity 或 ViewModel。

目录建议：

agent/
├── runtime/
│   ├── AgentLoop.kt
│   ├── AgentState.kt
│   └── AgentMessage.kt
├── llm/
│   ├── LLMClient.kt
│   └── DeepSeekClient.kt
├── tools/
│   ├── AgentTool.kt
│   ├── ToolRegistry.kt
│   ├── UsageTools.kt
│   ├── BehaviorTools.kt
│   └── ReportTools.kt
├── executor/
│   └── ToolExecutor.kt
└── memory/
    ├── AgentMemory.kt
    └── MemoryRepository.kt

### Agent 能力

第一版至少支持：

1. 查询今日使用情况。
2. 查询最近 7 天使用情况。
3. 查询 App 使用排行榜。
4. 查询异常行为。
5. 获取用户使用目标。
6. 生成每日使用总结。
7. 生成个性化使用建议。

### Agent Tools

定义以下工具：

get_daily_usage

获取今日各 App 使用时间。

get_weekly_usage

获取最近 7 天的使用情况。

get_usage_ranking

获取 App 使用排行榜。

get_app_usage_history

查询指定 App 的历史使用情况。

get_behavior_events

获取最近检测到的异常行为。

get_user_preferences

获取用户设置和使用目标。

generate_usage_report

生成使用情况分析报告。

### Agent Loop

实现完整的 Function Calling 执行流程：

1. 用户输入自然语言。
2. 将用户问题、相关上下文和 Tool Schema 发送给 LLM。
3. 解析模型返回的 Tool Calls。
4. 通过 ToolRegistry 找到对应工具。
5. 执行本地 Kotlin 函数。
6. 将工具执行结果返回给 LLM。
7. 模型继续分析或调用其他工具。
8. 返回最终回答。

要求：

- 支持一次任务中多次调用工具。
- 支持工具调用次数上限。
- 支持请求超时和网络错误。
- 支持 JSON 参数验证。
- 支持 Agent 任务取消。
- 支持对话历史管理。
- 防止无限 Agent Loop。
- 不允许模型执行任意 Kotlin 代码或 Shell 命令。

LLM API Key 通过安全配置管理，不要硬编码在源码中。正式发布版本应考虑通过自己的后端代理请求模型。

## 7. 主动管理系统

实现 AgentScheduler 和 ProactiveAnalyzer。

采用：

事件驱动 + 本地规则检测 + WorkManager 定时任务 + AI 按需分析。

### 定时任务

手机使用数据同步：

建议周期：15～30 分钟。

异常行为检测：

建议周期：30 分钟左右，必要时由事件触发。

每日 AI 总结：

默认每天晚上 22:30。

每周总结：

默认每周日晚上。

以上时间都允许用户调整。

注意 Android 后台调度存在延迟，不能假设 WorkManager 一定准时执行。

不要通过高频轮询实现实时监控。

### 主动分析流程

UsageStats / UsageEvents
        ↓
UsageEventProcessor
        ↓
更新 Room 数据
        ↓
BehaviorAnalyzer
        ↓
检查是否存在值得关注的异常
        ↓
生成 AgentTask
        ↓
AgentRuntime
        ↓
LLM 分析
        ↓
生成报告和建议
        ↓
NotificationManager 通知用户

本地没有发现重要事件时，不调用 LLM。

需要实现：

- 任务去重。
- 任务状态管理。
- 请求失败重试。
- AI 调用频率控制。
- 通知冷却时间。
- 后台任务日志。
- 断网时的本地降级处理。

## 8. 通知模块

实现以下通知：

- 每日使用时间提醒。
- 超过使用目标提醒。
- 异常使用行为提醒。
- AI 每日总结。
- 每周总结。

通知必须允许用户分别开启和关闭。

避免重复通知。

Android 13 及以上版本需要正确处理通知运行时权限。

---

# 四、UI 页面设计

第一版至少包含以下页面：

1. Dashboard：手机使用情况总览。
2. App Usage：所有 App 使用时间列表。
3. App Detail：单个 App 使用详情。
4. AI Assistant：与 Agent 聊天。
5. AI Report：每日和每周总结。
6. Behavior Events：异常行为记录。
7. Settings：权限、统计目标、AI 配置、提醒设置。

底部导航建议：

首页 | 应用 | AI 助手 | 报告 | 设置

UI 要求：

- 简洁、现代。
- 信息密度合理。
- 使用 Material 3。
- 深色模式。
- 不要过度堆砌卡片。
- 重要数据优先展示。
- 页面状态真实，不要使用大量占位内容。

---

# 五、限制功能的架构预留

第一版暂时不实现 Device Owner，也不实现 Root。

但是必须预留统一的限制接口：

interface AppRestrictionController {
    suspend fun block(packageName: String)
    suspend fun unblock(packageName: String)
    suspend fun isBlocked(packageName: String): Boolean
}

未来提供两种实现：

AccessibilityRestrictionController

DeviceOwnerRestrictionController

限制规则与执行方式分离。

不要让 Agent 直接调用 AccessibilityService 或 DevicePolicyManager。

Agent 只能创建限制建议，真正的规则执行必须经过本地 RuleValidator 和用户授权流程。

---

# 六、隐私和安全要求

这是一个涉及个人手机使用行为的应用，必须特别注意：

1. 使用数据优先保存在本地。
2. 只向 LLM 发送完成分析所必需的聚合数据。
3. 不上传完整 UsageEvents 原始记录。
4. 不收集其他 App 的聊天内容和输入内容。
5. 用户可以关闭 AI 分析。
6. 用户可以删除历史统计和 AI 报告。
7. AI 不能绕过用户授权执行限制操作。
8. 不能将电话、系统设置、启动器等关键应用默认纳入限制范围。
9. 网络请求失败时，统计功能仍然可用。
10. 对敏感数据提供明确的用户告知和控制。

---

# 七、开发实施要求

不要一次性生成整个项目后就结束。

请按照以下阶段执行。

## Phase 1：项目基础

- 检查现有工程。
- 建立模块结构。
- 配置 Gradle。
- 配置 Compose。
- 建立 Room 数据库。
- 建立 Repository。

完成后执行 Gradle 构建。

## Phase 2：使用统计

- 实现 Usage Access 权限引导。
- 实现 UsageStatsManager。
- 实现 UsageEvents。
- 实现每日统计。
- 实现最近 7 天统计。
- 实现 App 排行榜。
- 将数据保存到 Room。

完成后使用真实设备或模拟器测试。

## Phase 3：Dashboard

- 实现首页。
- 实现 App 列表。
- 实现 App 详情。
- 实现趋势图。
- 实现空数据和异常状态。

## Phase 4：主动分析

- 实现 BehaviorAnalyzer。
- 实现异常事件记录。
- 实现 WorkManager。
- 实现通知。
- 实现任务去重和恢复。

## Phase 5：AI Agent

- 实现 DeepSeekClient。
- 实现 ToolRegistry。
- 实现 ToolExecutor。
- 实现 AgentLoop。
- 实现 Function Calling。
- 实现 AI 日报和周报。
- 实现 Agent 聊天页面。

## Phase 6：集成测试

检查完整数据流：

Android 系统
    ↓
UsageStats / UsageEvents
    ↓
Room
    ↓
BehaviorAnalyzer
    ↓
AgentScheduler
    ↓
AgentRuntime
    ↓
DeepSeek
    ↓
AI Report
    ↓
Notification

确保每一层都真正连接，而不是只有独立的示例代码。

---

# 八、代码质量要求

1. 遵循 Kotlin 编码规范。
2. 使用合理的接口抽象。
3. 不要将所有业务代码写在 Activity 中。
4. 不要在主线程执行网络请求或耗时数据库操作。
5. 使用 Coroutines 和 Flow。
6. Repository 负责数据访问。
7. ViewModel 负责 UI 状态。
8. Agent Runtime 与 Android UI 解耦。
9. 系统权限处理必须有明确的状态管理。
10. 关键业务逻辑必须编写单元测试。

特别要求：

不要为了追求架构复杂度而过度设计。

第一版采用模块化单体架构即可，不需要微服务，也不需要多 Agent。

---

# 九、验收标准

第一版必须满足：

1. 可以在 Android 手机上成功安装。
2. 可以引导用户授予 Usage Access。
3. 可以读取真实 App 使用时长。
4. 可以展示今日使用排行榜。
5. 可以查看最近 7 天的使用情况。
6. 可以保存历史使用数据。
7. 可以检测至少三种异常使用行为。
8. 可以通过 WorkManager 执行后台分析任务。
9. 可以调用 DeepSeek API。
10. Agent 可以成功调用至少三个本地 Tools。
11. 可以生成真实数据驱动的 AI 日报。
12. 可以主动发送使用情况提醒。
13. 网络断开时，基础统计功能仍然可用。
14. 应用重启后，历史统计和任务状态可以恢复。

不要将未实现的功能标记为已完成。

---

# 十、你的工作方式

在开发过程中，请遵循以下流程：

第一步：检查项目现状，了解工程结构和已有代码。

第二步：制定实施计划，明确模块依赖和数据流。

第三步：先完成可以运行的最小版本，再逐步增加功能。

第四步：每完成一个阶段，都执行 Gradle 构建和必要测试。

第五步：遇到 API 不确定、Android 版本兼容性问题时，优先查阅 Android 官方文档和源码，不要凭空编造 API。

第六步：遇到问题先分析调用链和根本原因，不要通过大量无关修改掩盖问题。

第七步：每次完成修改后，报告：
- 修改了哪些文件。
- 实现了哪些功能。
- 使用了哪些关键 API。
- 执行了哪些测试。
- 哪些功能仍未完成。
- 存在哪些兼容性或运行时限制。

现在请先检查当前项目，输出工程结构、技术环境和实施计划，然后开始 Phase 1。

不要只给我开发建议，我需要你实际修改代码、构建项目，并逐步交付一个可以运行的 Android 应用。