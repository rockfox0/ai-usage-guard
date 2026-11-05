package com.aid.usageguard.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aid.usageguard.agent.contract.AgentRuntime
import com.aid.usageguard.agent.llm.LlmClientFactory
import com.aid.usageguard.agent.memory.AgentMemory
import com.aid.usageguard.agent.runtime.AgentLoop
import com.aid.usageguard.agent.runtime.AgentPhase
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.model.ChatMessageModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** AI 助手页的 UI 状态 */
data class AssistantUiState(
    val messages: List<ChatMessageModel> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val loadingHistory: Boolean = true,
    /** 是否已填写 API Key（未配置时给出提示，不发起无效请求） */
    val configured: Boolean = false,
    /** 例如 "deepseek-chat · OpenAI 兼容" */
    val modelLabel: String = "",
    /** 最近一次回答调用过的工具名 */
    val lastToolCalls: List<String> = emptyList(),
    /** 正在执行的工具（实时阶段，来自 AgentLoop.state） */
    val liveTool: String? = null,
    val error: String? = null,
) {
    val canSend: Boolean get() = input.isNotBlank() && !sending
}

/**
 * AI 助手页 ViewModel。
 *
 * 职责边界：
 * - 只负责 UI 状态与触发 [AgentRuntime.chat]；
 * - 消息持久化由 AgentLoop/AgentMemory 负责（单一写入方），这里只读 Room 刷新列表；
 * - 取消由 viewModelScope 驱动，AgentLoop 会把 CancellationException 继续上抛。
 */
class AssistantViewModel(
    private val runtime: AgentRuntime,
    private val memory: AgentMemory,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var sendJob: Job? = null

    init {
        loadHistory()
        refreshConfig()
        observeRuntimeState()
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** 发送当前输入。AgentLoop 负责把用户消息与回复落库，这里做乐观展示 + 结束后以 Room 为准刷新。 */
    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.sending) return

        _state.update { it.copy(input = "", sending = true, error = null, liveTool = null) }
        val optimistic = ChatMessageModel(
            id = -System.currentTimeMillis(),
            role = AgentMemory.ROLE_USER,
            content = text,
            timestamp = System.currentTimeMillis(),
        )
        _state.update { it.copy(messages = it.messages + optimistic) }

        sendJob = viewModelScope.launch {
            val outcome = try {
                runtime.chat(SESSION_ID, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            val persisted = runCatching { memory.history(SESSION_ID) }.getOrDefault(emptyList())
            _state.update { current ->
                val rawText = outcome?.text.orEmpty()
                val message = when {
                    outcome == null -> "调用失败，请检查网络后重试。"
                    !outcome.ok && rawText.isNotBlank() -> rawText
                    !outcome.ok -> "调用失败，请检查模型接口配置或网络后重试。"
                    else -> null
                }
                current.copy(
                    messages = if (persisted.isNotEmpty()) persisted else current.messages,
                    sending = false,
                    liveTool = null,
                    lastToolCalls = outcome?.toolCalls ?: emptyList(),
                    error = message,
                )
            }
        }
    }

    /** 停止当前回答（取消协程 → OkHttp 请求也会被 cancel） */
    fun stop() {
        if (!_state.value.sending) return
        sendJob?.cancel()
        sendJob = null
        viewModelScope.launch {
            val persisted = runCatching { memory.history(SESSION_ID) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    messages = if (persisted.isNotEmpty()) persisted else it.messages,
                    sending = false,
                    liveTool = null,
                )
            }
        }
    }

    /** 清空对话（同时清空 Room 里的 agent_message） */
    fun clear() {
        sendJob?.cancel()
        sendJob = null
        viewModelScope.launch {
            runCatching { memory.clear() }
            _state.update {
                it.copy(
                    messages = emptyList(),
                    sending = false,
                    liveTool = null,
                    lastToolCalls = emptyList(),
                    error = null,
                    loadingHistory = false,
                )
            }
        }
    }

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

    /** 订阅 AgentLoop 的实时阶段，用于显示「正在调用工具：xxx」 */
    private fun observeRuntimeState() {
        val loop = runtime as? AgentLoop ?: return
        viewModelScope.launch {
            loop.state.collect { snapshot ->
                when (snapshot.phase) {
                    AgentPhase.CALLING_TOOL -> _state.update { it.copy(liveTool = snapshot.toolCalls.lastOrNull()) }
                    AgentPhase.THINKING -> _state.update { it.copy(liveTool = null) }
                    else -> Unit
                }
            }
        }
    }

    companion object {
        /** 助手页固定一个会话 id：重启 App 后仍能看到上次对话；「清空」清的是整张表。 */
        const val SESSION_ID = AgentMemory.DEFAULT_SESSION_ID
    }
}
