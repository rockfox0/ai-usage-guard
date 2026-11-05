package com.aid.usageguard.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aid.usageguard.agent.contract.AgentContext
import com.aid.usageguard.agent.contract.AgentEvent
import com.aid.usageguard.agent.contract.AgentOutcome
import com.aid.usageguard.agent.contract.AgentRuntime
import com.aid.usageguard.agent.llm.LlmClientFactory
import com.aid.usageguard.agent.memory.AgentMemory
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.ActionDraftRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.action.ActionExecutor
import com.aid.usageguard.domain.model.ChatMessageModel
import com.aid.usageguard.ui.actions.PendingActionPresenter
import com.aid.usageguard.ui.actions.PendingActionUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 助手页可选的对话对象（今日使用 Top N 应用） */
data class AssistantAppChoice(
    val packageName: String,
    val appName: String,
    val minutes: Long,
)

/** AI 助手页的 UI 状态 */
data class AssistantUiState(
    val messages: List<ChatMessageModel> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val stopping: Boolean = false,
    val loadingHistory: Boolean = true,
    /** 是否已填写 API Key（未配置时给出提示，不发起无效请求） */
    val configured: Boolean = false,
    /** 例如 "deepseek-chat · OpenAI 兼容" */
    val modelLabel: String = "",
    /** 最近一次回答调用过的工具名 */
    val lastToolCalls: List<String> = emptyList(),
    /** 流式回答当前累计文本（AgentEvent.TextDelta 累计） */
    val streamingText: String = "",
    /** 流式过程中的工具状态小字（「正在读取… / 已完成」） */
    val toolStatus: String? = null,
    /** ActionProposed 事件插入的确认卡片（与设置页卡片同样式） */
    val actionCards: List<PendingActionUi> = emptyList(),
    val error: String? = null,
    /** 对话对象（提交前可切换；提交后本次任务用快照，chip 改动不影响已提交任务） */
    val focusPackage: String? = null,
    val focusAppName: String? = null,
    /** 范围天数：1 / 7 / 30 */
    val rangeDays: Int = 7,
    /** 可选的对话对象（今日使用 Top N） */
    val appChoices: List<AssistantAppChoice> = emptyList(),
    /** 最近一次提交时固定下来的上下文描述 */
    val activeContextLabel: String? = null,
) {
    val canSend: Boolean get() = input.isNotBlank() && !sending
    val hasFocus: Boolean get() = focusPackage != null
    val streaming: Boolean get() = streamingText.isNotBlank()

    /** 顶部上下文 chip 文案 */
    val contextChipLabel: String
        get() = "正在分析：${focusAppName ?: "全部应用"} · 最近 $rangeDays 天"

    val hasConversation: Boolean
        get() = messages.isNotEmpty() || actionCards.isNotEmpty() || streamingText.isNotBlank()
}

/**
 * AI 助手页 ViewModel（v1.7 方案 §7 上下文 + §8.3 流式事件）。
 *
 * 职责边界：
 * - 只负责 UI 状态、触发 [AgentRuntime.chat]、消费 [AgentRuntime.events]；
 * - 消息持久化由 AgentLoop/AgentMemory 负责（单一写入方），这里只读 Room 刷新列表；
 * - events 暂时为空流时优雅降级：仍用 chat() 的返回值 / Room 历史展示；
 * - 待确认操作（ActionProposed）用 [PendingActionUi] 渲染，确认后走与设置页相同的
 *   markConfirmed → ActionExecutor.execute → finish 流程。
 */
