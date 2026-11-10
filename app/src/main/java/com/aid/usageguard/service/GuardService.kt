package com.aid.usageguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.restriction.RestrictionMode
import com.aid.usageguard.restriction.RestrictionPolicy
import com.aid.usageguard.restriction.RuleValidator
import com.aid.usageguard.domain.quota.FocusWindow
import com.aid.usageguard.domain.quota.QuotaPolicy
import com.aid.usageguard.domain.quota.QuotaReason
import com.aid.usageguard.domain.quota.QuotaState
import com.aid.usageguard.restriction.TempAllowStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 「强制暂停」执行层：监听前台窗口，命中限制规则（Room restriction_rule）就盖全屏无障碍遮罩。
 *
 * 规则来源由 AppRestrictionController 维护；Agent 只能提出建议，
 * 必须经用户确认 + RuleValidator 校验才会写进规则表，所以这里只做执行。
 */
class GuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var overlay: View? = null
    private var overlayPkg: String? = null
    private var countdownView: TextView? = null
    private var elapsedRunnable: Runnable? = null

    private var lastForegroundPkg: String? = null
    private var tempAllowPkg: String? = null
    private var tempAllowUntil = 0L

    /** 用户手动勾选的限制（默认限制模式下作为额外信息） */
    @Volatile
    private var explicitBlocked: Set<String> = emptySet()

    @Volatile
    private var allowedPackages: Set<String> = emptySet()

    @Volatile
    private var restrictByDefault: Boolean = false

    @Volatile
    private var restrictionMode: RestrictionMode = RestrictionMode.QUOTA_AFTER_BUDGET

    @Volatile
    private var protectedPackages: Set<String> = emptySet()

    private val tempAllowStore by lazy { TempAllowStore(this) }

    // ---- 时长额度（v1.8）：全局每日额度 + 单应用每日限额 ----
    @Volatile
    private var dailyBudgetEnforced = true

    @Volatile
    private var dailyBudgetMinutes = 300

    @Volatile
    private var appLimits: Map<String, Int> = emptyMap()

    // ---- 专注时段（v1.8.2）：时段内只允许白名单应用 ----
    @Volatile
    private var focusWindowEnabled = false

    @Volatile
    private var focusWindowStart = 22

    @Volatile
    private var focusWindowEnd = 7

    private var quotaDate: String = ""
    private var quotaTotalMs: Long = 0L
    private val quotaPerAppMs = HashMap<String, Long>()

    // ---- 实时提示（陪伴模式） ----
    private var foregroundPkg: String? = null
    private var foregroundSince = 0L
    private var lastNudgeAt = 0L
    private var hintView: View? = null
    private var nudgeEnabled = true
    private var nudgeIntervalMinutes = 15
    private var nudgeUseAi = false

    private var tickCount = 0
    private val nudgeTicker = object : Runnable {
        override fun run() {
            // v1.10.1：额度改为「数据库为准」——手工累加墙钟时间会把桌面/系统 UI/
            // 拦截浮层/息屏时间也算进「今日已用」，导致显示 445 分钟而实际只有 3 小时。
            runCatching { syncQuotaFromDatabase() }
            runCatching { enforceQuotaMidSession() }
            runCatching { maybeNudge() }
            tickCount++
            if (tickCount % 3 == 0) {
                scope.launch { runCatching { appLimits = Container.quota.limits() } }
            }
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "onServiceConnected")
        runCatching { Container.init(this) }
        // 保活：无障碍连上后拉起常驻通知服务（v1.8.3）
        runCatching { KeepAliveService.start(this) }
        scope.launch {
            runCatching {
                // 桌面启动器 + 输入法永远不能限制，否则用户无法回到桌面/打字
                // 系统应用（含 OEM 的手机管家/系统导航/相机等）一律保护：
                // 默认限制模式只应作用于用户安装的第三方应用，避免把系统 UI 拦死
                protectedPackages = Container.usageSource.launcherPackages() +
                    inputMethodPackages() +
                    systemPackages()
                // 同步给 RuleValidator，让 Agent 的白名单工具也拒绝系统保护应用
                RuleValidator.registerProtected(protectedPackages)
                Log.i(TAG, "protected packages=${protectedPackages.size} (含输入法)")
            }
        }
        scope.launch {
            runCatching {
                Container.restrictions.observeBlocked().collect { explicitBlocked = it }
            }.onFailure { Log.e(TAG, "observe restrictions failed", it) }
        }
        // 额度基线：今天已用总时长 + 各应用时长 + 单应用限额
        scope.launch {
            runCatching {
                quotaDate = UsageEventSource.todayDate()
                val snapshot = Container.quota.todayUsage()
                quotaTotalMs = snapshot.totalMinutes * 60_000L
                quotaPerAppMs.clear()
                snapshot.perAppMinutes.forEach { (pkg, minutes) ->
                    quotaPerAppMs[pkg] = minutes * 60_000L
                }
                appLimits = Container.quota.limits()
                Log.i(TAG, "quota baseline total=${snapshot.totalMinutes}m apps=${quotaPerAppMs.size} limits=${appLimits.size}")
            }.onFailure { Log.e(TAG, "quota baseline failed", it) }
        }
        handler.postDelayed(nudgeTicker, 20_000L)
        // v1.9.4：服务重启/被冻结恢复时，若用户正停在某个应用内，立即按当前窗口重新判定，
        // 不等下一次窗口切换（否则会出现"已经超额但还能一直用"）
        handler.postDelayed({ runCatching { reevaluateCurrentForeground() } }, 3_000L)
        scope.launch {
            runCatching {
                Container.settings.settings.collect { settings ->
                    allowedPackages = settings.allowedPackages
                    restrictByDefault = settings.restrictByDefault
                    restrictionMode = RestrictionMode.parse(settings.restrictionMode)
                    nudgeEnabled = settings.realtimeNudgeEnabled
                    nudgeIntervalMinutes = settings.nudgeIntervalMinutes.coerceIn(1, 120)
                    nudgeUseAi = settings.nudgeUseAi
                    dailyBudgetEnforced = settings.dailyBudgetEnforced
                    dailyBudgetMinutes = settings.dailyTargetMinutes
                    focusWindowEnabled = settings.focusWindowEnabled
                    focusWindowStart = settings.focusWindowStart
                    focusWindowEnd = settings.focusWindowEnd
                }
            }.onFailure { Log.e(TAG, "observe settings failed", it) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val isSystemUi = pkg == "com.android.systemui" || pkg.contains("systemui")
        lastForegroundPkg = pkg

        resetQuotaIfNewDay()

        // 限制模式（v1.9.1 修正）：
        // - QUOTA_AFTER_BUDGET：额度内所有应用放行（手动名单不参与），超额交给 QuotaPolicy；
        // - ALWAYS_NON_WHITELIST：始终拦非白名单（手动名单不参与）；
        // - MANUAL_ONLY：只拦手动指定。
        val (blocked, quota) = evaluateBlocked(pkg)
        Log.d(
            TAG,
            "win-change pkg=$pkg blocked=$blocked mode=$restrictionMode quota=${quota.reason} " +
                "total=${quota.totalUsedMinutes}/${quota.globalBudgetMinutes}m " +
                "app=${quota.appUsedMinutes}/${quota.appLimitMinutes ?: "-"}m allowed=${allowedPackages.size}",
        )
        when {
            blocked -> {
                resetForegroundTracking()
                val now = System.currentTimeMillis()
                if (tempAllowPkg == pkg && now < tempAllowUntil) return
                showOverlay(pkg, quota.takeIf { it.blocked })
            }
            // 切到别的应用时收起；下拉通知栏这类系统 UI 不收起，避免绕过
            overlay != null && !isSystemUi -> hideOverlay()
        }

        if (!blocked) {
            if (pkg == packageName || isSystemUi || pkg in protectedPackages && pkg !in allowedPackages) {
                // 桌面/系统界面/本应用：不算「连续使用某个 App」
                if (pkg == packageName || pkg in protectedPackages && pkg !in allowedPackages) {
                    resetForegroundTracking()
                }
            } else if (foregroundPkg != pkg) {
                foregroundPkg = pkg
                foregroundSince = System.currentTimeMillis()
                lastNudgeAt = 0L
            }
        }
    }

    /** v1.9.4：以无障碍当前窗口重新判定前台应用（服务重启 / 冻结恢复后调用） */
    private fun reevaluateCurrentForeground() {
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return
        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return
        resetQuotaIfNewDay()
        val (blocked, quota) = evaluateBlocked(pkg)
        Log.i(TAG, "reevaluate foreground pkg=$pkg blocked=$blocked reason=${quota.reason}")
        if (blocked) {
            val now = System.currentTimeMillis()
            if (tempAllowPkg == pkg && now < tempAllowUntil) return
            showOverlay(pkg, quota.takeIf { it.blocked })
        }
    }

    private fun showOverlay(pkg: String, quota: QuotaState? = null) {
        if (overlayPkg == pkg && overlay != null) return
        hideOverlay()

        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F20B1020"))
            isClickable = true
            isFocusable = true
            setPadding(dp(28), dp(48), dp(28), dp(48))
        }

        root.addView(ImageView(this).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).also { it.gravity = Gravity.CENTER }
            alpha = 0.9f
        })

        root.addView(TextView(this).apply {
            text = when (quota?.reason) {
                QuotaReason.GLOBAL_BUDGET_EXCEEDED -> "今日额度已用完"
                QuotaReason.APP_LIMIT_EXCEEDED -> "今日用量已超限"
                QuotaReason.FOCUS_WINDOW -> "专注时段"
                else -> "已强制暂停"
            }
            setTextColor(Color.parseColor("#FF6B81"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(8))
            letterSpacing = 0.15f
        })

        root.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        countdownView = TextView(this).apply {
            text = "已暂停 0 秒"
            setTextColor(Color.parseColor("#A9B4C9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(countdownView)

        root.addView(TextView(this).apply {
            text = when (quota?.reason) {
                QuotaReason.GLOBAL_BUDGET_EXCEEDED ->
                    "今天已经用满 ${quota.globalBudgetMinutes} 分钟额度（已用 ${quota.totalUsedMinutes} 分钟）。\n" +
                        "除了允许名单里的应用，其他应用今天都不能再用了。"

                QuotaReason.APP_LIMIT_EXCEEDED ->
                    "这个应用今天限额 ${quota.appLimitMinutes} 分钟，已经用了 ${quota.appUsedMinutes} 分钟。\n" +
                        "明天再来，或者去设置里调整限额。"

                QuotaReason.FOCUS_WINDOW ->
                    "现在是专注时段（${FocusWindow.label(focusWindowStart, focusWindowEnd)}），\n" +
                        "这段时间只能使用白名单里的应用。"

                else -> "这个应用当前被限制使用。\n先做点别的事，或者回到桌面。"
            }
            setTextColor(Color.parseColor("#C7D0E0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(28))
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        root.addView(actionButton("返回桌面", true) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            hideOverlay()
        })
        val tempUsed = tempAllowStore.usedToday(pkg)
        root.addView(actionButton(
            if (tempUsed) "今日已用过临时使用" else "临时使用 1 分钟",
            false,
        ) {
            if (tempUsed) return@actionButton
            tempAllowStore.markUsedToday(pkg)
            tempAllowPkg = pkg
            tempAllowUntil = System.currentTimeMillis() + 60_000L
            hideOverlay()
            handler.postDelayed({
                if (lastForegroundPkg == pkg) {
                    tempAllowPkg = null
                    tempAllowUntil = 0L
                    showOverlay(pkg)
                }
            }, 60_000L)
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(root, params)
            overlay = root
            overlayPkg = pkg
            startElapsedTicker()
            Log.i(TAG, "overlay shown for $pkg")
        }.onFailure { Log.e(TAG, "overlay failed for $pkg", it) }
    }

    private fun actionButton(text: String, primary: Boolean, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = text
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        button.isAllCaps = false
        button.setTextColor(if (primary) Color.parseColor("#0B1020") else Color.WHITE)
        button.background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(if (primary) Color.parseColor("#7C8CF8") else Color.parseColor("#1E2740"))
            if (!primary) setStroke(dp(1), Color.parseColor("#33415C"))
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        lp.topMargin = dp(10)
        button.layoutParams = lp
        button.setOnClickListener { onClick() }
        return button
    }

    private fun startElapsedTicker() {
        stopElapsedTicker()
        val started = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val seconds = (System.currentTimeMillis() - started) / 1000
                countdownView?.text = "已暂停 $seconds 秒"
                handler.postDelayed(this, 1000L)
            }
        }
        elapsedRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun stopElapsedTicker() {
        elapsedRunnable?.let { handler.removeCallbacks(it) }
        elapsedRunnable = null
    }

    private fun hideOverlay() {
        stopElapsedTicker()
        val view = overlay ?: return
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        }
        overlay = null
        overlayPkg = null
        countdownView = null
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(nudgeTicker)
        hideHintView()
        hideOverlay()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * v1.10.1：每 20 秒从数据库同步一次额度（屏幕亮着时顺带触发增量采集）。
     *
     * 旧实现按「前台切换/20 秒 ticker」手工累加墙钟时间，且 total 累加时不过滤包名，
     * 会把桌面、系统 UI、拦截浮层、息屏时间都算进「今日已用」，数值只增不减。
     */
    private fun syncQuotaFromDatabase() {
        scope.launch {
            runCatching {
                val interactive = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
                if (interactive) Container.usage.refreshIfStale(60_000L)
                refreshQuotaFromDatabase()
            }
        }
    }

    /**
     * v1.9.4：用数据库里的今日统计校正内部计数（取 max）。
     *
     * 为什么需要：服务被冻结/同步延迟时内部计数会偏小，导致"超过限额仍可使用"。
     * 数据库只增不减，取 max 不会重复累加，也不会把计数改小。
     */
    private suspend fun refreshQuotaFromDatabase() {
        runCatching {
            val snapshot = Container.quota.todayUsage()
            quotaDate = UsageEventSource.todayDate()
            quotaTotalMs = snapshot.totalMinutes * 60_000L
            quotaPerAppMs.clear()
            snapshot.perAppMinutes.forEach { (pkg, minutes) ->
                quotaPerAppMs[pkg] = minutes * 60_000L
            }
            Log.i(TAG, "quota sync from DB: total=${snapshot.totalMinutes}m apps=${snapshot.perAppMinutes.size} date=$quotaDate")
            // 同步后立即对当前前台应用再判一次（额度变化可能刚好触发拦截）
            enforceQuotaMidSession()
        }.onFailure { Log.w(TAG, "refresh quota from DB failed", it) }
    }

    private fun resetQuotaIfNewDay() {
        val today = UsageEventSource.todayDate()
        if (quotaDate != today) {
            quotaDate = today
            quotaTotalMs = 0L
            quotaPerAppMs.clear()
            scope.launch { runCatching { refreshQuotaFromDatabase() } }
        }
    }

    /** 当前是否处于专注时段（时段内只有白名单应用可用） */
    private fun focusWindowActive(): Boolean {
        if (!focusWindowEnabled) return false
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return FocusWindow.isActive(hour, focusWindowStart, focusWindowEnd)
    }

    /** 统一判定：限制规则 + 额度（v1.9.4 抽出，供窗口事件与服务重启后复用） */
    private fun evaluateBlocked(pkg: String): Pair<Boolean, QuotaState> {
        val alwaysBlockNonWhitelist = restrictionMode == RestrictionMode.ALWAYS_NON_WHITELIST
        val activeManualBlocks = RestrictionPolicy.manualBlocksFor(restrictionMode, explicitBlocked)
        val ruleBlocked = RestrictionPolicy.isRestricted(
            packageName = pkg,
            allowedPackages = allowedPackages,
            explicitBlocked = activeManualBlocks,
            restrictByDefault = alwaysBlockNonWhitelist,
            protectedPackages = protectedPackages,
        )
        val quota = quotaState(pkg)
        return (ruleBlocked || quota.blocked) to quota
    }

    /** 当前应用是否触发额度拦截（UI / AI / Service 共用 QuotaPolicy） */
    private fun quotaState(pkg: String): QuotaState = QuotaPolicy.evaluate(
        packageName = pkg,
        totalUsedMinutes = quotaTotalMs / 60_000L,
        globalBudgetMinutes = dailyBudgetMinutes,
        globalEnforced = dailyBudgetEnforced,
        appUsedMinutes = (quotaPerAppMs[pkg] ?: 0L) / 60_000L,
        appLimitMinutes = appLimits[pkg],
        whitelisted = pkg in allowedPackages,
        protected = pkg in protectedPackages,
        focusWindowActive = focusWindowActive(),
        focusWindowLabel = FocusWindow.label(focusWindowStart, focusWindowEnd),
    )

    /** 停留在同一应用超过额度时也要拦（不能等切换应用） */
    private fun enforceQuotaMidSession() {
        // v1.9.4：浮层被系统移除但引用还在时，重新挂载，避免"看起来没拦"
        if (overlay != null && overlay?.isAttachedToWindow != true) {
            hideOverlay()
        }
        if (overlay != null) return
        val pkg = foregroundPkg ?: return
        if (tempAllowPkg == pkg && System.currentTimeMillis() < tempAllowUntil) return
        val state = quotaState(pkg)
        if (state.blocked) {
            Log.i(TAG, "quota blocked mid-session pkg=$pkg reason=${state.reason}")
            showOverlay(pkg, state)
            resetForegroundTracking()
        }
    }

    private fun resetForegroundTracking() {
        foregroundPkg = null
        foregroundSince = 0L
        lastNudgeAt = 0L
    }

    /** 连续使用同一 App 达到阈值时给一句提示（可选用 AI 生成文案） */
    private fun maybeNudge() {
        if (!nudgeEnabled) return
        val pkg = foregroundPkg ?: return
        val dwellMs = System.currentTimeMillis() - foregroundSince
        val intervalMs = nudgeIntervalMinutes * 60_000L
        if (dwellMs < intervalMs) return
        if (lastNudgeAt != 0L && System.currentTimeMillis() - lastNudgeAt < intervalMs) return
        lastNudgeAt = System.currentTimeMillis()

        val minutes = (dwellMs / 60_000L).coerceAtLeast(1L)
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val local = LocalHints.forApp(appName, minutes, nudgeIntervalMinutes)

        if (nudgeUseAi) {
            scope.launch {
                val outcome = runCatching {
                    Container.agentRuntime.quickHint(appName, minutes)
                }.getOrNull()
                val text = outcome?.takeIf { it.ok && it.text.isNotBlank() }?.text ?: local
                showHintView(text)
            }
        } else {
            showHintView(local)
        }
    }

    private fun showHintView(text: String) {
        hideHintView()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F21B2338"))
                setStroke(dp(1), Color.parseColor("#33415C"))
            }
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        card.addView(TextView(this).apply {
            this.text = "AI 陪伴"
            setTextColor(Color.parseColor("#8FA0FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        card.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            val side = dp(16)
            x = 0
            y = dp(96)
            horizontalMargin = 0f
            width = WindowManager.LayoutParams.MATCH_PARENT
        }

        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(card, params)
            hintView = card
            handler.postDelayed({ hideHintView() }, 8_000L)
            Log.i(TAG, "hint shown: $text")
        }.onFailure { Log.e(TAG, "hint show failed", it) }
    }

    private fun hideHintView() {
        val view = hintView ?: return
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        }
        hintView = null
    }

    /** 预装/系统应用包名（含 OEM 定制应用），默认限制模式下不限制 */
    private fun systemPackages(): Set<String> = runCatching {
        packageManager.getInstalledApplications(0)
            .asSequence()
            .filter {
                (it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            }
            .map { it.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /** 当前启用的输入法包名：默认限制模式下必须豁免，否则键盘会被拦掉 */
    private fun inputMethodPackages(): Set<String> = runCatching {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.inputMethodList.mapNotNull { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val TAG = "UsageGuard"
    }
}
