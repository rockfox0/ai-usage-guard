package com.aid.usageguard.agent.capability

/**
 * 能力目录（v1.9 Phase 1）。
 *
 * 约束（由单测保证）：
 * 1. 每个已注册工具**必须且只能**属于一个能力，否则工具会永远不可达；
 * 2. 能力 id 唯一；
 * 3. 目录只做分组与描述，不影响 ToolKind / TaskScope / 草案确认 / 预算。
 */
object CapabilityCatalog {

    val capabilities: List<CapabilityDescriptor> = listOf(
        CapabilityDescriptor(
            id = "usage_analysis",
            name = "使用分析",
            description = "查询手机/单个应用的使用时长、排行、30 天趋势与行为事件",
            category = "analysis",
            toolNames = listOf(
                "get_daily_usage",
                "get_weekly_usage",
                "get_usage_ranking",
                "get_app_usage_history",
                "get_usage_trend",
                "get_behavior_events",
            ),
            examples = listOf(
                "今天用了多久？",
                "哪个 App 用得最多？",
                "最近 30 天使用趋势怎么样？",
                "我最近是不是总是在不同 App 间切换？",
            ),
            keywords = listOf(
                "使用", "用时", "时长", "多久", "统计", "排行", "排名", "趋势", "习惯", "切换",
                "usage", "minutes", "ranking", "trend", "history", "habit",
            ),
        ),
        CapabilityDescriptor(
            id = "score_review",
            name = "评分复盘",
            description = "读取今日/历史使用评分、等级与判断词条",
            category = "analysis",
            toolNames = listOf("get_usage_score"),
            examples = listOf("我今天多少分？", "昨天评分为什么低？", "本周平均分怎么样？"),
            keywords = listOf("评分", "打分", "分数", "等级", "score", "grade"),
        ),
        CapabilityDescriptor(
            id = "quota_control",
            name = "额度管理",
            description = "查看/调整每日额度、单应用每日限额与超额拦截开关",
            category = "control",
            toolNames = listOf(
                "get_quota_status",
                "set_daily_target",
                "set_daily_budget_enforced",
                "set_app_daily_limit",
            ),
            examples = listOf(
                "今天还剩多少额度？",
                "明天起每天只能用 5 小时",
                "给微信设每天 30 分钟限额",
            ),
            keywords = listOf(
                "额度", "目标", "限额", "上限", "超额", "预算", "剩余",
                "quota", "budget", "target", "limit", "remaining",
            ),
        ),
        CapabilityDescriptor(
            id = "restriction_control",
            name = "限制与白名单",
            description = "查看/修改限制模式、白名单、单应用放行与限制建议",
            category = "control",
            toolNames = listOf(
                "get_whitelist",
                "set_whitelist",
                "set_whitelist_daily_limit",
                "set_app_restriction",
                "propose_restriction",
                "set_restriction_mode",
                "set_restrict_mode",
            ),
            examples = listOf(
                "把微信加入白名单",
                "以后只限制指定的应用",
                "王者荣耀为什么打不开？",
            ),
            keywords = listOf(
                "白名单", "限制", "放行", "拦截", "禁止", "允许", "受限", "打不开",
                "whitelist", "restrict", "block", "allow", "forbid",
            ),
        ),
        CapabilityDescriptor(
            id = "focus_control",
            name = "专注时段与提醒",
            description = "设置专注时段（只允许白名单）、实时提示与免打扰时段",
            category = "control",
            toolNames = listOf("set_focus_window", "set_nudge", "set_quiet_hours"),
            examples = listOf(
                "晚上 10 点到早上 7 点只能用白名单",
                "连续用 20 分钟提醒我一次",
                "23 点后别打扰我",
            ),
            keywords = listOf(
                "专注", "时段", "免打扰", "勿扰", "提醒", "提示", "夜间",
                "focus", "quiet", "nudge", "reminder", "night",
            ),
        ),
        CapabilityDescriptor(
            id = "notification_control",
            name = "通知设置",
            description = "开关通知、选择通知渠道、提醒风格与测试通知",
            category = "config",
            toolNames = listOf(
                "set_notification_enabled",
                "set_notification_channels",
                "set_reminder_style",
                "send_test_notification",
            ),
            examples = listOf("关掉通知", "只保留超目标提醒", "发一条测试通知"),
            keywords = listOf("通知", "渠道", "提醒方式", "推送", "notification", "channel", "push"),
        ),
        CapabilityDescriptor(
            id = "persona_settings",
            name = "AI 个性化",
            description = "称呼、语气、关注点、自定义要求与 AI 分析开关",
            category = "config",
            toolNames = listOf("get_user_preferences", "set_ai_persona", "set_ai_analysis_enabled"),
            examples = listOf("叫我老张", "语气温和一点", "多关注睡眠"),
            keywords = listOf(
                "称呼", "语气", "人设", "个性化", "偏好", "性格",
                "persona", "tone", "nickname", "preference",
            ),
        ),
        CapabilityDescriptor(
            id = "report_management",
            name = "报告与待确认操作",
            description = "生成日报/周报、立即生成报告、查看待确认操作",
            category = "report",
            toolNames = listOf("generate_usage_report", "generate_report_now", "get_pending_actions"),
            examples = listOf("给我一份周报", "现在生成报告", "我还有什么待确认的操作？"),
            keywords = listOf("报告", "日报", "周报", "待确认", "草案", "report", "pending", "draft"),
        ),
        CapabilityDescriptor(
            id = "proactive_insight",
            name = "主动洞察与功能提案",
            description = "查看 AI 主动分析出的使用洞察；把用户想要的新功能整理成结构化提案",
            category = "analysis",
            toolNames = listOf(
                "get_proactive_insights",
                "propose_feature",
                "list_feature_proposals",
            ),
            examples = listOf(
                "最近有什么值得注意的变化？",
                "你觉得这个 App 还缺什么功能？",
                "你之前给过哪些功能建议？",
            ),
            keywords = listOf(
                "洞察", "主动", "建议", "缺什么", "新功能", "提案",
                "insight", "proactive", "feature", "suggest",
            ),
        ),
        CapabilityDescriptor(
            id = "monitor_management",
            name = "自定义监控",
            description = "用自然语言创建/查看/删除监控规则（指定应用、阈值、工作日/周末），由本地引擎定时判断并通知",
            category = "control",
            toolNames = listOf(
                "create_monitor",
                "list_monitors",
                "remove_monitor",
                "monitor_history",
            ),
            examples = listOf(
                "工作日微信超过 3 小时提醒我",
                "周末玩游戏超过 2 小时提醒我",
                "我现在有哪些监控规则？",
            ),
            keywords = listOf(
                "监控", "规则", "提醒我", "超过", "低于", "达到", "定时",
                "monitor", "rule", "alert", "threshold",
            ),
        ),
        CapabilityDescriptor(
            id = "memory_management",
            name = "长期记忆",
            description = "记住用户偏好、长期目标与对建议的反馈，用于调整后续建议方式",
            category = "config",
            toolNames = listOf(
                "save_memory",
                "list_memory",
                "save_goal",
                "list_goals",
                "record_feedback",
            ),
            examples = listOf(
                "记住我更喜欢温和一点的提醒",
                "我的目标是晚上少玩游戏",
                "以后别再推荐这种方案了",
            ),
            keywords = listOf(
                "记住", "记忆", "偏好", "目标", "别再", "反馈", "习惯我",
                "remember", "memory", "preference", "goal", "feedback",
            ),
        ),
        CapabilityDescriptor(
            id = "system_maintenance",
            name = "系统设置与数据",
            description = "查看当前设置、刷新使用数据、切换主题",
            category = "config",
            toolNames = listOf("get_app_settings", "refresh_usage_data", "set_theme_mode"),
            examples = listOf("现在是什么设置？", "刷新一下数据", "换成深色模式"),
            keywords = listOf(
                "设置", "主题", "刷新", "同步", "权限", "深色", "浅色",
                "settings", "theme", "refresh", "sync", "permission",
            ),
        ),
    )

    /** 工具名 → 能力 id（单测保证 34 个工具全覆盖、无重复） */
    private val toolToCapability: Map<String, String> =
        capabilities.flatMap { cap -> cap.toolNames.map { it to cap.id } }
            .toMap()
            .also { map ->
                val total = capabilities.sumOf { it.toolNames.size }
                require(map.size == total) { "工具被重复归入多个能力：${map.size} != $total" }
            }

    private val byId: Map<String, CapabilityDescriptor> = capabilities.associateBy { it.id }

    val ids: List<String> get() = capabilities.map { it.id }

    fun byId(id: String): CapabilityDescriptor? = byId[id]

    fun capabilityOf(toolName: String): String? = toolToCapability[toolName]

    fun toolsOf(id: String): List<String> = byId[id]?.toolNames ?: emptyList()

    /** 未归类工具（正常应为空，供启动自检/单测使用） */
    fun unknownTools(registered: Collection<String>): List<String> =
        registered.filter { it !in toolToCapability }
}
