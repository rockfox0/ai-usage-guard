package com.aid.usageguard.data

import android.content.Context
import android.provider.Settings

object Access {

    /** 「使用情况访问」是否已授权（AppOps GET_USAGE_STATS）。 */
    fun usageAccessGranted(context: Context): Boolean =
        runCatching { UsageEventSource(context).hasUsageAccess() }.getOrDefault(false)

    /** 本应用的无障碍服务是否已在系统里启用。 */
    fun accessibilityGranted(context: Context): Boolean {
        val enabled = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) {
            0
        }
        if (enabled != 1) return false
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = context.packageName
        return services.split(':').any { it.trim().startsWith("$me/") }
    }
}
