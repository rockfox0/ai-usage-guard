package com.aid.usageguard.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 无障碍自恢复（v1.8.3 保活）：
 *
 * 拦截依赖 [GuardService]（无障碍服务）。OEM（尤其 vivo/小米）在「清理后台」时
 * 会把无障碍开关一起关掉，导致保护失效。
 *
 * 若应用被授予 `WRITE_SECURE_SETTINGS`（可通过 adb 一次性授予，见设置页提示），
 * 就可以把 `enabled_accessibility_services` 重新写回并打开开关，实现自愈；
 * 没有该权限时降级为「通知用户手动开启」。
 */
object AccessibilityRestorer {

    private const val TAG = "AccessibilityRestorer"

    /** 本应用的无障碍组件名（与 AndroidManifest 一致） */
    const val COMPONENT = "com.aid.usageguard/com.aid.usageguard.service.GuardService"

    enum class Result {
        /** 已经开启，无需处理 */
        ALREADY_ON,

        /** 本次成功恢复 */
        RESTORED,

        /** 没有 WRITE_SECURE_SETTINGS，无法自动恢复 */
        NO_PERMISSION,

        /** 写入失败（系统拒绝/OEM 遮挡） */
        FAILED,
    }

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled && services.split(':').any { it.equals(COMPONENT, ignoreCase = true) }
    }

    fun hasWritePermission(context: Context): Boolean =
        context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 尝试把本应用重新写入无障碍开关。
     * 调用方应保证在后台线程调用（写 Settings 是轻量 IO，但避免主线程磁盘写）。
     */
    fun tryRestore(context: Context): Result {
        if (isEnabled(context)) return Result.ALREADY_ON
        if (!hasWritePermission(context)) return Result.NO_PERMISSION
        return runCatching {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val services = current.split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(COMPONENT, ignoreCase = true) }
                .toMutableList()
            services.add(COMPONENT)
            val okList = Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.joinToString(":"),
            )
            val okEnabled = Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1,
            )
            if (okList && okEnabled && isEnabled(context)) {
                Log.i(TAG, "accessibility service restored")
                Result.RESTORED
            } else {
                Log.w(TAG, "restore write not effective (list=$okList enabled=$okEnabled)")
                Result.FAILED
            }
        }.getOrElse {
            Log.e(TAG, "restore failed", it)
            Result.FAILED
        }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "open accessibility settings failed", it) }
    }
}