class AssistantViewModel(
    private val runtime: AgentRuntime,
    private val memory: AgentMemory,
    private val settings: SettingsRepository,
    private val usage: UsageRepository,
    private val usageSource: UsageEventSource,
    private val actionDrafts: ActionDraftRepository,
    private val actionExecutor: ActionExecutor,
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var sendJob: Job? = null
    private var flushJob: Job? = null

    /** TextDelta 合并缓冲：最多每 60ms 触发一次重组 */
    private val deltaBuffer = StringBuilder()

    init {
        loadHistory()
        refreshConfig()
        loadAppChoices()
        observeEvents()
    }

    // ------------------------------------------------------------------ 输入与上下文

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** 选择/清除对话对象（提交前有效；发送中不允许切换） */
    fun setFocus(choice: AssistantAppChoice?) {
        _state.update { state ->
            if (state.sending) {
                state
            } else {
                state.copy(
                    focusPackage = choice?.packageName,
                    focusAppName = choice?.appName,
                )
            }
        }
    }

    fun clearFocus() = setFocus(null)

    /** 范围天数：1 / 7 / 30（提交前有效） */
    fun setRangeDays(days: Int) {
        if (days !in RANGE_CHOICES) return
        _state.update { state -> if (state.sending) state else state.copy(rangeDays = days) }
    }

    // ------------------------------------------------------------------ 发送 / 停止

    /**
     * 发送当前输入。
     * 发送瞬间把上下文快照成 [AgentContext]（entryPage / focusPackage / rangeDays），
     * 之后 chip 再变化也不会影响这次任务。
     */
    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.sending) return

        val snapshot = _state.value
        val context = AgentContext(
            entryPage = "assistant",
            focusPackage = snapshot.focusPackage,
            focusAppName = snapshot.focusAppName,
            rangeDays = snapshot.rangeDays,
        )

        deltaBuffer.setLength(0)
        flushJob?.cancel()
        flushJob = null
        _state.update {
            it.copy(
                input = "",
                sending = true,
                stopping = false,
                streamingText = "",
                toolStatus = null,
                error = null,
                lastToolCalls = emptyList(),
                activeContextLabel = context.describe().ifBlank { null },
                messages = it.messages + ChatMessageModel(
                    id = -System.currentTimeMillis(),
                    role = AgentMemory.ROLE_USER,
                    content = text,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }

        sendJob = viewModelScope.launch {
            var outcome: AgentOutcome? = null
            try {
                outcome = runtime.chat(SESSION_ID, text, context)
            } catch (cancelled: CancellationException) {
                // stop() 已经做了收尾，这里直接结束
                throw cancelled
            } catch (t: Throwable) {
                outcome = null
            }
            val result = outcome
            flushDelta()
            val persisted = runCatching { memory.history(SESSION_ID) }.getOrDefault(emptyList())
            val streamed = _state.value.streamingText
            val finalText = result?.text.orEmpty()
            // 事件流可用时正文来自增量；不可用时回落 chat() 返回值
            val replyText = finalText.ifBlank { streamed }
            _state.update { current ->
                val messages = if (persisted.isNotEmpty()) {
                    persisted
                } else if (replyText.isNotBlank()) {
                    current.messages + ChatMessageModel(
                        id = -System.currentTimeMillis() - 1,
                        role = AgentMemory.ROLE_ASSISTANT,
                        content = replyText,
                        timestamp = System.currentTimeMillis(),
                    )
                } else {
                    current.messages
                }
                current.copy(
                    messages = messages,
                    sending = false,
                    stopping = false,
                    streamingText = "",
                    toolStatus = null,
                    lastToolCalls = result?.toolCalls ?: emptyList(),
                    error = when {
                        result == null -> "调用失败，请检查网络后重试。"
                        !result.ok && streamed.isBlank() && finalText.isBlank() ->
                            "调用失败，请检查模型接口配置或网络后重试。"
                        else -> null
                    },
                )
            }
        }
    }

    /** 停止当前回答：通知运行时取消，必要时兜底结束本地协程 */
    fun stop() {
        if (!_state.value.sending) return
        _state.update { it.copy(stopping = true) }
        viewModelScope.launch {
            runCatching { runtime.cancelCurrent() }
            delay(700L)
            if (_state.value.sending) {
                sendJob?.cancel()
                sendJob = null
                finalizeCancelled()
            }
        }
    }

    /** 清空对话（同时清空 Room 里的 agent_message） */
    fun clear() {
        sendJob?.cancel()
        sendJob = null
        flushJob?.cancel()
        flushJob = null
        _state.update {
            it.copy(
                messages = emptyList(),
                actionCards = emptyList(),
                sending = false,
                stopping = false,
                streamingText = "",
                toolStatus = null,
                lastToolCalls = emptyList(),
                error = null,
                activeContextLabel = null,
                loadingHistory = false,
            )
        }
        viewModelScope.launch { runCatching { memory.clear() } }
    }

    // ------------------------------------------------------------------ 待确认操作（消息流内卡片）

    fun confirmAction(opId: String) {
        val card = _state.value.actionCards.firstOrNull { it.opId == opId } ?: return
        if (!card.actionable) return
        updateCard(opId) { it.copy(busy = true) }
        viewModelScope.launch {
            val result = PendingActionPresenter.confirmAndExecute(actionDrafts, actionExecutor, opId)
            updateCard(opId) { ui ->
                ui.copy(
                    busy = false,
                    success = result.success,
                    statusText = if (result.success) "已执行：${result.message}" else result.message,
                )
            }
        }
    }

    fun cancelAction(opId: String) {
        val card = _state.value.actionCards.firstOrNull { it.opId == opId } ?: return
        if (!card.actionable) return
        updateCard(opId) { it.copy(busy = true) }
        viewModelScope.launch {
            PendingActionPresenter.cancel(actionDrafts, opId)
            // v1.9 Phase 3：用户取消草案 → 记入 FeedbackMemory，后续避免原样重复该建议
            runCatching {
                com.aid.usageguard.data.Container.memory.recordFeedback(
                    targetType = "suggestion",
                    targetId = opId,
                    signal = "REJECT",
                    reason = "用户取消了该待确认操作：${card.typeLabel}${card.changeLabel.let { if (it.isBlank()) "" else "（$it）" }}",
                )
            }
            updateCard(opId) { it.copy(busy = false, success = false, statusText = "已取消") }
        }
    }

    private fun updateCard(opId: String, transform: (PendingActionUi) -> PendingActionUi) {
        _state.update { state ->
            state.copy(
                actionCards = state.actionCards.map { card ->
                    if (card.opId == opId) transform(card) else card
                },
            )
        }
    }

    // ------------------------------------------------------------------ 事件流

    /** 订阅运行时事件流；流不可用/为空时静默降级（chat() 返回值仍然会展示） */
    private fun observeEvents() {
        viewModelScope.launch {
            try {
                runtime.events.collect { event -> handleEvent(event) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // 忽略：降级为「只显示 chat() 的最终结果」
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> appendDelta(event.text)

            is AgentEvent.ToolStarted -> _state.update {
                it.copy(toolStatus = "正在读取：${toolLabel(event.name)}…")
            }

            is AgentEvent.ToolFinished -> _state.update {
                it.copy(toolStatus = if (event.ok) "已完成：${toolLabel(event.name)}" else "工具失败：${toolLabel(event.name)}")
            }

            is AgentEvent.DataBlock -> _state.update {
                it.copy(toolStatus = "已获取本地数据")
            }

            is AgentEvent.ActionProposed -> addActionCard(event)

            is AgentEvent.Completed -> {
                val finalText = event.outcome.text
                _state.update { state ->
                    state.copy(
                        streamingText = finalText.ifBlank { state.streamingText },
                        toolStatus = null,
                    )
                }
            }

            is AgentEvent.Failed -> _state.update {
                it.copy(error = event.message, toolStatus = null)
            }

            is AgentEvent.Cancelled -> finalizeCancelled()
        }
    }

    /** TextDelta 追加（60ms 合并一次，避免每个 token 都重组） */
    private fun appendDelta(text: String) {
        if (text.isEmpty()) return
        deltaBuffer.append(text)
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            delay(60L)
            flushDelta()
        }
    }

    private fun flushDelta() {
        if (deltaBuffer.isEmpty()) return
        val chunk = deltaBuffer.toString()
        deltaBuffer.setLength(0)
        _state.update { it.copy(streamingText = it.streamingText + chunk) }
    }

    private fun finalizeCancelled() {
        flushDelta()
        val text = _state.value.streamingText
        _state.update {
            it.copy(
                sending = false,
                stopping = false,
                toolStatus = null,
                streamingText = if (text.isBlank()) "" else "$text\n\n（已取消）",
                error = if (text.isBlank()) "已取消本次回答" else null,
            )
        }
    }

    private fun addActionCard(event: AgentEvent.ActionProposed) {
        viewModelScope.launch {
            val entity = runCatching { actionDrafts.byId(event.opId) }.getOrNull()
            val now = System.currentTimeMillis()
            val base = if (entity != null) {
                withContext(Dispatchers.IO) {
                    PendingActionPresenter.toUi(
                        entity = entity,
                        nameOf = { pkg -> usageSource.appNameOf(pkg) },
                        now = now,
                    )
                }
            } else {
                val expiresAt = now + ActionDraftRepository.DEFAULT_TTL_MS
                PendingActionUi(
                    opId = event.opId,
                    type = event.type,
                    typeLabel = PendingActionPresenter.typeLabel(event.type),
                    changeLabel = "",
                    expiresAt = expiresAt,
                    remainingText = PendingActionPresenter.remainingText(expiresAt, now),
                )
            }
            val note = event.message.takeIf { it.isNotBlank() }
            _state.update { state ->
                if (state.actionCards.any { it.opId == event.opId }) {
                    state
                } else {
                    state.copy(actionCards = state.actionCards + base.copy(note = note))
                }
            }
        }
    }

    // ------------------------------------------------------------------ 历史 / 配置 / 应用候选

    private fun loadHistory() {
        viewModelScope.launch {
            val history = runCatching { memory.history(SESSION_ID) }.getOrDefault(emptyList())
            _state.update { it.copy(messages = history, loadingHistory = false) }
        }
    }

    private fun refreshConfig() {
        viewModelScope.launch {
            val current = runCatching { settings.current() }.getOrDefault(AppSettings.DEFAULT)
            _state.update {
                it.copy(
                    configured = current.aiApiKey.isNotBlank(),
                    modelLabel = LlmClientFactory.describe(current),
                )
            }
        }
    }

    private fun loadAppChoices() {
        viewModelScope.launch {
            val ranking = runCatching { usage.todayRanking(8) }.getOrDefault(emptyList())
            _state.update { state ->
                state.copy(
                    appChoices = ranking.map { AssistantAppChoice(it.packageName, it.appName, it.minutes) },
                )
            }
        }
    }

    /** 工具名 → 中文短语（流式状态小字用） */
    private fun toolLabel(name: String): String = when {
        name.startsWith("get_daily") || name.startsWith("get_usage") || name.startsWith("get_weekly") -> "使用统计"
        name.startsWith("get_behavior") -> "异常行为"
        name.startsWith("get_score") -> "评分"
        name.startsWith("get_report") -> "报告"
        name.startsWith("get_app") || name.startsWith("get_user_preferences") -> "应用与偏好"
        name.startsWith("get_whitelist") -> "白名单"
        name.startsWith("set_") -> "修改草案"
        else -> name
    }

    companion object {
        /** 助手页固定一个会话 id：重启 App 后仍能看到上次对话；「清空」清的是整张表。 */
        const val SESSION_ID = AgentMemory.DEFAULT_SESSION_ID

        /** 可选范围天数 */
        val RANGE_CHOICES = listOf(1, 7, 30)
    }
}
