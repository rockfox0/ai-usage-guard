package com.aid.usageguard.restriction

import android.content.Context
import com.aid.usageguard.data.UsageEventSource

/**
 * 「临时使用 1 分钟」的次数限制：每个应用每天只能用一次。
 * 记录按自然日（本地时区）失效，不需要跨天清理。
 */
class TempAllowStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("temp_allow", Context.MODE_PRIVATE)

    fun usedToday(packageName: String): Boolean =
        sp.getString(key(packageName), null) == UsageEventSource.todayDate()

    fun markUsedToday(packageName: String) {
        sp.edit().putString(key(packageName), UsageEventSource.todayDate()).apply()
    }

    private fun key(packageName: String) = "used_$packageName"
}
