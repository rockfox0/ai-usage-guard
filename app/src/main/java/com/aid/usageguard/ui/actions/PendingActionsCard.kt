package com.aid.usageguard.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aid.usageguard.data.Container
import com.aid.usageguard.ui.vmFactory
import kotlinx.coroutines.delay

/**
 * 设置页「待确认操作」入口卡片（v1.7 方案 §6.2）。
 * 空态（没有待确认草案）时整张卡片不渲染，避免占位。
 */
@Composable
fun PendingActionsCard(
    modifier: Modifier = Modifier,
    viewModel: PendingActionsViewModel = viewModel(
        factory = vmFactory {
            PendingActionsViewModel(
                drafts = Container.actionDrafts,
                executor = Container.actionExecutor,
                usageSource = Container.usageSource,
            )
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(Unit) { viewModel.refresh() }
    // 执行结果提示 4 秒后自动收起
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4_000L)
            viewModel.dismissMessage()
        }
    }

    if (!state.hasItems && state.message == null) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "待确认操作",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.count > 0) {
                    Surface(
                        color = colors.primary,
                        contentColor = colors.onPrimary,
                        shape = CircleShape,
                    ) {
                        Text(
                            text = "${state.count}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            state.message?.let { message ->
                Text(
                    text = message,
                    fontSize = 11.sp,
                    color = colors.primary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (state.items.isEmpty()) {
                if (state.message == null) {
                    Text(
                        text = "当前没有待确认的操作",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "AI 生成的修改需要你确认后才会生效，草案 5 分钟内有效。",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                state.items.forEachIndexed { index, item ->
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    PendingActionItem(
                        ui = item,
                        onConfirm = { viewModel.confirm(item.opId) },
                        onCancel = { viewModel.cancel(item.opId) },
                    )
                }
            }
        }
    }
}

/**
 * 单条待确认操作（设置页列表 / 助手页消息流内共用）。
 * 展示：类型 / 目标应用 / before→after / 剩余有效期，并给出「确认并执行」「取消」。
 */
@Composable
fun PendingActionItem(
    ui: PendingActionUi,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val statusText = ui.statusText
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ui.done) colors.surfaceVariant else colors.surfaceVariant.copy(alpha = 0.65f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ui.typeLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = ui.remainingText,
                    fontSize = 10.sp,
                    color = if (ui.expired && !ui.done) colors.error else colors.onSurfaceVariant,
                )
            }
            ui.targetLabel?.let { target ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "目标：$target",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }
            if (ui.changeLabel.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = ui.changeLabel,
                    fontSize = 12.sp,
                    color = colors.onSurface,
                    lineHeight = 17.sp,
                )
            }
            ui.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = note,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            when {
                statusText != null -> Text(
                    text = statusText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (ui.success == true) colors.primary else colors.error,
                )

                ui.expired -> Text(
                    text = "已过期，不可操作",
                    fontSize = 11.sp,
                    color = colors.error,
                )

                else -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel, enabled = !ui.busy) {
                        Text("取消", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(
                        onClick = onConfirm,
                        enabled = !ui.busy,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (ui.busy) "执行中…" else "确认并执行",
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
