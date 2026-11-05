package com.aid.usageguard.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.9 Phase 3：从自然语言里识别反馈信号（零 token，本地规则）。 */
class FeedbackDetectorTest {

    @Test
    fun `拒绝信号被识别`() {
        assertEquals("REJECT", FeedbackDetector.detect("这个建议太严格了，不要提醒我")?.signal)
        assertEquals("REJECT", FeedbackDetector.detect("以后别再推荐这个方案")?.signal)
        assertEquals("REJECT", FeedbackDetector.detect("too strict, don't remind me")?.signal)
    }

    @Test
    fun `偏好信号被识别`() {
        assertEquals("PREFER", FeedbackDetector.detect("我更喜欢温和一点的提醒")?.signal)
        assertEquals("PREFER", FeedbackDetector.detect("能不能慢慢来，渐进一点")?.signal)
        assertEquals("PREFER", FeedbackDetector.detect("希望关注睡眠的情况")?.signal)
    }

    @Test
    fun `普通问题不产生反馈`() {
        assertNull(FeedbackDetector.detect("今天用了多久？"))
        assertNull(FeedbackDetector.detect("帮我分析最近一周的使用"))
        assertNull(FeedbackDetector.detect("   "))
    }

    @Test
    fun `中文关键词覆盖英文输入`() {
        val detected = FeedbackDetector.detect("stop reminding me, this is annoying")
        assertEquals("REJECT", detected?.signal)
        assertEquals(true, detected?.topic?.isNotBlank())
    }
}
