package com.example.beatoverlay

import android.Manifest
import android.app.Activity
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File

private const val SCREEN_HOME = 0
private const val SCREEN_PRESETS = 1
private const val SCREEN_SETTINGS = 2

private const val REQ_EXPORT = 100
private const val REQ_IMPORT = 101
private const val REQ_IMAGE = 102

// The Stop button next to the floating bar: its width, and the gap between the two.
private const val STOP_W_DP = 72
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
    private var insetTop = 0
    private var insetBottom = 0

    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var navHolder: FrameLayout
    private lateinit var stopBtn: View
    private var stopShown = false              // what the Stop button currently shows
    private var stopFade: ObjectAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable {
        if (OverlayService.running) startForegroundService(configIntent())
    }

    private fun dp(v: Int) = ui.dp(v)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        store.onChange = {
            handler.removeCallbacks(syncRunnable)
            handler.postDelayed(syncRunnable, 350)
        }
        screen = savedInstanceState?.getInt("screen") ?: SCREEN_HOME
        render(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("screen", screen)
    }

    override fun onResume() {
        super.onResume()
        OverlayService.listener = { fillNav() }
        // The overlay saves its position when it is dragged; show the new value if it changed.
        if (store.syncPosition()) render(true)
        fillNav()
    }

    override fun onPause() {
        OverlayService.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (screen != SCREEN_HOME) go(SCREEN_HOME) else super.onBackPressed()
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
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        window.setBackgroundDrawable(p.bg.toDrawable())
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val flags = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility = if (p.isDark) flags and light.inv() else flags or light
    }

    /** Builds the whole screen again (used when the theme or the page changes). */
    private fun render(keepScroll: Boolean) {
        val keepY = if (keepScroll && ::scroll.isInitialized) scroll.scrollY else 0

        p = currentPalette()
        ui = Ui(this, p, store)
        applySystemBars()

        val root = FrameLayout(this).apply {
            setBackgroundColor(p.bg)
            clipChildren = false
            clipToPadding = false
        }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            clipToPadding = false
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
            outlineProvider = ViewOutlineProvider.BACKGROUND
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
            FrameLayout.LayoutParams(dp(STOP_W_DP), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
        )
        navHolder.addView(
            navBar,
            FrameLayout.LayoutParams(navBarWidth(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START)
        )
        root.addView(
            navHolder,
            FrameLayout.LayoutParams(
                navBarWidth() + dp(STOP_W_DP + STOP_GAP_DP),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )
        setStopInstant(OverlayService.running)

        when (screen) {
            SCREEN_HOME -> buildHome()
            SCREEN_PRESETS -> buildPresets()
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
        page.setPadding(dp(12), insetTop + dp(8), dp(12), insetBottom + dp(128))
        (navHolder.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + dp(16)
        navHolder.requestLayout()
    }

    private fun go(target: Int) {
        screen = target
        render(false)
    }

    // ---------------------------------------------------------------- floating bar

    /**
     * The bar keeps one fixed width. Room is kept free on its right for the Stop button that
     * arrives in a later step, so nothing needs to resize when it appears.
     */
    private fun navBarWidth() =
        minOf(resources.displayMetrics.widthPixels - dp(24) - dp(STOP_W_DP + STOP_GAP_DP), dp(480))

    private fun fillNav() {
        if (!::navBar.isInitialized) return
        val running = OverlayService.running
        val paused = OverlayService.paused
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
        // One button for three jobs: Start when stopped, Pause while running, Resume while paused.
        val icon = if (running && !paused) R.drawable.ic_pause else R.drawable.ic_play
        val label = when {
            !running -> "Start"
            paused -> "Resume"
            else -> "Pause"
        }
        val main = ui.navItem(icon, label, selected = false, enabled = true, filled = true) { onMainClicked() }
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
        stopBtn.scaleY = if (show) 1f else 0.85f
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
        val show = OverlayService.running
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
            .scaleX(scale).scaleY(scale)
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
        val service = OverlayService.instance
        if (service == null) toast("Start the overlay first") else service.togglePause()
    }

    /** The combined button: Start when stopped, Pause while running, Resume while paused. */
    private fun onMainClicked() {
        if (!OverlayService.running) startOverlay() else onPauseClicked()
    }

    // ---------------------------------------------------------------- shared pieces

    private fun topBar(title: String, back: Boolean = false, settingsButton: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        if (back) {
            row.addView(ui.iconButton(R.drawable.ic_back, "Back") { go(SCREEN_HOME) }, ui.lp(dp(48), dp(48)))
        } else {
            val logo = ImageView(this).apply {
                setImageResource(R.drawable.ic_logo)
                contentDescription = "Beat Overlay logo"
            }
            row.addView(logo, ui.lp(dp(36), dp(36)).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        val t = ui.tv(title, 28f, p.text, true).apply { setPadding(dp(8), 0, 0, 0) }
        ui.heading(t)
        row.addView(t, ui.lp(0, -2, 1f))
        if (settingsButton) {
            row.addView(ui.iconButton(R.drawable.ic_settings, "Settings") { go(SCREEN_SETTINGS) }, ui.lp(dp(48), dp(48)))
        }
        page.addView(row)
    }

    // ---------------------------------------------------------------- Home

    private fun buildHome() {
        topBar("Beat Overlay")

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
        ui.slider(c, K_CHAOS_START, "Starts after", 0, 20, "min", "start")
        ui.slider(c, K_CHAOS_PCT, "Drift amount (±)", 0, 50, "%", "none")
        ui.slider(c, K_CHAOS_GROW, "Drift grows by", 0, 10, "%/min", "none")
    }

    // ---------------------------------------------------------------- Presets

    private fun buildPresets() {
        topBar("Presets")

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
                go(SCREEN_HOME)
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
            .show()
    }

    // ---------------------------------------------------------------- Settings

    private fun buildSettings() {
        topBar("Settings", back = true)

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
        ui.toggle(c, K_NAV_HAPTICS, "Vibrate on navigation buttons")
        ui.toggle(c, K_HAPTICS, "Vibrate on every beat", gate = true)
        ui.slider(c, K_H_STRENGTH, "Vibration strength", 5, 100, "%")
        ui.slider(c, K_H_MS, "Vibration length", 10, 300, "ms")

        c = ui.card(page, "Gestures")
        ui.note(c, "Dragging the bar up or down always moves it. Choose what the other gestures do.")
        GESTURE_KEYS.forEachIndexed { i, key -> gestureRow(c, GESTURE_NAMES[i], key) }

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
            ui.pillButton("Use app logo", false) { useAppLogo() },
            ui.lp(0, dp(48), 1f).apply { marginEnd = dp(8) }
        )
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

    private fun useAppLogo() {
        toast("Loading logo…")
        val appContext = applicationContext
        Thread {
            val bytes = resources.openRawResource(R.raw.logo_beatoverlay).readBytes()
            val error = MarkerImage.importFromBytes(appContext, bytes)
            runOnUiThread {
                if (error == null) {
                    store.setBool(K_USE_IMAGE, true)
                    if (!isDestroyed) render(true)
                    toast("App logo set as beat marker")
                } else {
                    toast(error)
                }
            }
        }.start()
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
        store.values.forEach { (k, v) -> i.putExtra(k, v) }
        store.flags.forEach { (k, v) -> i.putExtra(k, v) }
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
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(configIntent())
    }
}