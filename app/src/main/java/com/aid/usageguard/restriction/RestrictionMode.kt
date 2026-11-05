package com.aid.usageguard.restriction

/**
 * 限制模式（v1.8.1 修正）：
 *
 * - [QUOTA_AFTER_BUDGET]：**默认**。额度内所有应用都能用（与白名单无关）；
 *   今日总时长达到额度后，只有白名单（和系统保护应用）还能用，其他应用被拦截。
 *   白名单的唯一作用就是「超额后的通行证」。
 * - [ALWAYS_NON_WHITELIST]：始终拦截非白名单应用（旧「默认限制」行为）。
 * - [MANUAL_ONLY]：只拦截用户手动指定受限的应用。
 *
 * 单应用每日限额在任何模式下都独立生效（超了只拦它）。
 */
enum class RestrictionMode(val label: String, val description: String) {
    QUOTA_AFTER_BUDGET(
        "超额后才限制",
        "额度内所有应用都能用；今日用满额度后，只有白名单里的应用还能打开",
    ),
    ALWAYS_NON_WHITELIST(
        "始终限制非白名单",
        "不在白名单里的第三方应用一打开就拦（与额度无关）",
    ),
    MANUAL_ONLY(
        "只限制指定应用",
        "只有你手动标记为受限的应用会被拦截",
    );

    companion object {
        fun parse(raw: String?): RestrictionMode =
            entries.firstOrNull { it.name == raw } ?: QUOTA_AFTER_BUDGET

        /** 兼容旧的布尔设置：true=始终限制，false=只限制指定应用 */
        fun fromLegacy(restrictByDefault: Boolean): RestrictionMode =
            if (restrictByDefault) ALWAYS_NON_WHITELIST else MANUAL_ONLY
    }
}
