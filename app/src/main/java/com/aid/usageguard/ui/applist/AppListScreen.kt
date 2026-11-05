package com.aid.usageguard.ui.applist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aid.usageguard.data.Container
import com.aid.usageguard.ui.vmFactory

/**
 * App 使用列表。
 * 数据 = [Container.usageSource].launcherVisibleApps()（全部可启动应用）
 *      + [Container.usage].todayRanking()（今日分钟数），由 [AppListViewModel] 合并。
 *
 * 限制语义随模式切换（见 [AppListUiState.modeText]）：
 * - 超额后才限制（QUOTA_AFTER_BUDGET，默认）：额度内所有应用可用，开关 = 「允许」，
 *   checked 表示是否在白名单（超额后仍可用的通行证，[Container.settings].setAllowed）；
 * - 始终限制非白名单（ALWAYS_NON_WHITELIST）：同样用白名单开关；
 * - 只限制指定应用（MANUAL_ONLY）：开关 = 「暂停」，checked 表示是否被手动限制
 *   （[Container.restrictions].block/unblock）；
 * - 单应用每日限额在任何模式下独立生效；系统关键应用开关置灰，永不被限制。
 */
@Composable
fun AppListScreen(onOpenAppDetail: (String) -> Unit) {
    val viewModel: AppListViewModel = viewModel(
        factory = vmFactory {
            AppListViewModel(
                usageSource = Container.usageSource,
                usage = Container.usage,
                settings = Container.settings,
                restrictions = Container.restrictions,
                quota = Container.quota,
                whitelist = Container.whitelist,
            )
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 每次进入页面都重新采集/查询一次今日分钟数（ViewModel 内部已做并发去重）
    LaunchedEffect(Unit) { viewModel.load(ingest = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "应用使用",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = state.modeText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "共 ${state.apps.size} 个可启动应用 · 今日用到 ${state.usedCount} 个",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { viewModel.refresh() }, enabled = !state.refreshing) {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.setQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("搜索应用名或包名", fontSize = 13.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        if (!state.hasUsageAccess) {
            NoticeRow(
                text = "未授予「使用情况访问」权限，今日分钟数会显示为 0；授权后回来刷新即可。",
                isError = true,
            )
        }
        state.error?.let { message ->
            NoticeRow(text = message, isError = true)
        }
        // 白名单添加结果（成功 / 达到每日上限被拒）
        state.notice?.let { notice ->
            NoticeRow(
                text = notice,
                isError = state.noticeIsError,
                onDismiss = viewModel::dismissNotice,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.filtered.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.query.isBlank()) {
                            "没有找到可启动的应用"
                        } else {
                            "没有匹配「${state.query}」的应用"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.filtered, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            switchLabel = state.switchLabel,
                            onSwitch = { checked -> viewModel.setSwitch(app.packageName, checked) },
                            onClick = { onOpenAppDetail(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeRow(
    text: String,
    isError: Boolean,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) colors.errorContainer else colors.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = if (isError) colors.onErrorContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 11.sp,
                color = if (isError) colors.onErrorContainer else colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text("知道了", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppUsageItem,
    switchLabel: String,
    onSwitch: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val statusColor = when {
        app.systemProtected -> colors.onSurfaceVariant
        app.whitelisted -> colors.primary
        app.focusActive -> colors.error
        app.restricted && !app.byDefault -> colors.error
        else -> colors.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.whitelisted) colors.primary.copy(alpha = 0.10f) else colors.surface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = app.packageName)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    app.dailyLimitMinutes?.let { limit ->
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = colors.secondary.copy(alpha = 0.18f),
                            contentColor = colors.secondary,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = "限额 $limit 分钟",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(app.statusText)
                        append(" · ")
                        append(if (app.minutes > 0L) "今日 ${app.minutes} 分钟" else "今日未使用")
                    },
                    fontSize = 11.sp,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = switchLabel,
                fontSize = 11.sp,
                color = if (app.systemProtected) {
                    colors.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    colors.onSurfaceVariant
                },
                modifier = Modifier.padding(end = 6.dp),
            )
            Switch(
                checked = app.checked,
                onCheckedChange = onSwitch,
                enabled = !app.systemProtected,
            )
        }
    }
}

/** App 图标（PackageManager 真实图标，加载失败时退化为色块） */
@Composable
internal fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val bitmap = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(width = 108, height = 108)
        }.getOrNull()
    }
    val shape = RoundedCornerShape(size / 4)
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = packageName,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(colors.surfaceVariant),
        )
    }
}
