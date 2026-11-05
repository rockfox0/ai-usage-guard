package com.aid.usageguard.domain.quota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.8.2：专注时段判定（支持跨午夜）。 */
class FocusWindowTest {

    @Test
    fun `普通时段内生效`() {
        assertTrue(FocusWindow.isActive(nowHour = 9, startHour = 8, endHour = 18))
        assertTrue(FocusWindow.isActive(nowHour = 8, startHour = 8, endHour = 18))
        assertFalse(FocusWindow.isActive(nowHour = 18, startHour = 8, endHour = 18))
        assertFalse(FocusWindow.isActive(nowHour = 7, startHour = 8, endHour = 18))
    }

    @Test
    fun `跨午夜时段生效`() {
        val start = 22
        val end = 7
        assertTrue(FocusWindow.isActive(nowHour = 22, startHour = start, endHour = end))
        assertTrue(FocusWindow.isActive(nowHour = 23, startHour = start, endHour = end))
        assertTrue(FocusWindow.isActive(nowHour = 0, startHour = start, endHour = end))
        assertTrue(FocusWindow.isActive(nowHour = 6, startHour = start, endHour = end))
        assertFalse(FocusWindow.isActive(nowHour = 7, startHour = start, endHour = end))
        assertFalse(FocusWindow.isActive(nowHour = 12, startHour = start, endHour = end))
    }

    @Test
    fun `起止相同视为全天生效`() {
        assertTrue(FocusWindow.isActive(nowHour = 0, startHour = 0, endHour = 0))
        assertTrue(FocusWindow.isActive(nowHour = 13, startHour = 13, endHour = 13))
    }

    @Test
    fun `越界小时被钳制`() {
        assertTrue(FocusWindow.isActive(nowHour = 23, startHour = 30, endHour = 23))
    }

    @Test
    fun `文案格式`() {
        assertTrue(FocusWindow.label(22, 7) == "22:00-07:00")
        assertTrue(FocusWindow.label(0, 6) == "00:00-06:00")
    }
}
