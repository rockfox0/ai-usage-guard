package com.aid.usageguard.domain.quota

/**
 * 时长额度策略（v1.8 需求）：
 *
 * 1) 全局每日额度：今天总使用时长超过「每日目标」后，除白名单/系统保护应用外，所有应用都被拦截；
 * 2) 单应用每日限额：某应用今天的使用时长超过它自己的限额后，该应用被拦截——
 *    **与该应用是否在全局额度内无关**（即使整机还没超时）。
 *
 * 白名单与系统保护应用不受任何额度限制。
 */
enum class QuotaReason(val label: String) {
    NONE("未超额度"),
    GLOBAL_BUDGET_EXCEEDED("今日总额度已用完"),
    APP_LIMIT_EXCEEDED("该应用今日额度已用完"),
    FOCUS_WINDOW("当前是专注时段，只能用白名单应用"),
}

data class QuotaState(
    val blocked: Boolean,
    val reason: QuotaReason,
    /** 给用户看的说明，例如「今天已用 305 分钟，超出额度 300 分钟」 */
    val detail: String = "",
    /** 该应用今日已用分钟（用于 UI） */
    val appUsedMinutes: Long = 0,
    /** 该应用限额分钟；null 表示未设置 */
    val appLimitMinutes: Int? = null,
    /** 全局已用/额度分钟 */
    val totalUsedMinutes: Long = 0,
    val globalBudgetMinutes: Int = 0,
) {
    companion object {
        val NONE = QuotaState(false, QuotaReason.NONE)
    }
}

object QuotaPolicy {

    fun evaluate(
        packageName: String,
        totalUsedMinutes: Long,
        globalBudgetMinutes: Int,
        globalEnforced: Boolean,
        appUsedMinutes: Long,
        appLimitMinutes: Int?,
        whitelisted: Boolean,
        protected: Boolean,
        /** 是否处于「专注时段」（此时只有白名单应用可用） */
        focusWindowActive: Boolean = false,
        /** 专注时段文案，例如 22:00-07:00 */
        focusWindowLabel: String = "",
    ): QuotaState {
        val base = QuotaState(
            blocked = false,
            reason = QuotaReason.NONE,
            appUsedMinutes = appUsedMinutes,
            appLimitMinutes = appLimitMinutes,
            totalUsedMinutes = totalUsedMinutes,
            globalBudgetMinutes = globalBudgetMinutes,
        )
        // 系统关键应用/输入法永不受限
        if (protected) return base

        // 单应用限额是显式设置，优先于白名单：即使应用在允许名单里，超了它自己的额度也要拦
        if (appLimitMinutes != null && appLimitMinutes > 0 && appUsedMinutes >= appLimitMinutes) {
            return base.copy(
                blocked = true,
                reason = QuotaReason.APP_LIMIT_EXCEEDED,
                detail = "这个应用今天已用 ${appUsedMinutes} 分钟，达到 ${appLimitMinutes} 分钟上限",
            )
        }

        // 专注时段：只允许白名单应用（与额度无关）
        if (focusWindowActive && !whitelisted) {
            return base.copy(
                blocked = true,
                reason = QuotaReason.FOCUS_WINDOW,
                detail = "现在是专注时段（$focusWindowLabel），只能使用白名单里的应用",
            )
        }

        // 白名单应用不受全局每日额度限制
        if (whitelisted) return base

        if (globalEnforced && globalBudgetMinutes > 0 && totalUsedMinutes >= globalBudgetMinutes) {
            return base.copy(
                blocked = true,
                reason = QuotaReason.GLOBAL_BUDGET_EXCEEDED,
                detail = "今天已用 ${totalUsedMinutes} 分钟，达到每日额度 ${globalBudgetMinutes} 分钟",
            )
        }

        return base
    }

    /** 剩余分钟数（可能为 0，不会为负） */
    fun remainingMinutes(budgetMinutes: Int, usedMinutes: Long): Long =
        (budgetMinutes - usedMinutes).coerceAtLeast(0L)

    /** 该应用今日剩余分钟（未设限额返回 null） */
    fun appRemainingMinutes(limitMinutes: Int?, usedMinutes: Long): Long? =
        limitMinutes?.let { (it - usedMinutes).coerceAtLeast(0L) }
}
