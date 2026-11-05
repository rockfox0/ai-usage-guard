package com.aid.usageguard.ui.behavior

import androidx.lifecycle.ViewModel
import com.aid.usageguard.data.repo.BehaviorRepository
import com.aid.usageguard.domain.model.BehaviorEventModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 行为页 UI 状态 */
data class BehaviorUiState(
    val loading: Boolean = true,
    val analyzing: Boolean = false,
    val events: List<BehaviorEventModel> = emptyList(),
    /** 今日事件条数（与时间线上的全部记录区分） */
    val todayCount: Int = 0,
    val lastRunAt: Long = 0L,
    val message: String? = null,
)

/**
 * 行为分析页 ViewModel。
 *
 * 事件时间线直接跟随 [BehaviorRepository.observeRecent]（Room Flow），
 * 所以 Worker 在后台分析出的事件也会自动出现在页面上。
 */
class BehaviorViewModel(
    private val behavior: BehaviorRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BehaviorUiState())
    val state: StateFlow<BehaviorUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            behavior.observeRecent(OBSERVE_LIMIT).collect { events ->
                _state.update { it.copy(loading = false, events = events) }
            }
        }
        scope.launch { refreshCount() }
    }

    /** 手动「立即分析」：跑一次本地规则引擎，新事件由仓储去重后落库 */
    fun analyzeNow() {
        if (_state.value.analyzing) return
        scope.launch {
            _state.update { it.copy(analyzing = true, message = null) }
            val fresh = runCatching { behavior.runAnalysis() }.getOrDefault(emptyList())
            refreshCount()
            _state.update {
                it.copy(
                    analyzing = false,
                    lastRunAt = System.currentTimeMillis(),
                    message = if (fresh.isEmpty()) {
                        "本次分析没有发现新的异常"
                    } else {
                        "本次分析发现 ${fresh.size} 条新事件"
                    },
                )
            }
        }
    }

    /** 清空历史事件（只清展示，不影响使用数据） */
    fun clearEvents() {
        scope.launch {
            runCatching { behavior.clear() }
            refreshCount()
            _state.update { it.copy(message = "已清空行为事件记录") }
        }
    }

    private suspend fun refreshCount() {
        val count = runCatching { behavior.countToday() }.getOrDefault(0)
        _state.update { it.copy(todayCount = count) }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    companion object {
        private const val OBSERVE_LIMIT = 60
    }
}
