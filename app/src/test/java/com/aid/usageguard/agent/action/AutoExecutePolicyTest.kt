package com.aid.usageguard.agent.action

import com.aid.usageguard.agent.tools.ToolKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9.2：AI 写操作权限（每次确认 / 自动执行）与工具结果改写。 */
class AutoExecutePolicyTest {

    @Test
    fun `只有写工具会被自动执行`() {
        assertTrue(AutoExecutePolicy.isWriteTool(ToolKind.PROPOSE))
        assertTrue(AutoExecutePolicy.isWriteTool(ToolKind.CONTROL))
        assertFalse(AutoExecutePolicy.isWriteTool(ToolKind.QUERY))
    }

    @Test
    fun `每次确认模式一律不自动执行`() {
        ToolKind.entries.forEach { kind ->
            assertFalse(
                "确认模式下 $kind 不应自动执行",
                AutoExecutePolicy.shouldAutoExecute(autoExecuteWrites = false, kind = kind),
            )
        }
    }

    @Test
    fun `自动执行模式只对写工具生效`() {
        assertTrue(AutoExecutePolicy.shouldAutoExecute(true, ToolKind.PROPOSE))
        assertTrue(AutoExecutePolicy.shouldAutoExecute(true, ToolKind.CONTROL))
        assertFalse(AutoExecutePolicy.shouldAutoExecute(true, ToolKind.QUERY))
    }

    @Test
    fun `执行成功时改写为已执行`() {
        val original = JSONObject()
            .put("executed", false)
            .put("requiresConfirmation", true)
            .put("opId", "op1")
            .put("changed", JSONObject().put("dailyTarget", "360 → 350"))
            .toString()
        val rewritten = JSONObject(AutoExecutePolicy.rewriteExecuted(original, true, "每日目标已设为 350 分钟"))
        assertTrue(rewritten.getBoolean("executed"))
        assertTrue(rewritten.getBoolean("autoExecuted"))
        assertFalse(rewritten.getBoolean("requiresConfirmation"))
        assertTrue(rewritten.getBoolean("success"))
        assertEquals("op1", rewritten.getString("opId"))
        assertTrue(rewritten.getString("message").contains("已自动执行"))
    }

    @Test
    fun `执行失败时明确告知失败原因`() {
        val original = JSONObject().put("opId", "op2").put("executed", false).toString()
        val rewritten = JSONObject(
            AutoExecutePolicy.rewriteExecuted(original, false, "白名单今日添加已达上限"),
        )
        assertFalse(rewritten.getBoolean("executed"))
        assertTrue(rewritten.getString("message").contains("自动执行失败"))
        assertTrue(rewritten.getString("message").contains("上限"))
    }

    @Test
    fun `坏 JSON 原样返回不丢内容`() {
        assertEquals("not-json", AutoExecutePolicy.rewriteExecuted("not-json", true, "x"))
    }

    @Test
    fun `两种模式的提示词都存在`() {
        assertTrue(AutoExecutePolicy.AUTO_EXECUTE_HINT.contains("自动执行"))
        assertTrue(AutoExecutePolicy.AUTO_EXECUTE_HINT.contains("不要再说"))
        assertTrue(AutoExecutePolicy.CONFIRM_REQUIRED_HINT.contains("每次确认"))
    }
}
