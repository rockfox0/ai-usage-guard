package com.aid.usageguard.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.notify.NotificationHelper
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.aid.usageguard.ui.vmFactory
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(context.applicationContext as android.app.Application) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val permissions by vm.permissionState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshPermissions() }

    // 以 DataStore 的值为 key 初始化：保存后自动同步，用户输入期间不会被覆盖
    var baseUrl by remember(settings.aiBaseUrl) { mutableStateOf(settings.aiBaseUrl) }
    var apiKey by remember(settings.aiApiKey) { mutableStateOf(settings.aiApiKey) }
    var model by remember(settings.aiModel) { mutableStateOf(settings.aiModel) }
    var format by remember(settings.aiFormat) { mutableStateOf(settings.aiFormat) }
    var showKey by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf<String?>(null) }
    var testNotifyHint by remember { mutableStateOf<String?>(null) }
    var nickname by remember(settings.aiNickname) { mutableStateOf(settings.aiNickname) }
    var tone by remember(settings.aiTone) { mutableStateOf(settings.aiTone) }
    var focus by remember(settings.aiFocus) { mutableStateOf(settings.aiFocus) }
    var customPrompt by remember(settings.aiCustomPrompt) { mutableStateOf(settings.aiCustomPrompt) }
    var personaHint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) vm.refreshPermissions()
    }

    if (confirmClear != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text("确认清空？") },
            text = {
                Text(
                    when (confirmClear) {
                        "usage" -> "会删除本地保存的使用会话与每日聚合数据（系统 UsageStats 不受影响，下次同步会重新导入最近 24 小时）。"
                        "behavior" -> "会删除本地行为事件记录。"
                        else -> "会删除所有 AI/本地生成的报告。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (confirmClear) {
                        "usage" -> vm.clearUsageHistory()
                        "behavior" -> vm.clearBehaviorEvents()
                        else -> vm.clearReports()
                    }
                    confirmClear = null
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text("取消") } },
        )
    }

    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold) }

        if (message != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.10f)),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message!!, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.dismissMessage() }) { Text("好", fontSize = 12.sp) }
                    }
                }
            }
        }

        // 权限
        item {
            SettingsCard(title = "权限状态") {
                PermissionRow(
                    label = "使用情况访问（统计必需）",
                    ok = permissions.hasUsageAccess,
                    actionText = if (permissions.hasUsageAccess) "已授权" else "去授权",
                    onAction = { vm.openUsageAccessSettings(context) },
                )
                Spacer(Modifier.height(6.dp))
                PermissionRow(
                    label = "无障碍服务（强制暂停）",
                    ok = permissions.accessibilityOn,
                    actionText = if (permissions.accessibilityOn) "已开启" else "去开启",
                    onAction = { vm.openAccessibilitySettings(context) },
                )
                Spacer(Modifier.height(6.dp))
                PermissionRow(
                    label = "通知权限",
                    ok = permissions.notificationsEnabled,
                    actionText = if (permissions.notificationsEnabled) "已开启" else "去开启",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android 不允许应用自己授予以上权限，必须由你在系统界面确认一次。",
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        // 限制模式
        item {
            SettingsCard(title = "限制模式") {
                ToggleRow(
                    label = "默认限制第三方应用（系统应用与白名单除外）",
                    checked = settings.restrictByDefault,
                    onChange = vm::setRestrictByDefault,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (settings.restrictByDefault) {
                        "已开启：除「白名单」与系统应用（含电话/短信/设置/桌面/输入法/系统导航）外，用户安装的第三方应用打开即被拦截。在「应用」页把开关打开可标记为「允许」。"
                    } else {
                        "已关闭：只有你在「应用」页手动打开「暂停」的应用会被拦截。"
                    },
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "白名单：${settings.allowedPackages.size} 个应用 · 每个应用每天可用 1 次「临时使用 1 分钟」",
                    fontSize = 11.sp,
                    color = colors.primary,
                )
            }
        }

        // AI 接口
        item {
            SettingsCard(title = "AI 接口（LLM）") {
                Text(
                    "填任意 OpenAI / Anthropic 兼容服务的 BaseURL 与 API Key；留空则只使用本地规则分析。",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("BaseURL", fontSize = 12.sp) },
                    placeholder = { Text("https://api.deepseek.com", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "隐藏" else "显示", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型", fontSize = 12.sp) },
                    placeholder = { Text("deepseek-chat", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("协议格式", fontSize = 12.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = format == AppSettings.FORMAT_OPENAI,
                        onClick = { format = AppSettings.FORMAT_OPENAI },
                        label = { Text("OpenAI 兼容", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = format == AppSettings.FORMAT_ANTHROPIC,
                        onClick = { format = AppSettings.FORMAT_ANTHROPIC },
                        label = { Text("Anthropic 兼容", fontSize = 12.sp) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { vm.saveAiConfig(baseUrl, apiKey, model, format) },
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("保存配置", fontSize = 13.sp) }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = {
                        baseUrl = AppSettings.DEFAULT_BASE_URL
                        model = AppSettings.DEFAULT_MODEL
                        format = AppSettings.FORMAT_OPENAI
                    }) { Text("恢复默认", fontSize = 12.sp) }
                }
            }
        }

        // AI 个性化
        item {
            SettingsCard(title = "AI 个性化") {
                Text(
                    "称呼、语气、关注点会写进 AI 的系统提示词，影响聊天、报告和实时提示的说话方式。",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("AI 怎么称呼你（可空）", fontSize = 12.sp) },
                    placeholder = { Text("例如：老王 / 同学", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("语气", fontSize = 12.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tone == AppSettings.TONE_GENTLE,
                        onClick = { tone = AppSettings.TONE_GENTLE },
                        label = { Text("温和", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = tone == AppSettings.TONE_DIRECT,
                        onClick = { tone = AppSettings.TONE_DIRECT },
                        label = { Text("直接", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = tone == AppSettings.TONE_STRICT,
                        onClick = { tone = AppSettings.TONE_STRICT },
                        label = { Text("严厉", fontSize = 12.sp) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("关注重点（可多选）", fontSize = 12.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                val focusOptions = listOf(
                    AppSettings.FOCUS_SLEEP to "睡眠",
                    AppSettings.FOCUS_STUDY to "学习",
                    AppSettings.FOCUS_ENTERTAINMENT to "娱乐",
                    AppSettings.FOCUS_EFFICIENCY to "效率",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    focusOptions.take(2).forEach { (key, label) ->
                        FilterChip(
                            selected = key in focus,
                            onClick = { focus = if (key in focus) focus - key else focus + key },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    focusOptions.drop(2).forEach { (key, label) ->
                        FilterChip(
                            selected = key in focus,
                            onClick = { focus = if (key in focus) focus - key else focus + key },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = { customPrompt = it },
                    label = { Text("给 AI 的额外要求（可空）", fontSize = 12.sp) },
                    placeholder = { Text("例如：别用感叹号；多提醒我早点睡", fontSize = 12.sp) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            vm.setAiPersona(nickname, tone, focus, customPrompt)
                            personaHint = "已保存，下次对话生效"
                        },
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("保存个性化", fontSize = 13.sp) }
                    if (personaHint != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(personaHint!!, fontSize = 10.sp, color = colors.primary)
                    }
                }
            }
        }

        // 实时提示
        item {
            SettingsCard(title = "实时提示（陪伴模式）") {
                ToggleRow(
                    label = "使用过程中给提示",
                    checked = settings.realtimeNudgeEnabled,
                    onChange = vm::setRealtimeNudgeEnabled,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "连续使用同一个应用达到设定时长后，会在屏幕顶部弹一句提示，8 秒自动消失，不打断操作。",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("提示间隔", fontSize = 12.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { minutes ->
                        FilterChip(
                            selected = settings.nudgeIntervalMinutes == minutes,
                            onClick = { vm.setNudgeIntervalMinutes(minutes) },
                            label = { Text("$minutes 分钟", fontSize = 12.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    label = "用 AI 生成提示语（更费 token）",
                    checked = settings.nudgeUseAi,
                    onChange = vm::setNudgeUseAi,
                )
                Text(
                    "关闭时用本地文案模板；开启后每次提示调用一次模型，失败会自动回退本地文案。",
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        // 使用目标
        item {
            SettingsCard(title = "每日使用目标") {
                Text(
                    "当前目标：${settings.dailyTargetMinutes} 分钟",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = settings.dailyTargetMinutes.toFloat(),
                    onValueChange = { vm.setDailyTarget((it / 15).roundToInt() * 15) },
                    valueRange = 30f..480f,
                    steps = 29,
                )
                Text(
                    "超过目标会触发提醒（可在下方通知开关里单独关闭）。",
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        // 通知
        item {
            SettingsCard(title = "提醒与通知") {
                ToggleRow("总开关", settings.notificationEnabled, vm::setNotificationEnabled)
                ToggleRow("每日使用提醒", settings.notifyDailyReminder, vm::setNotifyDailyReminder)
                ToggleRow("超过目标提醒", settings.notifyGoalExceeded, vm::setNotifyGoalExceeded)
                ToggleRow("异常使用提醒", settings.notifyAnomaly, vm::setNotifyAnomaly)
                ToggleRow("每日 AI 总结", settings.notifyDailySummary, vm::setNotifyDailySummary)
                ToggleRow("每周总结", settings.notifyWeeklySummary, vm::setNotifyWeeklySummary)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        testNotifyHint = "已请求发送测试通知"
                        scope.launch {
                            NotificationHelper.notifyReport(
                                context, "使用情况提醒（测试）",
                                "今天已使用 12 分钟，超过目标时会在这里提醒你。", 9999,
                            )
                        }
                    }) { Text("发送测试通知", fontSize = 12.sp) }
                    if (testNotifyHint != null) {
                        Text(testNotifyHint!!, fontSize = 10.sp, color = colors.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("提醒风格", fontSize = 12.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.reminderStyle == AppSettings.STYLE_GENTLE,
                        onClick = { vm.setReminderStyle(AppSettings.STYLE_GENTLE) },
                        label = { Text("温和", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = settings.reminderStyle == AppSettings.STYLE_STRICT,
                        onClick = { vm.setReminderStyle(AppSettings.STYLE_STRICT) },
                        label = { Text("严格", fontSize = 12.sp) },
                    )
                }
            }
        }

        // AI 分析 + 主题
        item {
            SettingsCard(title = "AI 与外观") {
                ToggleRow("允许 AI 分析（关闭后只用本地规则）", settings.aiAnalysisEnabled, vm::setAiAnalysisEnabled)
                Spacer(Modifier.height(8.dp))
                Text("主题", fontSize = 12.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.themeMode == AppSettings.THEME_SYSTEM,
                        onClick = { vm.setThemeMode(AppSettings.THEME_SYSTEM) },
                        label = { Text("跟随系统", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = settings.themeMode == AppSettings.THEME_LIGHT,
                        onClick = { vm.setThemeMode(AppSettings.THEME_LIGHT) },
                        label = { Text("浅色", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = settings.themeMode == AppSettings.THEME_DARK,
                        onClick = { vm.setThemeMode(AppSettings.THEME_DARK) },
                        label = { Text("深色", fontSize = 12.sp) },
                    )
                }
            }
        }

        // 数据管理
        item {
            SettingsCard(title = "数据与隐私") {
                Text(
                    "使用数据只保存在本机；只有生成 AI 报告时才会把聚合统计发给所配置的接口，不上传原始事件、不读取其他应用内容。",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirmClear = "usage" }) { Text("清空使用记录", fontSize = 12.sp) }
                    TextButton(onClick = { confirmClear = "behavior" }) { Text("清空行为事件", fontSize = 12.sp) }
                    TextButton(onClick = { confirmClear = "report" }) { Text("清空报告", fontSize = 12.sp) }
                }
            }
        }

        item {
            SettingsCard(title = "关于") {
                Text(
                    "AI Digital Wellbeing（AI 使用管家）v1.1\n" +
                        "本地统计 + 本地规则分析 + LLM Agent 报告；强制暂停基于无障碍服务实现。\n" +
                        "数据全部留在本机。",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, actionText: String, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            if (ok) "● 正常" else "● 未开启",
            fontSize = 11.sp,
            color = if (ok) colors.primary else colors.error,
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onAction) { Text(actionText, fontSize = 12.sp) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
