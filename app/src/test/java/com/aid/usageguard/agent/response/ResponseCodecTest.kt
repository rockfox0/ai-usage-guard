package com.aid.usageguard.agent.response

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.10：结构化卡片持久化的编解码（重进助手页可还原）。 */
class ResponseCodecTest {

    @Test
    fun `编码后再解码得到同样的 block`() {
        val response = AgentResponse(
            title = "本周使用概览",
            blocks = listOf(
                ResponseBlock.StatisticCard("本周合计", listOf(StatItem("总时长", "12 小时 30 分钟"))),
                ResponseBlock.InsightCard("深夜使用偏多", "medium", "23 点后使用 3 次"),
                ResponseBlock.ChartBlock("daily_usage_7d", "近 7 天趋势"),
                ResponseBlock.FollowUpAction("看详情", "看本周详细数据"),
            ),
        )
        val roundTrip = ResponseCodec.decode(ResponseCodec.encode(response))!!
        assertEquals("本周使用概览", roundTrip.title)
        assertEquals(4, roundTrip.blocks.size)
        assertTrue(roundTrip.blocks[0] is ResponseBlock.StatisticCard)
        assertTrue(roundTrip.blocks[2] is ResponseBlock.ChartBlock)
        assertTrue(roundTrip.blocks[3] is ResponseBlock.FollowUpAction)
    }

    @Test
    fun `坏 JSON 返回 null 而不是崩溃`() {
        assertNull(ResponseCodec.decode(""))
        assertNull(ResponseCodec.decode("not-json"))
        assertNull(ResponseCodec.decode("""{"blocks":[{"type":"unknown"}]}"""))
        assertNull(ResponseCodec.decode(null))
    }
}
