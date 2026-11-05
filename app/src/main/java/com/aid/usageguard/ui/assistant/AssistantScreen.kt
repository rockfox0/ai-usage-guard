package com.aid.usageguard.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aid.usageguard.agent.memory.AgentMemory
import com.aid.usageguard.data.Container
import com.aid.usageguard.domain.model.ChatMessageModel
import com.aid.usageguard.ui.MarkdownLiteText
import com.aid.usageguard.ui.vmFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 助手聊天页。
 *
 * - 根壳已经套了 UsageGuardTheme 与安全区 insets，这里只处理 IME 弹出时的底部避让；
 * - 消息气泡区分用户/助手，助手气泡下展示「本次调用过的工具名」；
 * - 发送中显示加载态与实时工具名，可「停止」取消（协程取消会连带取消 OkHttp 请求）；
 * - 空态给出示例问题，右上角可清空对话（同时清空 Room agent_message）。
 */
@Composable
fun AssistantScreen(
    modifier: Modifier = Modifier,
    viewModel: AssistantViewModel = viewModel(
        factory = vmFactory {
            AssistantViewModel(
                runtime = Container.agentRuntime,
                memory = AgentMemory(Container.db.agentMessageDao()),
                settings = Container.settings,
            )
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme

    // 新消息 / 发送状态变化时滚到底部（发送中底部还有一个「正在思考」气泡）
    LaunchedEffect(state.messages.size, state.sending) {
        val lastIndex = state.messages.size + if (state.sending) 1 else 0
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex - 1)
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        AssistantHeader(
            configured = state.configured,
            modelLabel = state.modelLabel,
            canClear = state.messages.isNotEmpty() && !state.sending,
            onClear = viewModel::clear,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loadingHistory -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }

                state.messages.isEmpty() -> AssistantEmptyState(onPick = viewModel::onInputChange)

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                    if (state.sending) {
                        item(key = "thinking") { ThinkingBubble(liveTool = state.liveTool) }
                    }
                }
            }
        }

        val error = state.error
        if (error != null) {
            ErrorNotice(text = error, onDismiss = viewModel::dismissError)
        }

        if (!state.configured && state.messages.isEmpty() && !state.loadingHistory) {
            Text(
                text = "未配置模型接口时无法对话：请到「设置 → AI 接口」填写 BaseURL 与 API Key。",
                fontSize = 11.sp,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        InputBar(
            state = state,
            onInputChange = viewModel::onInputChange,
            onSend = viewModel::send,
            onStop = viewModel::stop,
        )
    }
}

@Composable
private fun AssistantHeader(
    configured: Boolean,
    modelLabel: String,
    canClear: Boolean,
    onClear: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("AI 助手", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                text = if (configured) {
                    "已连接 $modelLabel"
                } else {
                    "尚未配置模型接口（设置 → AI 接口）"
                },
                fontSize = 11.sp,
                color = if (configured) colors.onSurfaceVariant else colors.error,
            )
        }
        TextButton(onClick = onClear, enabled = canClear) {
            Text("清空对话", fontSize = 12.sp)
        }
    }
}

@Composable
private fun AssistantEmptyState(onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val samples = listOf(
        "今天我用得最多的是什么？",
        "最近有没有异常使用？",
        "帮我看看微信这周用了多久",
        "给我一些减少刷手机的建议",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("你好，我是 AI 使用助手", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "我可以查询你的使用统计、分析异常行为，并给出具体建议。" +
                        "我只能「建议」限制某个 App，不会直接替你限制。",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    lineHeight = 18.sp,
                )
            }
        }

        Text("试试这些问题", fontSize = 12.sp, color = colors.onSurfaceVariant)
        samples.forEach { sample ->
            OutlinedButton(
                onClick = { onPick(sample) },
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(sample, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessageModel) {
    val colors = MaterialTheme.colorScheme
    val isUser = message.role == AgentMemory.ROLE_USER

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                color = if (isUser) colors.primary else colors.surface,
                contentColor = if (isUser) colors.onPrimary else colors.onSurface,
                shape = if (isUser) {
                    RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
                } else {
                    RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
                },
                tonalElevation = if (isUser) 0.dp else 1.dp,
            ) {
                if (isUser) {
                    // 用户自己输入的内容按纯文本展示，不做 Markdown 解释
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                } else {
                    // 模型回答可能带 **加粗** / # 标题 / - 列表 / `代码`，用轻量 Markdown 渲染
                    MarkdownLiteText(
                        text = message.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = colors.onSurface,
                    )
                }
            }
            if (!isUser && message.toolCalls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "🔧 本次调用工具：" + message.toolCalls.joinToString("、"),
                    fontSize = 10.sp,
                    color = colors.primary,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatClock(message.timestamp),
                fontSize = 9.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThinkingBubble(liveTool: String?) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = colors.surface,
            contentColor = colors.onSurfaceVariant,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = colors.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (liveTool != null) "正在调用工具：$liveTool" else "正在思考…",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorNotice(text: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("知道了", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun InputBar(
    state: AssistantUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("问我今天的手机使用情况…", fontSize = 12.sp) },
            textStyle = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            shape = RoundedCornerShape(20.dp),
            enabled = !state.sending,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (state.sending) {
            FilledTonalButton(
                onClick = onStop,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text("停止", fontSize = 12.sp)
            }
        } else {
            Button(
                onClick = onSend,
                enabled = state.canSend,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun formatClock(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))
