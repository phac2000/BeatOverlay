package com.example.beatoverlay

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import java.io.File
import androidx.core.net.toUri

private const val SCREEN_HOME = 0
private const val SCREEN_PRESETS = 1
private const val SCREEN_SETTINGS = 2
private const val SCREEN_SETUP = 3
private const val COMPACT_DP = 72

// First-time setup steps (the notification step is skipped before Android 13)
private const val STEP_WELCOME = 0
private const val STEP_OVERLAY = 1
private const val STEP_NOTIF = 2
private const val STEP_SIZE = 3
private const val STEP_GESTURES = 4

private const val REQ_NOTIF = 1

private const val REQ_EXPORT = 100
private const val REQ_IMPORT = 101
private const val REQ_IMAGE = 102

// The Stop button next to the floating bar: its width, and the gap between the two.
private const val STOP_W_DP = 64
private const val STOP_GAP_DP = 8

/**
 * Three screens (Home, Presets, Settings) with a floating bar at the bottom holding
 * Home, Presets, Pause / Resume and Start / Stop.
 * Every setting is saved as soon as it changes; a running overlay is updated a moment later.
 */
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var p: Palette
    private lateinit var ui: Ui

    private var screen = SCREEN_HOME
    private var setupStep = 0
    private var insetTop = 0
    private var insetBottom = 0

    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var navHolder: FrameLayout
    private var applyPending = false          // what the Apply item in the bar currently shows
    private var applyItem: View? = null        // the Apply item (Home only), kept for its animation
    private var applyWasShown: Boolean? = null
    private var applyAnim: ValueAnimator? = null
    private lateinit var rootView: FrameLayout
    private var compactBar: View? = null
    private var switching = false
    private lateinit var stopBtn: View
    private var stopShown = false              // what the Stop button currently shows
    private var stopFade: ObjectAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable {
        if (overlayRunning) startForegroundService(configIntent())
    }

    // The overlay runs in its own process, so the app is told its state by messages.
    private var overlayRunning = false
    private var overlayPaused = false
    private var stateReceiverOn = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != OverlayService.ACTION_STATE) return
            handler.removeCallbacks(noAnswer)
            overlayRunning = intent.getBooleanExtra(OverlayService.EXTRA_RUNNING, false)
            overlayPaused = intent.getBooleanExtra(OverlayService.EXTRA_PAUSED, false)
            fillNav()
            updateSetupOverlayBtn()
        }
    }

    /** Runs when the overlay did not answer a ping: it is not running. */
    private val noAnswer = Runnable {
        if (overlayRunning || overlayPaused) {
            overlayRunning = false
            overlayPaused = false
            fillNav()
            updateSetupOverlayBtn()
        }
    }

    private fun sendToOverlay(action: String) =
        sendBroadcast(Intent(action).setPackage(packageName))

    private fun dp(v: Int) = ui.dp(v)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ask for the screen's top refresh rate (for example 120 Hz) so this window does not vote for 60.
        window.attributes = window.attributes.apply { preferredRefreshRate = highestRefreshRate(this@MainActivity) }
        store = SettingsStore(this)
        store.onChange = {
            handler.removeCallbacks(syncRunnable)
            handler.postDelayed(syncRunnable, 350)
        }
        store.onPendingChange = { updateApplyButton() }
        // The very first time the app opens, start with the setup screens.
        screen = savedInstanceState?.getInt("screen")
            ?: if (store.bool(K_SETUP_DONE)) SCREEN_HOME else SCREEN_SETUP
        setupStep = savedInstanceState?.getInt("setupStep") ?: 0
        render(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("screen", screen)
        outState.putInt("setupStep", setupStep)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(OverlayService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        stateReceiverOn = true
        sendToOverlay(OverlayService.ACTION_PING)           // ask the overlay for its state
        handler.removeCallbacks(noAnswer)
        handler.postDelayed(noAnswer, 700)
        // The overlay saves its position when it is dragged; show the new value if it changed.
        // The setup screens show live permission status, so rebuild them when coming back from Android's settings.
        if (store.syncPosition() || screen == SCREEN_SETUP) render(true)
        fillNav()
    }

    override fun onPause() {
        handler.removeCallbacks(noAnswer)
        if (stateReceiverOn) {
            unregisterReceiver(stateReceiver)
            stateReceiverOn = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            screen == SCREEN_SETUP -> {
                if (setupStep > 0) {
                    setupStep--
                    render(false)
                } else {
                    super.onBackPressed()
                }
            }
            screen != SCREEN_HOME -> go(SCREEN_HOME)
            store.hasPending() -> confirmLeaveHome { finish() }
            else -> super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- frame

    private fun currentPalette(): Palette = when (store.int(K_THEME)) {
        1 -> Palette.LIGHT
        2 -> Palette.DARK
        else -> {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) Palette.DARK else Palette.LIGHT
        }
    }

    private fun applySystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        window.setBackgroundDrawable(p.bg.toDrawable())
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        val layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        val flags = window.decorView.systemUiVisibility or layoutFlags
        window.decorView.systemUiVisibility = if (p.isDark) flags and light.inv() else flags or light
    }

    /** Builds the whole screen again (used when the theme or the page changes). */
    @SuppressLint("RtlHardcoded")
    private fun render(keepScroll: Boolean) {
        val keepY = if (keepScroll && ::scroll.isInitialized) scroll.scrollY else 0

        store.staging = screen == SCREEN_HOME      // Home edits wait for the Apply button
        p = currentPalette()
        ui = Ui(this, p, store)
        applySystemBars()

        val root = FrameLayout(this).apply {
            setBackgroundColor(p.bg)
            clipChildren = false
            clipToPadding = false
        }
        rootView = root
        compactBar = null
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            clipChildren = false
        }
        scroll = ScrollView(this).apply {
            clipToPadding = false
            clipChildren = false
            isFocusable = true
            isFocusableInTouchMode = true
            addView(page)
        }
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(p.navBg, 36)
            elevation = dp(8).toFloat()
            clipChildren = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        // The bar and the Stop button live in one holder. The bar sits at the left, Stop at the right
        // and underneath it; the bar is shifted right while Stop is hidden so it stays centred.
        stopBtn = ui.stopButton { stopService(Intent(this, OverlayService::class.java)) }
        navHolder = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        navHolder.addView(
            stopBtn,
            FrameLayout.LayoutParams(dp(STOP_W_DP), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.RIGHT)
        )
        navHolder.addView(
            navBar,
            FrameLayout.LayoutParams(navBarWidth(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.LEFT)
        )
        val groupWidth = navBarWidth() + dp(STOP_W_DP + STOP_GAP_DP)
        root.addView(
            navHolder,
            FrameLayout.LayoutParams(groupWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )
        // Stop must be exactly as tall as the bar. (A FrameLayout only re-measures a "match parent" child
        // when it has two or more of them, so a single one would stay at its content height.)
        navBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            val lp = stopBtn.layoutParams
            if (h > 0 && lp.height != h) {
                lp.height = h
                stopBtn.layoutParams = lp
            }
        }
        navHolder.visibility = if (screen == SCREEN_SETUP) View.GONE else View.VISIBLE
        setStopInstant(overlayRunning)

        when (screen) {
            SCREEN_HOME -> buildHome()
            SCREEN_PRESETS -> buildPresets()
            SCREEN_SETUP -> buildSetup()
            else -> buildSettings()
        }
        fillNav()

        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                insetTop = bars.top
                insetBottom = bars.bottom
            } else {
                insetTop = insets.systemWindowInsetTop
                insetBottom = insets.systemWindowInsetBottom
            }
            applyInsets()
            insets
        }
        setContentView(root)
        applyInsets()
        if (keepScroll) scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun applyInsets() {
        page.setPadding(dp(12), insetTop + dp(8), dp(12), insetBottom + dp(if (screen == SCREEN_SETUP) 24 else 128))
        compactBar?.let {
            it.layoutParams.height = insetTop + dp(COMPACT_DP)
            it.requestLayout()
        }
        (navHolder.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + dp(16)
        navHolder.requestLayout()
    }

    /** Changes screen. Leaving Home with edits that were not applied asks what to do with them first. */
    private fun go(target: Int) {
        if (screen == SCREEN_HOME && target != SCREEN_HOME && store.hasPending()) {
            confirmLeaveHome { goNow(target) }
        } else {
            goNow(target)
        }
    }

    private fun goNow(target: Int) {
        if (target == screen || switching) return
        val animate = !store.bool(K_REDUCED) && screen != SCREEN_SETUP && target != SCREEN_SETUP
        if (!animate) {
            screen = target
            render(false)
            return
        }
        val dir = if (target > screen) 1 else -1
        val dist = dp(40).toFloat()
        switching = true
        scroll.animate().translationX(-dir * dist).alpha(0f).setDuration(110)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                screen = target
                render(false)
                scroll.translationX = dir * dist
                scroll.alpha = 0f
                scroll.animate().translationX(0f).alpha(1f).setDuration(260)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
                switching = false
            }
            .start()
    }

    private fun confirmLeaveHome(then: () -> Unit) {
        AlertDialog.Builder(this, dialogStyle())
            .setTitle("Apply your changes?")
            .setMessage("You changed some Home settings but did not apply them.")
            .setPositiveButton("Apply") { _, _ ->
                store.applyPending()
                then()
            }
            .setNegativeButton("Discard") { _, _ ->
                store.discardPending()
                then()
            }
            .setNeutralButton("Keep editing", null)
            .show().also { ui.tickDialog(it) }
    }

    /** The Apply item in the bar looks different when changes are waiting, so rebuild the bar when that flips. */
    private fun updateApplyButton() {
        if (store.hasPending() == applyPending) return
        fillNav()
    }

    private fun onApplyClicked() {
        currentFocus?.clearFocus()   // commits a number that is still being typed
        if (!store.hasPending()) return
        store.applyPending()
        toast(if (overlayRunning) "Applied to the overlay" else "Applied")
        val item = applyItem
        if (item == null || store.bool(K_REDUCED)) updateApplyButton() else {
            // From here on nothing is waiting. The animation fades the item to its gray look, then the bar is rebuilt.
            // If a setting is edited meanwhile, updateApplyButton() rebuilds the bar straight away.
            applyPending = false
            ui.playApplied(item) { fillNav() }
        }
    }

    // ---------------------------------------------------------------- floating bar

    /**
     * The bar keeps one fixed width. Room is kept free on its right for the Stop button that
     * arrives in a later step, so nothing needs to resize when it appears.
     */
    private fun navBarWidth() =
        minOf(resources.displayMetrics.widthPixels - dp(32) - dp(STOP_W_DP + STOP_GAP_DP), dp(440))

    private fun fillNav() {
        if (!::navBar.isInitialized) return
        val running = overlayRunning
        val paused = overlayPaused
        applyPending = store.hasPending()
        navBar.removeAllViews()
        navBar.addView(
            ui.navItem(R.drawable.ic_home, "Home", screen == SCREEN_HOME, true) { go(SCREEN_HOME) },
            ui.lp(0, -2, 1f)
        )
        navBar.addView(
            ui.navItem(R.drawable.ic_tune, "Presets", screen == SCREEN_PRESETS, true) { go(SCREEN_PRESETS) },
            ui.lp(0, -2, 1f)
        )
        navBar.addView(
            ui.navItem(R.drawable.ic_settings, "Settings", screen == SCREEN_SETTINGS, true) { go(SCREEN_SETTINGS) },
            ui.lp(0, -2, 1f)
        )
        // Apply (Home only): grows in when Home opens, shrinks away when Home is left.
        applyAnim?.cancel()
        applyItem = null
        val showApply = screen == SCREEN_HOME
        val before = applyWasShown
        applyWasShown = showApply
        val animateSlot = before != null && before != showApply && !store.bool(K_REDUCED)
        if (showApply || animateSlot) {
            val apply = ui.navItem(
                R.drawable.ic_check, "Apply", false, applyPending,
                strong = applyPending, badge = applyPending, firmTick = true
            ) { onApplyClicked() }
            if (showApply) applyItem = apply
            apply.contentDescription = if (applyPending) "Apply changes" else "Apply changes, nothing to apply"
            ViewCompat.setStateDescription(apply, if (applyPending) "Changes waiting" else "Nothing to apply")
            if (!animateSlot) {
                navBar.addView(apply, ui.lp(0, -2, 1f))
            } else {
                val slot = FrameLayout(this).apply { clipChildren = false }
                val itemW = (navBarWidth() - dp(16)) / 5
                slot.addView(apply, FrameLayout.LayoutParams(itemW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                apply.isClickable = false
                val slotLp = ui.lp(0, -2, if (showApply) 0f else 1f)
                navBar.addView(slot, slotLp)
                apply.scaleX = if (showApply) 0f else 1f
                apply.scaleY = apply.scaleX
                apply.alpha = apply.scaleX
                val interpolator: android.view.animation.Interpolator =
                    if (showApply) OvershootInterpolator(2.2f) else AccelerateInterpolator()
                applyAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = if (showApply) 420L else 220L
                    addUpdateListener { a ->
                        val f = interpolator.getInterpolation(a.animatedFraction)
                        val t = if (showApply) f else 1f - f
                        slotLp.weight = t.coerceAtLeast(0f)
                        slot.layoutParams = slotLp
                        apply.scaleX = t
                        apply.scaleY = t
                        apply.alpha = t.coerceIn(0f, 1f)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        private var cancelled = false
                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }
                        override fun onAnimationEnd(animation: Animator) {
                            if (cancelled) return
                            applyAnim = null
                            fillNav()
                        }
                    })
                    start()
                }
            }
        }
        // One button for three jobs: Start when stopped, Pause while running, Resume while paused.
        val icon = if (running && !paused) R.drawable.ic_pause else R.drawable.ic_play
        val label = when {
            !running -> "Start"
            paused -> "Resume"
            else -> "Pause"
        }
        val main = ui.navItem(icon, label, false, enabled = true, filled = true) { onMainClicked() }
        main.contentDescription = "$label overlay"
        navBar.addView(main, ui.lp(0, -2, 1f))
        updateStop()
    }

    // ---------------------------------------------------------------- Stop button animation

    private fun stopShift() = dp(STOP_W_DP + STOP_GAP_DP).toFloat()

    private fun setStopAccessibility(show: Boolean) {
        stopBtn.contentDescription = if (show) "Stop overlay" else null
        stopBtn.importantForAccessibility =
            if (show) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Puts everything in its final place at once (used when the screen is built and for reduced motion). */
    private fun setStopInstant(show: Boolean) {
        stopFade?.cancel()
        stopBtn.animate().cancel()
        navBar.animate().cancel()
        stopShown = show
        navBar.translationX = if (show) 0f else stopShift() / 2f
        stopBtn.translationX = if (show) 0f else -stopShift()
        stopBtn.scaleX = if (show) 1f else 0.85f
        stopBtn.alpha = if (show) 1f else 0f
        stopBtn.visibility = if (show) View.VISIBLE else View.GONE
        setStopAccessibility(show)
    }

    /**
     * Stop slides out from underneath the bar with a bounce while the bar slides left, so the pair ends
     * up centred. When the overlay stops it plays backwards. A change in the middle of an animation
     * simply continues from where the views are now.
     */
    private fun updateStop() {
        val show = overlayRunning
        if (show == stopShown) return
        if (store.bool(K_REDUCED)) {
            setStopInstant(show)
            return
        }
        stopShown = show
        stopFade?.cancel()
        if (show) stopBtn.visibility = View.VISIBLE
        setStopAccessibility(show)

        val shift = stopShift()
        val scale = if (show) 1f else 0.85f
        val duration = 460L
        stopBtn.animate()
            .translationX(if (show) 0f else -shift)
            .scaleX(scale)
            .setDuration(duration)
            .setInterpolator(if (show) OvershootInterpolator(1.8f) else AccelerateDecelerateInterpolator())
            .start()
        navBar.animate()
            .translationX(if (show) 0f else shift / 2f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()

        // The corners of the bar are rounded, so the hidden button must not peek out from behind them.
        val fadeMs = 140L
        stopFade = ObjectAnimator.ofFloat(stopBtn, View.ALPHA, if (show) 1f else 0f).apply {
            this.duration = fadeMs
            startDelay = if (show) 0L else duration - fadeMs
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && !stopShown) stopBtn.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun onPauseClicked() {
        if (!overlayRunning) toast("Start the overlay first") else sendToOverlay(OverlayService.ACTION_PAUSE)
    }

    /** The combined button: Start when stopped, Pause while running, Resume while paused. */
    private fun onMainClicked() {
        if (!overlayRunning) startOverlay() else onPauseClicked()
    }

    // ---------------------------------------------------------------- shared pieces

    private fun topBar(title: String, back: Boolean = false, settingsButton: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        if (back) row.addView(ui.iconButton(R.drawable.ic_back, "Back") { go(SCREEN_HOME) }, ui.lp(dp(48), dp(48)))
        val t = ui.tv(title, 28f, p.text, true).apply { setPadding(if (back) dp(4) else dp(12), 0, 0, 0) }
        ui.heading(t)
        row.addView(t, ui.lp(0, -2, 1f))
        if (settingsButton) {
            row.addView(ui.iconButton(R.drawable.ic_settings, "Settings") { go(SCREEN_SETTINGS) }, ui.lp(dp(48), dp(48)))
        }
        page.addView(row)
    }

    // ---------------------------------------------------------------- collapsing header

    private fun logoView(): ImageView = ImageView(this).apply {
        setImageDrawable(packageManager.getApplicationIcon(applicationInfo))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setOval(0, 0, v.width, v.height)
            }
        }
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun versionLabel(): String = try {
        "v" + packageManager.getPackageInfo(packageName, 0).versionName
    } catch (_: Exception) {
        ""
    }

    private fun collapsingHeader(title: String, home: Boolean = false, showPreset: Boolean = true) {
        val bigH = (resources.displayMetrics.heightPixels * if (home) 0.5f else 0.3f)
            .toInt().coerceAtLeast(if (home) 0 else dp(210))
        val preset = if (showPreset) "Preset: " + store.currentPreset else ""
        val reduced = store.bool(K_REDUCED)

        // ---- big header (scrolls with the page)
        val big = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val bigLogo = logoView()
        big.addView(bigLogo, ui.lp(dp(112), dp(112)))
        val bigTitle = ui.tv(title, 32f, p.text, true)
        ui.heading(bigTitle)
        big.addView(bigTitle, ui.lp(-2, -2).apply { topMargin = dp(20) })
        val fadeViews = ArrayList<View>()
        if (home) {
            val tagline = ui.tv("Control and Focus on your beats", 17f, p.sub)
            val ver = ui.tv(versionLabel(), 14f, p.sub)
            big.addView(tagline, ui.lp(-2, -2).apply { topMargin = dp(10) })
            big.addView(ver, ui.lp(-2, -2).apply { topMargin = dp(6) })
            fadeViews.add(tagline)
            fadeViews.add(ver)
        }
        val bigPreset = ui.tv(preset, 14f, p.sub)
        big.addView(bigPreset, ui.lp(-2, -2).apply { topMargin = dp(if (home) 12 else 8) })
        page.addView(big, ui.lp(-1, bigH))

        // ---- small pinned bar
        val bar = FrameLayout(this).apply {
            setBackgroundColor(p.bg)
            alpha = 0f
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val smallLogo = logoView()
        val smallTitle = ui.tv(title, 22f, p.text, true).apply { setPadding(dp(12), 0, 0, 0) }
        val smallPreset = ui.tv(preset, 13f, p.sub)
        row.addView(smallLogo, ui.lp(dp(44), dp(44)))
        row.addView(smallTitle, ui.lp(0, -2, 1f))
        row.addView(smallPreset)
        bar.addView(row, FrameLayout.LayoutParams(-1, dp(COMPACT_DP), Gravity.BOTTOM))
        // Soft shadow under the pinned bar. It is a child of the bar, so it fades in and out with it.
        bar.clipChildren = false
        val shadow = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(if (p.isDark) 0x66000000 else 0x33000000, 0x00000000)
            )
            translationY = dp(24).toFloat()
        }
        bar.addView(shadow, FrameLayout.LayoutParams(-1, dp(24), Gravity.BOTTOM))
        rootView.addView(bar, FrameLayout.LayoutParams(-1, insetTop + dp(COMPACT_DP), Gravity.TOP))
        compactBar = bar

        if (reduced) {
            // Reduce motion: the old simple cross-fade.
            val from = bigH * 0.45f
            val to = bigH * 0.8f
            scroll.setOnScrollChangeListener { _, _, y, _, _ ->
                val f = ((y - from) / (to - from)).coerceIn(0f, 1f)
                bar.alpha = f
                bar.visibility = if (f == 0f) View.INVISIBLE else View.VISIBLE
                big.alpha = 1f - f
            }
            return
        }

        // ---- flying copies: they move from the big positions to the small ones
        val flyLogo = logoView().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val flyTitle = ui.tv(title, 32f, p.text, true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val flyPreset = ui.tv(preset, 14f, p.sub).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val flyers = listOf(flyLogo, flyTitle, flyPreset)
        flyLogo.layoutParams = FrameLayout.LayoutParams(dp(112), dp(112))
        for (f in flyers) {
            if (f.layoutParams == null) f.layoutParams = FrameLayout.LayoutParams(-2, -2)
            f.pivotX = 0f
            f.pivotY = 0f
            f.visibility = View.INVISIBLE
            rootView.addView(f)
        }
        // The small copies stay hidden; the flying ones are drawn in their place.
        smallLogo.alpha = 0f
        smallTitle.alpha = 0f
        smallPreset.alpha = 0f

        val bigViews = listOf(bigLogo, bigTitle, bigPreset)
        val smallViews = listOf(smallLogo, smallTitle, smallPreset)
        val smallInsetX = listOf(0, dp(12), 0) // the small title has 12 dp of left padding
        val scaleTo = listOf(44f / 112f, 22f / 32f, 13f / 14f)

        var e = 0f              // 0 = big, 1 = small (can overshoot a little while bouncing)
        var collapsed = false
        var firstEvent = true
        var anim: ValueAnimator? = null
        val rootLoc = IntArray(2)
        val loc = IntArray(2)

        fun place() {
            rootView.getLocationInWindow(rootLoc)
            for (i in 0 until 3) {
                bigViews[i].getLocationInWindow(loc)
                val bx = (loc[0] - rootLoc[0]).toFloat()
                val by = (loc[1] - rootLoc[1]).toFloat()
                smallViews[i].getLocationInWindow(loc)
                val sx = (loc[0] - rootLoc[0] + smallInsetX[i]).toFloat()
                val sy = (loc[1] - rootLoc[1]).toFloat()
                val v = flyers[i]
                v.translationX = bx + (sx - bx) * e
                v.translationY = by + (sy - by) * e
                val s = 1f + (scaleTo[i] - 1f) * e
                v.scaleX = s
                v.scaleY = s
            }
        }

        fun applyState(animating: Boolean) {
            val flying = e != 0f || animating
            for (i in 0 until 3) {
                flyers[i].visibility = if (flying) View.VISIBLE else View.INVISIBLE
                bigViews[i].alpha = if (flying) 0f else 1f
            }
            val fade = (1f - e).coerceIn(0f, 1f)
            for (v in fadeViews) v.alpha = fade
            val barA = e.coerceIn(0f, 1f)
            bar.alpha = barA
            bar.visibility = if (barA == 0f) View.INVISIBLE else View.VISIBLE
            if (flying) place()
        }

        fun goTo(target: Float, animate: Boolean) {
            anim?.cancel()
            if (!animate) {
                e = target
                applyState(false)
                return
            }
            val e0 = e
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (target > e0) 520L else 460L
                interpolator = OvershootInterpolator(0.5f)
                var ticked = false
                addUpdateListener {
                    e = e0 + (target - e0) * (it.animatedValue as Float)
                    applyState(true)
                    if (!ticked && it.currentPlayTime >= 330L) {   // the moment the title first lands
                        ticked = true
                        ui.tick(rootView, false)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        e = target
                        applyState(false)
                    }
                })
                start()
            }
        }

        val collapseAt = bigH * 0.35f
        val expandAt = bigH * 0.25f
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val want = if (collapsed) y > expandAt else y > collapseAt
            if (want != collapsed) {
                collapsed = want
                // After a rebuild (for example rotation) at a deep scroll position, jump straight there.
                goTo(if (want) 1f else 0f, !firstEvent)
            } else if (e != 0f) {
                place()
            }
            firstEvent = false
        }
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (e != 0f) place()
        }
    }

    // ---------------------------------------------------------------- First-time setup

    private fun setupSteps(): List<Int> = buildList {
        add(STEP_WELCOME)
        add(STEP_OVERLAY)
        if (Build.VERSION.SDK_INT >= 33) add(STEP_NOTIF)
        add(STEP_SIZE)
        add(STEP_GESTURES)
    }

    private fun overlayAllowed() = Settings.canDrawOverlays(this)

    private fun notificationsAllowed() =
        Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun buildSetup() {
        val steps = setupSteps()
        val index = setupStep.coerceIn(0, steps.size - 1)
        setupStep = index

        topBar("Setup")
        page.addView(ui.tv("Step ${index + 1} of ${steps.size}", 14f, p.sub).apply {
            setPadding(dp(16), 0, dp(16), dp(12))
        })

        var nextEnabled = true
        when (steps[index]) {
            STEP_WELCOME -> setupWelcome()
            STEP_OVERLAY -> nextEnabled = setupOverlayPermission()
            STEP_NOTIF -> setupNotifications()
            STEP_SIZE -> setupSize()
            else -> setupGestures()
        }
        setupButtons(index, steps.size, nextEnabled)
    }

    private fun setupWelcome() {
        val c = ui.card(page, "Welcome")
        ui.note(c, "Beat Overlay puts a thin rhythm bar on top of any app.")
        ui.note(c, "Beats scroll through the white line in the middle and your phone buzzes on every beat. " +
                "The tempo speeds up, drifts, pauses and rests on its own.")
        ui.note(c, "This quick setup takes about a minute.")
    }

    /** Returns true when the permission is granted (Next is only enabled then). */
    private fun setupOverlayPermission(): Boolean {
        val allowed = overlayAllowed()
        val c = ui.card(page, "Display over other apps")
        ui.note(c, "Beat Overlay needs this permission to draw the bar on top of other apps. " +
                "Without it the bar cannot appear.")
        c.target.addView(
            ui.tv(if (allowed) "✓ Allowed" else "Not allowed yet", 18f, p.text, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
            }
        )
        if (!allowed) {
            c.target.addView(
                ui.pillButton("Open permission settings", true) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
                },
                ui.lp(-1, dp(48)).apply { topMargin = dp(8) }
            )
            ui.note(c, "Find Beat Overlay in the list, switch it on, then come back here.")
        }
        return allowed
    }

    private fun setupNotifications() {
        val allowed = notificationsAllowed()
        val c = ui.card(page, "Notifications (optional)")
        ui.note(c, "While the overlay runs, Android shows a small \"Beat overlay running\" notification. " +
                "On Android 13 and newer it stays hidden unless you allow notifications. The overlay works either way.")
        c.target.addView(
            ui.tv(if (allowed) "✓ Allowed" else "Not allowed yet", 18f, p.text, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
            }
        )
        if (!allowed) {
            c.target.addView(
                ui.pillButton("Allow notifications", true) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                },
                ui.lp(-1, dp(48)).apply { topMargin = dp(8) }
            )
            ui.note(c, "If nothing appears, open Android's Settings, then Apps, Beat Overlay, Notifications.")
        }
    }

    private fun setupSize() {
        val c = ui.card(page, "Size and position")
        ui.note(c, "Choose how wide and thick the bar is and where it sits. " +
                "Press Show overlay to try the real bar; you can drag it up or down at any time.")
        ui.slider(c, K_BAR_W, "Width", 40, 100, "%")
        ui.slider(c, K_BAR_H, "Thickness", 24, 72, "dp")
        ui.slider(c, K_BAR_Y_PCT, "Vertical position", 0, 100, "%")
        c.target.addView(overlayToggleButton(), ui.lp(-1, dp(48)).apply { topMargin = dp(16) })
    }

    /** Last step: how to move the bar, what each gesture does right now, and a pad to try them on. */
    private fun setupGestures() {
        var c = ui.card(page, "Move the bar")
        ui.note(c, "Drag the bar up or down to move it anywhere on the screen. " +
                "Small ▲▼ arrows at its left end remind you; they fade out a few seconds after the overlay starts.")
        c.target.addView(overlayToggleButton(), ui.lp(-1, dp(48)).apply { topMargin = dp(12) })

        c = ui.card(page, "Gestures")
        GESTURE_KEYS.forEachIndexed { i, key ->
            c.target.addView(
                ui.tv("${GESTURE_NAMES[i]}: ${ACTION_LABELS[store.int(key)]}", 16f, p.text).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(2))
                }
            )
        }
        ui.note(c, "A single tap does nothing. You can change all of this later in Settings, under Gestures.")

        c = ui.card(page, "Try it")
        ui.note(c, "Use the pad like the real bar. Nothing is triggered here; it only shows what each gesture would do.")
        practicePad(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun practicePad(c: Card) {
        val label = ui.tv("Try the gestures here", 16f, p.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val pad = FrameLayout(this).apply {
            background = ui.rounded(p.field, 20)
            contentDescription = "Practice area for the overlay gestures"
        }
        pad.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Same recognizer as the real bar, so double tap waits for a third tap only if triple tap has an action.
        val gestures = OverlayGestures(this, object : OverlayGestures.Listener {
            @SuppressLint("SetTextI18n")
            override fun onDrag(dy: Float) {
                label.text = "↕ Drag: moves the bar up or down"
            }
            @SuppressLint("SetTextI18n")
            override fun onGesture(gesture: Int) {
                label.text = "${GESTURE_NAMES[gesture]}: ${ACTION_LABELS[store.int(GESTURE_KEYS[gesture])]}"
            }
            override fun hasHold() = store.int(K_G_HOLD) != ACT_NONE
            override fun hasTripleTap() = store.int(K_G_TRIPLE) != ACT_NONE
        })
        pad.setOnTouchListener { v, e ->
            // Keep the page from scrolling while a finger is on the pad.
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true)
            gestures.onTouch(v, e)
        }
        c.target.addView(pad, ui.lp(-1, dp(140)).apply { topMargin = dp(12) })
    }

    private fun setupButtons(index: Int, count: Int, nextEnabled: Boolean) {
        val last = index == count - 1
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        if (index > 0) {
            row.addView(
                ui.pillButton("Back", false) {
                    currentFocus?.clearFocus()
                    setupStep = index - 1
                    render(false)
                },
                ui.lp(0, dp(52), 1f).apply { marginEnd = dp(8) }
            )
        }
        val next = ui.pillButton(if (last) "Finish" else "Next", true) {
            currentFocus?.clearFocus()
            if (last) {
                finishSetup()
            } else {
                if (setupSteps()[index] == STEP_SIZE) hideSetupOverlay()
                setupStep = index + 1
                render(false)
            }
        }.apply {
            if (!nextEnabled) {
                isEnabled = false
                alpha = 0.4f
            }
        }
        row.addView(next, ui.lp(0, dp(52), 1f))
        page.addView(row)

        page.addView(
            ui.tv("Skip setup", 14f, p.sub).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(14))
                isClickable = true
                isFocusable = true
                contentDescription = "Skip setup"
                setOnClickListener { ui.tick(this, false); finishSetup() }
            },
            ui.lp(-1, -2)
        )
    }

    private fun finishSetup() {
        currentFocus?.clearFocus()
        hideSetupOverlay()
        store.setBool(K_SETUP_DONE, true)
        screen = SCREEN_HOME
        render(false)
    }

    /** Setup only previews the bar; it is hidden again when the preview step or the setup ends. */
    private fun hideSetupOverlay() {
        stopService(Intent(this, OverlayService::class.java))
    }

    private var setupOverlayBtn: android.widget.Button? = null

    /** One button for the setup steps: "Show overlay" when the bar is off, "Hide overlay" while it runs. */
    private fun overlayToggleButton(): android.widget.Button {
        val b = ui.pillButton(if (overlayRunning) "Hide overlay" else "Show overlay", true) {
            if (overlayRunning) hideSetupOverlay() else startOverlay()
        }
        setupOverlayBtn = b
        return b
    }

    private fun updateSetupOverlayBtn() {
        if (screen != SCREEN_SETUP) return
        setupOverlayBtn?.text = if (overlayRunning) "Hide overlay" else "Show overlay"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (screen == SCREEN_SETUP) render(true)
    }

    // ---------------------------------------------------------------- Home

    private fun buildHome() {
        collapsingHeader("Beat Overlay", home = true)
        page.addView(ui.tv("Changes take effect when you press Apply.", 13f, p.sub).apply {
            setPadding(dp(16), 0, dp(16), dp(12))
        })

        var c = ui.card(page, "Tempo")
        ui.slider(c, K_BPM, "Starting BPM", 30, 240, "bpm")
        ui.slider(c, K_GROWTH, "Speed up per minute", 0, 40, "%", "none")
        ui.slider(c, K_MAX, "Max BPM", 60, 300, "bpm")

        c = ui.card(page, "Random pauses")
        ui.toggle(c, K_PAUSES, "Enable pauses", gate = true)
        ui.slider(c, K_GAP_MIN, "Shortest gap between", 5, 300, "s")
        ui.slider(c, K_GAP_MAX, "Longest gap between", 5, 300, "s")
        ui.slider(c, K_LEN_MIN, "Shortest pause", 1, 60, "s")
        ui.slider(c, K_LEN_MAX, "Longest pause", 1, 60, "s")
        ui.slider(c, K_COUNTDOWN, "Restart countdown", 0, 10, "s", "off")

        c = ui.card(page, "Slow rest")
        ui.toggle(c, K_REST_ENABLED, "Enable slow rests", gate = true)
        ui.slider(c, K_REST_BPM, "Rest tempo", 20, 100, "bpm")
        ui.slider(c, K_REST_LEN_MIN, "Shortest rest", 1, 180, "s")
        ui.slider(c, K_REST_LEN_MAX, "Longest rest", 1, 180, "s")
        ui.slider(c, K_REST_SWAP, "Pause becomes a rest", 0, 100, "%", "never")
        ui.slider(c, K_REST_CHANCE, "Rest after a pause", 0, 100, "%", "never")

        c = ui.card(page, "Random rests")
        ui.note(c, "Works together with Slow rest above (it must be on).")
        ui.toggle(c, K_REST_RANDOM, "Rests can start by themselves", gate = true)
        ui.slider(c, K_REST_GAP_MIN, "Shortest gap between", 5, 300, "s")
        ui.slider(c, K_REST_GAP_MAX, "Longest gap between", 5, 300, "s")

        c = ui.card(page, "Random tempo drift")
        ui.toggle(c, K_CHAOS_ENABLED, "Enable random tempo drift", gate = true)
        ui.slider(c, K_CHAOS_START, "Starts after", 0, 20, "min", "start")
        ui.slider(c, K_CHAOS_PCT, "Drift amount (±)", 0, 50, "%", "none")
        ui.slider(c, K_CHAOS_GROW, "Drift grows by", 0, 10, "%/min", "none")
    }

    // ---------------------------------------------------------------- Presets

    private fun buildPresets() {
        collapsingHeader("Presets")

        val save = ui.card(page, "Save current settings")
        ui.note(save, "Saves the Home settings (tempo, pauses, rests, drift) under a name.")
        val name = ui.nameField("Preset name")
        save.target.addView(name, ui.lp(-1, -2).apply { topMargin = dp(8) })
        save.target.addView(
            ui.pillButton("Save preset", true) {
                val n = name.text.toString().trim()
                when {
                    n.isEmpty() -> toast("Type a name first")
                    store.isBuiltIn(n) -> toast("That name is used by a built-in preset")
                    else -> {
                        store.savePreset(n)
                        toast("Saved \"$n\"")
                        render(true)
                    }
                }
            },
            ui.lp(-1, dp(48)).apply { topMargin = dp(12) }
        )

        val mine = ui.card(page, "Your presets")
        val saved = store.userPresets()
        if (saved.isEmpty()) ui.note(mine, "Nothing saved yet.")
        saved.forEach { n ->
            presetRow(mine, n, onDelete = { confirmDelete(n) })
        }

        val built = ui.card(page, "Built-in")
        store.builtInPresets().forEach { n -> presetRow(built, n, onDelete = null) }
    }

    private fun presetRow(c: Card, name: String, onDelete: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(ui.tv(name, 16f, p.text), ui.lp(0, -2, 1f))
        if (name == store.currentPreset) {
            row.addView(ui.tv("Current", 13f, p.sub, true).apply { setPadding(0, 0, dp(10), 0) }, ui.lp(-2, -2))
        }
        if (onDelete != null) {
            row.addView(
                ui.pillButton("Delete", false) { onDelete() }.apply { contentDescription = "Delete preset $name" },
                ui.lp(-2, dp(40)).apply { marginEnd = dp(8) }
            )
        }
        row.addView(
            ui.pillButton("Apply", true) {
                store.applyPreset(name)
                toast("Applied \"$name\"")
                render(true)    // stay on Presets; refreshes the header name and the "Current" label
            }.apply { contentDescription = "Apply preset $name" },
            ui.lp(-2, dp(40))
        )
        c.target.addView(row)
    }

    private fun confirmDelete(name: String) {
        val style = if (p.isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        AlertDialog.Builder(this, style)
            .setTitle("Delete preset?")
            .setMessage("\"$name\" will be removed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                store.deletePreset(name)
                render(true)
            }
            .show().also { ui.tickDialog(it) }
    }

    // ---------------------------------------------------------------- Settings

    private fun buildSettings() {
        collapsingHeader("Settings", showPreset = false)

        var c = ui.card(page, "Appearance")
        ui.segmented(c, "Theme", listOf("System", "Light", "Dark"), store.int(K_THEME)) {
            store.setInt(K_THEME, it)
            render(true)
        }
        ui.slider(c, K_ALPHA_PCT, "Bar opacity", 10, 100, "%")
        ui.slider(c, K_EDGE_FADE, "Edge fade", 0, 50, "%", "off")

        c = ui.card(page, "Overlay bar")
        ui.slider(c, K_BAR_W, "Width", 40, 100, "%")
        ui.slider(c, K_BAR_H, "Thickness", 24, 72, "dp")
        ui.slider(c, K_BAR_Y_PCT, "Vertical position", 0, 100, "%")
        ui.toggle(c, K_SHOW_BPM, "Show BPM number")
        ui.note(c, "Shows the current tempo at the right end of the bar. PAUSE and the countdown always show.")
        ui.segmented(c, "Up / down arrows (▲▼)", listOf("Fade out", "Always", "Off"), store.int(K_CHEVRON_MODE)) {
            store.setInt(K_CHEVRON_MODE, it)
            render(true)
        }
        ui.note(c, "Drag the bar up or down to move it. The small arrows at its left end remind you.")

        buildMarkerCard()

        c = ui.card(page, "Motion")
        ui.toggle(c, K_REDUCED, "Reduce motion")
        ui.note(c, "The beat marker flashes in place instead of scrolling across the bar. " +
                "Turned on automatically if Android's animations are off.")

        c = ui.card(page, "Haptic feedback")
        ui.toggle(c, K_NAV_HAPTICS, "UI haptic feedback")
        ui.note(c, "A small tick on buttons, switches, choices and slider release. " +
                "It also follows Android's touch-feedback setting.")
        ui.toggle(c, K_HAPTICS, "Vibrate on every beat", gate = true)
        ui.slider(c, K_H_STRENGTH, "Vibration strength", 5, 100, "%")
        ui.slider(c, K_H_MS, "Vibration length", 10, 300, "ms")

        c = ui.card(page, "Gestures")
        ui.note(c, "Dragging the bar up or down always moves it. Choose what the other gestures do.")
        GESTURE_KEYS.forEachIndexed { i, key -> gestureRow(c, GESTURE_NAMES[i], key) }

        c = ui.card(page, "Setup and tutorial")
        ui.note(c, "Re-check the permissions and replay the gesture tutorial.")
        c.target.addView(
            ui.pillButton("Run setup again", false) {
                setupStep = 0
                go(SCREEN_SETUP)
            },
            ui.lp(-1, dp(48)).apply { topMargin = dp(8) }
        )

        c = ui.card(page, "Backup")
        ui.note(c, "Save or load all settings and presets as a file.")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(ui.pillButton("Export", true) { exportSettings() }, ui.lp(0, dp(48), 1f).apply { marginEnd = dp(8) })
        row.addView(ui.pillButton("Import", false) { importSettings() }, ui.lp(0, dp(48), 1f))
        c.target.addView(row)
    }

    // ---------------------------------------------------------------- Beat marker picture

    private fun markerFile() = File(filesDir, MARKER_FILE)

    private fun buildMarkerCard() {
        val c = ui.card(page, "Beat marker")
        val file = markerFile()
        val hasImage = file.exists()
        val usingImage = hasImage && store.bool(K_USE_IMAGE)

        ui.segmented(c, "Style", listOf("Circle", "Image"), if (usingImage) 1 else 0) { choice ->
            if (choice == 1) {
                if (markerFile().exists()) {
                    store.setBool(K_USE_IMAGE, true)
                    render(true)
                } else {
                    pickImage()   // nothing chosen yet: open the picker straight away
                }
            } else {
                store.setBool(K_USE_IMAGE, false)
                render(true)
            }
        }

        if (hasImage) {
            val bmp = BitmapFactory.decodeFile(file.path)
            if (bmp != null) {
                val preview = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = ui.rounded(p.field, 16)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    contentDescription = "Preview of your beat marker picture"
                }
                c.target.addView(preview, ui.lp(-1, dp(96)).apply { topMargin = dp(12) })
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(
            ui.pillButton(if (hasImage) "Change picture" else "Choose picture", true) { pickImage() },
            ui.lp(0, dp(48), 1f).apply { if (hasImage) marginEnd = dp(8) }
        )
        if (hasImage) row.addView(ui.pillButton("Remove", false) { removeMarkerImage() }, ui.lp(0, dp(48), 1f))
        c.target.addView(row)

        ui.slider(c, K_MARKER_SIZE, "Size", 50, 150, "%")
        ui.note(c, "PNG, JPG, WebP or SVG. Pictures with a transparent background work best. " +
                "Simple SVG shapes work; animations and some effects do not.")
    }

    private fun pickImage() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("image/png", "image/jpeg", "image/webp", "image/svg+xml")
                    )
                },
                REQ_IMAGE
            )
        } catch (_: Exception) {
            toast("No file picker found on this phone")
        }
    }

    /** Reads and shrinks the picture on a background thread, then switches the style to Image. */
    private fun importMarkerImage(uri: Uri) {
        toast("Loading picture…")
        val appContext = applicationContext
        Thread {
            val error = MarkerImage.import(appContext, uri)
            runOnUiThread {
                if (error == null) {
                    store.setBool(K_USE_IMAGE, true)
                    if (!isDestroyed) render(true)
                    toast("Picture set")
                } else {
                    toast(error)
                }
            }
        }.start()
    }

    private fun removeMarkerImage() {
        markerFile().delete()
        store.setBool(K_USE_IMAGE, false)
        render(true)
        toast("Picture removed")
    }

    private fun dialogStyle() =
        if (p.isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /** One gesture: its name on the left, and a button showing its action; tapping opens a list to choose from. */
    private fun gestureRow(c: Card, name: String, key: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(ui.tv(name, 16f, p.text), ui.lp(0, -2, 1f).apply { marginEnd = dp(8) })
        val pill = ui.pillButton(ACTION_LABELS[store.int(key)], false) {}
        pill.contentDescription = "$name: ${ACTION_LABELS[store.int(key)]}"
        pill.setOnClickListener {
            AlertDialog.Builder(this, dialogStyle())
                .setTitle(name)
                .setSingleChoiceItems(ACTION_LABELS.toTypedArray(), store.int(key)) { dialog, which ->
                    store.setInt(key, which)
                    pill.text = ACTION_LABELS[which]
                    pill.contentDescription = "$name: ${ACTION_LABELS[which]}"
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        row.addView(pill, ui.lp(-2, dp(40)))
        c.target.addView(row)
    }

    private fun exportSettings() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "beatoverlay-settings.json")
            },
            REQ_EXPORT
        )
    }

    private fun importSettings() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            REQ_IMPORT
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQ_IMAGE -> importMarkerImage(uri)
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(store.exportJson().toByteArray()) }
                    toast("Settings exported")
                }
                REQ_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    val error = store.importJson(text)
                    if (error == null) {
                        toast("Settings imported")
                        render(true)
                    } else {
                        toast(error)
                    }
                }
            }
        } catch (e: Exception) {
            toast("That did not work: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- start

    private fun configIntent(): Intent {
        store.syncPosition()
        val i = Intent(this, OverlayService::class.java)
        // Home settings that are waiting for Apply are not sent; the overlay gets the last applied values.
        store.values.forEach { (k, v) -> i.putExtra(k, if (k in SettingsStore.PRESET_INTS) store.appliedInt(k) else v) }
        store.flags.forEach { (k, v) -> i.putExtra(k, if (k in SettingsStore.PRESET_BOOLS) store.appliedBool(k) else v) }
        // Setup only previews the look of the bar, so the beat vibration stays off there (the saved setting is not changed).
        if (screen == SCREEN_SETUP) i.putExtra(K_HAPTICS, false)
        return i
    }

    private fun startOverlay() {
        currentFocus?.clearFocus()   // commits any number that is still being typed
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow \"Display over other apps\", then press Start again")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        startForegroundService(configIntent())
    }
}