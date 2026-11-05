package com.aid.usageguard.agent.runtime

import com.aid.usageguard.agent.llm.LlmMessage

/**
 * Agent 会话里的一条消息（user / assistant / tool）。
 *
 * 实现上直接复用与协议无关的 [LlmMessage]（见 agent.llm），
 * 这样运行时循环、两个 LLM 客户端和记忆模块共用同一套结构，
 * 避免在 AgentLoop 里做无意义的来回转换。
 */
typealias AgentMessage = LlmMessage
