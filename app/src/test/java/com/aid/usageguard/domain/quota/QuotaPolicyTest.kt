package com.aid.usageguard.domain.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8：全局每日额度 + 单应用每日限额。
 */
class QuotaPolicyTest {

    private fun eval(
        total: Long = 0,
        budget: Int = 300,
        enforced: Boolean = true,
        appUsed: Long = 0,
        appLimit: Int? = null,
        whitelisted: Boolean = false,
        protected: Boolean = false,
        focusWindow: Boolean = false,
    ) = QuotaPolicy.evaluate(
        packageName = "com.example.app",
        totalUsedMinutes = total,
        globalBudgetMinutes = budget,
        globalEnforced = enforced,
        appUsedMinutes = appUsed,
        appLimitMinutes = appLimit,
        whitelisted = whitelisted,
        protected = protected,
        focusWindowActive = focusWindow,
        focusWindowLabel = "22:00-07:00",
    )

    @Test
    fun `额度内不拦截`() {
        val state = eval(total = 299)
        assertFalse(state.blocked)
        assertEquals(QuotaReason.NONE, state.reason)
    }

    @Test
    fun `达到每日额度后拦截`() {
        val state = eval(total = 300)
        assertTrue(state.blocked)
        assertEquals(QuotaReason.GLOBAL_BUDGET_EXCEEDED, state.reason)
    }

    @Test
    fun `关闭全局额度执行后不拦截`() {
        val state = eval(total = 999, enforced = false)
        assertFalse(state.blocked)
    }

    @Test
    fun `白名单应用不受全局额度限制`() {
        assertFalse(eval(total = 999, whitelisted = true).blocked)
    }

    @Test
    fun `系统保护应用不受任何额度限制`() {
        assertFalse(eval(total = 999, appLimit = 1, appUsed = 60, protected = true).blocked)
    }

    @Test
    fun `单应用限额优先于白名单`() {
        // 显式给白名单应用设了每日限额，超了也要拦（用户明确意图优先）
        val state = eval(total = 10, appUsed = 30, appLimit = 30, whitelisted = true)
        assertTrue(state.blocked)
        assertEquals(QuotaReason.APP_LIMIT_EXCEEDED, state.reason)
    }

    @Test
    fun `单应用限额即使全局未超时也会拦截`() {
        // 全局只用了 30 分钟，但该应用限额 20 分钟、已用 25 分钟
        val state = eval(total = 30, appUsed = 25, appLimit = 20)
        assertTrue(state.blocked)
        assertEquals(QuotaReason.APP_LIMIT_EXCEEDED, state.reason)
    }

    @Test
    fun `单应用未超限额时继续可用`() {
        val state = eval(total = 30, appUsed = 19, appLimit = 20)
        assertFalse(state.blocked)
    }

    @Test
    fun `单应用限额为零视为未设置`() {
        val state = eval(total = 10, appUsed = 100, appLimit = 0)
        assertFalse(state.blocked)
    }

    @Test
    fun `全局额度优先于未超限的单应用`() {
        val state = eval(total = 305, appUsed = 5, appLimit = 60)
        assertTrue(state.blocked)
        assertEquals(QuotaReason.GLOBAL_BUDGET_EXCEEDED, state.reason)
    }

    @Test
    fun `剩余分钟不会为负数`() {
        assertEquals(0L, QuotaPolicy.remainingMinutes(300, 400))
        assertEquals(120L, QuotaPolicy.remainingMinutes(300, 180))
        assertEquals(0L, QuotaPolicy.appRemainingMinutes(30, 45))
    }

    @Test
    fun `专注时段内即使额度未超也要拦非白名单`() {
        val state = eval(total = 10, budget = 300, focusWindow = true)
        assertTrue(state.blocked)
        assertEquals(QuotaReason.FOCUS_WINDOW, state.reason)
    }

    @Test
    fun `专注时段内白名单与系统保护应用不受限`() {
        assertFalse(eval(total = 10, focusWindow = true, whitelisted = true).blocked)
        assertFalse(eval(total = 10, focusWindow = true, protected = true).blocked)
    }

    @Test
    fun `专注时段内单应用限额仍然生效`() {
        val state = eval(total = 10, appUsed = 30, appLimit = 30, whitelisted = true, focusWindow = true)
        assertTrue(state.blocked)
        assertEquals(QuotaReason.APP_LIMIT_EXCEEDED, state.reason)
    }
}
