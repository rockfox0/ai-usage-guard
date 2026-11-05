package com.aid.usageguard.domain.behavior

import com.aid.usageguard.domain.model.BehaviorEventModel

/**
 * 本地行为分析引擎契约。
 * 实现只做「基于历史基线的规则检测」，不调用 LLM；
 * 去重/冷却由 BehaviorRepository 统一处理。
 */
interface BehaviorAnalyzerContract {
    /** 分析某一天，返回当次检测出的候选事件 */
    suspend fun analyze(date: String): List<BehaviorEventModel>
}

/** 占位：真实分析器接入前不产生事件 */
class StubBehaviorAnalyzer : BehaviorAnalyzerContract {
    override suspend fun analyze(date: String): List<BehaviorEventModel> = emptyList()
}
