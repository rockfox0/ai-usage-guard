package com.aid.usageguard.agent.memory

/**
 * 自然语言反馈识别（v1.9 Phase 3）：从用户一句话里识别「拒绝 / 偏好」信号。
 * 纯本地规则（零 token），命中后写入 FeedbackMemory，后续建议会避开同类方案。
 */
data class DetectedFeedback(
    /** REJECT / PREFER */
    val signal: String,
    /** 命中的关键词，用于摘要 */
    val topic: String,
    /** 原始理由片段 */
    val reason: String,
)

object FeedbackDetector {

    private val REJECT_MARKERS = listOf(
        "太严格", "太严", "太烦", "太频繁", "别提醒", "不要提醒", "别推荐", "不要推荐",
        "不喜欢", "不要这个", "别老", "以后别", "别再", "不合适", "没用", "烦死",
        "too strict", "too often", "don't remind", "stop reminding", "don't recommend", "annoying",
    )

    private val PREFER_MARKERS = listOf(
        "温和", "轻一点", "轻一些", "严格一点", "严格些", "关注睡眠", "关注学习", "关注工作",
        "晚上", "白天", "渐进", "慢慢来", "gentle", "mild", "focus on",
    )

    fun detect(input: String): DetectedFeedback? {
        val text = input.lowercase().trim()
        if (text.isEmpty()) return null
        val reject = REJECT_MARKERS.firstOrNull { text.contains(it) }
        if (reject != null) {
            return DetectedFeedback(signal = "REJECT", topic = reject, reason = input.take(80))
        }
        val prefer = PREFER_MARKERS.firstOrNull { text.contains(it) }
        if (prefer != null) {
            return DetectedFeedback(signal = "PREFER", topic = prefer, reason = input.take(80))
        }
        return null
    }
}
