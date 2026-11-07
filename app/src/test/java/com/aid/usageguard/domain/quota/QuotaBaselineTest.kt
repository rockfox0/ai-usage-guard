package com.aid.usageguard.domain.quota

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.9.4：守护服务内部计数与数据库统计合并（只增不减，防止限额漏拦）。 */
class QuotaBaselineTest {

    @Test
    fun `总时长取较大值`() {
        assertEquals(62L, QuotaBaseline.mergeTotalMinutes(45L, 62L))
        assertEquals(45L, QuotaBaseline.mergeTotalMinutes(45L, 40L))
        assertEquals(0L, QuotaBaseline.mergeTotalMinutes(-1L, -5L))
    }

    @Test
    fun `各应用逐项取较大值且保留两边键`() {
        val merged = QuotaBaseline.mergePerApp(
            internal = mapOf("a" to 40L, "b" to 10L),
            database = mapOf("a" to 62L, "c" to 5L),
        )
        assertEquals(62L, merged["a"])
        assertEquals(10L, merged["b"])
        assertEquals(5L, merged["c"])
    }

    @Test
    fun `数据库为空时保持内部计数`() {
        val internal = mapOf("a" to 40L)
        assertEquals(internal, QuotaBaseline.mergePerApp(internal, emptyMap()))
    }

    @Test
    fun `合并不会把计数改小`() {
        val merged = QuotaBaseline.mergePerApp(mapOf("a" to 59L), mapOf("a" to 58L))
        assertEquals(59L, merged["a"])
    }
}
