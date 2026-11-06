package com.aid.usageguard.restriction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9.1 回归：手动限制列表只在 MANUAL_ONLY 生效。
 * 事故背景：用户在「超额后才限制」模式下，哔哩哔哩因历史手动限制行被无条件拦截。
 */
class RestrictionModeGateTest {

    private val manual = setOf("tv.danmaku.bili", "com.ss.android.ugc.aweme")

    @Test
    fun `超额后才限制模式忽略手动名单`() {
        assertTrue(
            RestrictionPolicy.manualBlocksFor(RestrictionMode.QUOTA_AFTER_BUDGET, manual).isEmpty(),
        )
    }

    @Test
    fun `始终限制非白名单模式忽略手动名单`() {
        assertTrue(
            RestrictionPolicy.manualBlocksFor(RestrictionMode.ALWAYS_NON_WHITELIST, manual).isEmpty(),
        )
    }

    @Test
    fun `只限制指定应用模式保留手动名单`() {
        assertEquals(manual, RestrictionPolicy.manualBlocksFor(RestrictionMode.MANUAL_ONLY, manual))
    }

    @Test
    fun `额度模式下手动名单里的应用不被策略拦截`() {
        val active = RestrictionPolicy.manualBlocksFor(RestrictionMode.QUOTA_AFTER_BUDGET, manual)
        assertFalse(
            RestrictionPolicy.isRestricted(
                packageName = "tv.danmaku.bili",
                allowedPackages = emptySet(),
                explicitBlocked = active,
                restrictByDefault = false,
                protectedPackages = emptySet(),
            ),
        )
    }

    @Test
    fun `手动模式下手动名单里的应用被拦截`() {
        val active = RestrictionPolicy.manualBlocksFor(RestrictionMode.MANUAL_ONLY, manual)
        assertTrue(
            RestrictionPolicy.isRestricted(
                packageName = "tv.danmaku.bili",
                allowedPackages = emptySet(),
                explicitBlocked = active,
                restrictByDefault = false,
                protectedPackages = emptySet(),
            ),
        )
    }

    @Test
    fun `白名单在任何模式下都豁免`() {
        RestrictionMode.entries.forEach { mode ->
            val active = RestrictionPolicy.manualBlocksFor(mode, manual)
            assertFalse(
                "${mode.name} 下白名单应豁免",
                RestrictionPolicy.isRestricted(
                    packageName = "com.tencent.mm",
                    allowedPackages = setOf("com.tencent.mm"),
                    explicitBlocked = active,
                    restrictByDefault = true,
                    protectedPackages = emptySet(),
                ),
            )
        }
    }
}
