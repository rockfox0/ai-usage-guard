package com.aid.usageguard.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.util.Calendar
import java.util.Locale

/** 一条原始 UsageEvent（只保留归并会话需要的字段） */
data class RawUsageEvent(
    val packageName: String,
    val type: Int,
    val time: Long,
)

/**
 * UsageStatsManager 的薄封装：权限检查、原始事件读取、应用信息查询。
 * 统计逻辑在 [UsageIngester] 里，避免把系统 API 细节散落到各处。
 */
class UsageEventSource(private val context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val pm: PackageManager = context.packageManager

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun readEvents(from: Long, to: Long): List<RawUsageEvent> {
        val result = ArrayList<RawUsageEvent>()
        val events = usm.queryEvents(from, to) ?: return result
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            result.add(RawUsageEvent(pkg, event.eventType, event.timeStamp))
        }
        return result
    }

    /** 桌面启动器包名：统计时排除，避免把「停在家里」算成使用时长 */
    fun launcherPackages(): Set<String> = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** 可启动应用列表（packageName -> 应用名） */
    fun launcherVisibleApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, 0)
        return resolved.mapNotNull { ri ->
            val info = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
            info.packageName to info.loadLabel(pm).toString()
        }.distinctBy { it.first }
    }

    fun appNameOf(pkg: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun firstInstallTime(pkg: String): Long = try {
        pm.getPackageInfo(pkg, 0).firstInstallTime
    } catch (e: PackageManager.NameNotFoundException) {
        0L
    }

    companion object {
        fun todayDate(): String = dateOf(System.currentTimeMillis())

        fun dateOf(ts: Long): String {
            val cal = Calendar.getInstance()
            cal.timeInMillis = ts
            return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )
        }

        fun startOfDay(ts: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = ts
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun startOfDay(date: String): Long = try {
            val parts = date.split("-")
            val cal = Calendar.getInstance()
            cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (e: Exception) {
            startOfDay(System.currentTimeMillis())
        }

        fun addDays(date: String, days: Int): String =
            dateOf(startOfDay(date) + days * DAY_MS)

        fun dayLabel(date: String): String = date.takeLast(5).replace("-", "/")

        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
