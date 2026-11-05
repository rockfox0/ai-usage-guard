package com.aid.usageguard.restriction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 方案 4.3 / T05：统一的有效限制策略。
 */
class EffectiveRestrictionTest {

    private val protectedPkgs = setOf("com.android.settings", "com.android.dialer")
    private val launcher = setOf("com.google.android.apps.nexuslauncher")

    @Test
    fun `系统保护应用永远不受限`() {
        val state = EffectiveRestrictionResolver.resolve(
            packageName = "com.android.dialer",
            allowedPackages = emptySet(),
            explicitBlocked = setOf("com.android.dialer"),
            restrictByDefault = true,
            protectedPackages = protectedPkgs,
        )
        assertFalse(state.restricted)
        assertEquals(RestrictionReason.PROTECTED, state.reason)
    }

    @Test
    fun `桌面启动器受保护`() {
        val state = EffectiveRestrictionResolver.resolve(
            "com.google.android.apps.nexuslauncher", emptySet(), emptySet(), true, launcher,
        )
        assertFalse(state.restricted)
        assertEquals(RestrictionReason.PROTECTED, state.reason)
    }

    @Test
    fun `白名单优先于默认限制和手动限制`() {
        val state = EffectiveRestrictionResolver.resolve(
            packageName = "com.tencent.mm",
            allowedPackages = setOf("com.tencent.mm"),
            explicitBlocked = setOf("com.tencent.mm"),
            restrictByDefault = true,
            protectedPackages = emptySet(),
        )
        assertFalse(state.restricted)
        assertEquals(RestrictionReason.WHITELISTED, state.reason)
    }

    @Test
    fun `默认限制模式下未列白名单即受限`() {
        val state = EffectiveRestrictionResolver.resolve(
            "com.example.app", emptySet(), emptySet(), true, emptySet(),
        )
        assertTrue(state.restricted)
        assertEquals(RestrictionReason.DEFAULT_RESTRICTED, state.reason)
    }

    @Test
    fun `指定限制模式下只有手动限制生效`() {
        val allowedApp = EffectiveRestrictionResolver.resolve(
            "com.example.a", emptySet(), emptySet(), false, emptySet(),
        )
        assertFalse(allowedApp.restricted)

        val blockedApp = EffectiveRestrictionResolver.resolve(
            "com.example.b", emptySet(), setOf("com.example.b"), false, emptySet(),
        )
        assertTrue(blockedApp.restricted)
        assertEquals(RestrictionReason.MANUALLY_RESTRICTED, blockedApp.reason)
    }

    @Test
    fun `默认限制模式下放行后即使仍有手动限制也视为允许`() {
        // 「确认放行」应同时清理手动限制；即使清理失败，白名单也必须优先
        val state = EffectiveRestrictionResolver.resolve(
            "com.example.c",
            allowedPackages = setOf("com.example.c"),
            explicitBlocked = setOf("com.example.c"),
            restrictByDefault = true,
            protectedPackages = emptySet(),
        )
        assertFalse(state.restricted)
    }
}
