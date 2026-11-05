package com.aid.usageguard.ui.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.ActionDraftRepository
import com.aid.usageguard.domain.action.ActionExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PendingActionsUiState(
    val loading: Boolean = true,
    val items: List<PendingActionUi> = emptyList(),
    /** 执行/取消后的一行提示 */
    val message: String? = null,
    val busyOpId: String? = null,
) {
    val count: Int get() = items.size
    val hasItems: Boolean get() = items.isNotEmpty()
}

/**
 * 「待确认操作」列表 ViewModel（v1.7 方案 §6.2）。
 * - 数据来自 [ActionDraftRepository.pending]（内部会先把过期草案标记为 EXPIRED）；
 * - 「确认并执行」严格走 markConfirmed → [ActionExecutor.execute] → finish，
 *   markConfirmed 返回 null（已处理/已过期）时不会执行；
 * - 每秒刷新剩余有效期文案；过期项置灰不可操作。
 */
class PendingActionsViewModel(
    private val drafts: ActionDraftRepository,
    private val executor: ActionExecutor,
    private val usageSource: UsageEventSource,
) : ViewModel() {

    private val _state = MutableStateFlow(PendingActionsUiState())
    val state: StateFlow<PendingActionsUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var tickerJob: Job? = null

    init {
        refresh()
        startTicker()
    }

    fun refresh(force: Boolean = false) {
        if (!force && refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val entities = runCatching { drafts.pending() }.getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            val items = withContext(Dispatchers.IO) {
                entities.map { entity ->
                    PendingActionPresenter.toUi(
                        entity = entity,
                        nameOf = { pkg -> usageSource.appNameOf(pkg) },
                        now = now,
                    )
                }
            }
            _state.update { current ->
                current.copy(
                    loading = false,
                    items = items,
                    busyOpId = current.busyOpId?.takeIf { id -> items.any { it.opId == id } },
                )
            }
        }
    }

    /** 确认并执行；重复点击/过期都安全（markConfirmed 返回 null 时只提示不执行） */
    fun confirm(opId: String) {
        if (_state.value.busyOpId != null) return
        if (_state.value.items.firstOrNull { it.opId == opId }?.actionable != true) return
        _state.update { state ->
            state.copy(
                busyOpId = opId,
                message = null,
                items = state.items.map { ui -> if (ui.opId == opId) ui.copy(busy = true) else ui },
            )
        }
        viewModelScope.launch {
            val result = PendingActionPresenter.confirmAndExecute(drafts, executor, opId)
            _state.update { state ->
                state.copy(
                    busyOpId = null,
                    message = if (result.success) "已执行：${result.message}" else result.message,
                    items = state.items.map { ui ->
                        if (ui.opId != opId) {
                            ui
                        } else {
                            ui.copy(
                                busy = false,
                                statusText = if (result.success) "已执行" else "执行失败",
                                success = result.success,
                            )
                        }
                    },
                )
            }
            refresh(force = true)
        }
    }

    /** 取消草案 */
    fun cancel(opId: String) {
        if (_state.value.busyOpId != null) return
        _state.update { state ->
            state.copy(
                message = null,
                items = state.items.map { ui ->
                    if (ui.opId == opId) ui.copy(busy = true) else ui
                },
            )
        }
        viewModelScope.launch {
            PendingActionPresenter.cancel(drafts, opId)
            _state.update { state ->
                state.copy(
                    message = "已取消该操作",
                    items = state.items.map { ui ->
                        if (ui.opId == opId) ui.copy(busy = false, statusText = "已取消", success = false) else ui
                    },
                )
            }
            refresh(force = true)
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 每秒刷新「剩余 X 分 Y 秒」，到期后立即置为过期 */
    private fun startTicker() {
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                _state.update { state ->
                    if (state.items.isEmpty()) {
                        state
                    } else {
                        state.copy(
                            items = state.items.map { ui ->
                                if (ui.done) {
                                    ui
                                } else {
                                    val expired = now >= ui.expiresAt
                                    ui.copy(
                                        expired = expired,
                                        remainingText = PendingActionPresenter.remainingText(ui.expiresAt, now),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        tickerJob?.cancel()
        super.onCleared()
    }
}
