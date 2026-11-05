package com.aid.usageguard.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 保活引导（v1.8.3）：把「让系统别杀我」需要用户做的几步集中起来。
 * 普通应用无法直接改这些开关，只能跳转系统/厂商页面或给出 adb 命令。
 */
object KeepAliveGuide {

    private const val TAG = "KeepAliveGuide"

    /** 是否已在电池优化白名单（忽略电池优化） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 申请「忽略电池优化」（会弹系统确认框） */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (!isIgnoringBatteryOptimizations(context)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }.onFailure { openAppDetails(context) }
        }
    }

    /** 打开「自启动 / 后台运行」相关设置（vivo 优先，其它厂商回退到应用详情） */
    fun openAutoStartSettings(context: Context) {
        val candidates = listOf(
            // vivo
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            // vivo 高耗电管理
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.PurviewTabActivity",
            ),
            // 小米
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            // OPPO
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            // 华为
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        openAppDetails(context)
    }

    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure { Log.e(TAG, "open app details failed", it) }
    }

    fun openAccessibilitySettings(context: Context) = AccessibilityRestorer.openAccessibilitySettings(context)

    /** 授予自恢复权限的一次性 adb 命令 */
    fun adbGrantCommand(): String =
        "adb shell pm grant com.aid.usageguard android.permission.WRITE_SECURE_SETTINGS"
}
