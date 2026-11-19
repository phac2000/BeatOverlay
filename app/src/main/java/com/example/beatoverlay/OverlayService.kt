package com.example.beatoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.roundToInt

/**
 * The highest refresh rate the screen offers at its current resolution (for example 120f).
 * Returns 0f ("no preference") when it cannot be found.
 */
fun highestRefreshRate(context: Context): Float {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0f
    val current = display.mode
    var best = current.refreshRate
    for (m in display.supportedModes) {
        if (m.physicalWidth == current.physicalWidth && m.physicalHeight == current.physicalHeight &&
            m.refreshRate > best
        ) best = m.refreshRate
    }
    return best
}

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bar: BeatBarView? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var gestures: OverlayGestures

    private val handler = Handler(Looper.getMainLooper())

    // Shown to the app through broadcasts (the overlay runs in its own process).
    private var running = false
    private var paused = false

    /** Answers the app: "ping" = tell me your state, "pause" = toggle pause. */
    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PING -> notifyState()
                ACTION_PAUSE -> if (bar != null) togglePause()
            }
        }
    }

    // Layout (from settings) and what each gesture does. The defaults match SettingsStore.
    private var barW = 90
    private var barH = 36
    private var barYPct = 8
    private val actions = intArrayOf(ACT_OPEN, ACT_PAUSE, ACT_NONE, ACT_NONE, ACT_NONE)

    private var dragging = false
    private var imageStamp = -1L
    private val unhide = Runnable { setHidden(false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(ACTION_PING)
            addAction(ACTION_PAUSE)
        }
        ContextCompat.registerReceiver(this, commands, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        running = true

        if (bar == null) createBar()

        if (intent != null) {
            configure({ k, d -> intent.getIntExtra(k, d) }, { k, d -> intent.getBooleanExtra(k, d) })
        } else {
            // Android restarted the service after killing it: use the saved settings.
            val store = SettingsStore(this)
            readSavedY()?.let { store.values[K_BAR_Y_PCT] = it }
            configure({ k, d -> store.values[k] ?: d }, { k, d -> store.flags[k] ?: d })
        }
        notifyState()
        return START_STICKY
    }

    private fun createBar() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val store = SettingsStore(this)
        barW = store.int(K_BAR_W)
        barH = store.int(K_BAR_H)
        barYPct = readSavedY() ?: store.int(K_BAR_Y_PCT)

        val view = BeatBarView(this)
        params = WindowManager.LayoutParams(
            0, 0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // The bar redraws every frame. Asking for the top refresh rate keeps it from pulling the screen down to 60 Hz.
            preferredRefreshRate = highestRefreshRate(this@OverlayService)
        }
        computeLayout()

        gestures = OverlayGestures(this, gestureListener)
        view.setOnTouchListener(gestures)
        wm.addView(view, params)
        bar = view
    }

    private fun configure(getInt: (String, Int) -> Int, getBool: (String, Boolean) -> Boolean) {
        val b = bar ?: return
        val c = b.config
        c.bpm = getInt(K_BPM, c.bpm)
        c.growthPct = getInt(K_GROWTH, c.growthPct)
        c.maxBpm = getInt(K_MAX, c.maxBpm)

        c.pausesEnabled = getBool(K_PAUSES, c.pausesEnabled)
        c.gapMinSec = getInt(K_GAP_MIN, c.gapMinSec)
        c.gapMaxSec = getInt(K_GAP_MAX, c.gapMaxSec)
        c.lenMinSec = getInt(K_LEN_MIN, c.lenMinSec)
        c.lenMaxSec = getInt(K_LEN_MAX, c.lenMaxSec)
        c.countdownSec = getInt(K_COUNTDOWN, c.countdownSec)

        c.restEnabled = getBool(K_REST_ENABLED, c.restEnabled)
        c.restBpm = getInt(K_REST_BPM, c.restBpm)
        c.restLenMinSec = getInt(K_REST_LEN_MIN, c.restLenMinSec)
        c.restLenMaxSec = getInt(K_REST_LEN_MAX, c.restLenMaxSec)
        c.restSwapPct = getInt(K_REST_SWAP, c.restSwapPct)
        c.restChancePct = getInt(K_REST_CHANCE, c.restChancePct)
        c.restSwapOn = getBool(K_REST_SWAP_ON, c.restSwapOn)
        c.restChanceOn = getBool(K_REST_CHANCE_ON, c.restChanceOn)
        c.restRandom = getBool(K_REST_RANDOM, c.restRandom)
        c.restGapMinSec = getInt(K_REST_GAP_MIN, c.restGapMinSec)
        c.restGapMaxSec = getInt(K_REST_GAP_MAX, c.restGapMaxSec)

        c.chaosStartMin = getInt(K_CHAOS_START, c.chaosStartMin)
        c.chaosPct = getInt(K_CHAOS_PCT, c.chaosPct)
        c.chaosGrowPct = getInt(K_CHAOS_GROW, c.chaosGrowPct)
        c.chaosEnabled = getBool(K_CHAOS_ENABLED, c.chaosEnabled)

        c.haptics = getBool(K_HAPTICS, c.haptics)
        c.hapticStrengthPct = getInt(K_H_STRENGTH, c.hapticStrengthPct)
        c.hapticMs = getInt(K_H_MS, c.hapticMs)

        b.alpha = getInt(K_ALPHA_PCT, (b.alpha * 100).toInt()) / 100f
        b.edgeFadePct = getInt(K_EDGE_FADE, b.edgeFadePct)
        b.reducedMotion = getBool(K_REDUCED, b.reducedMotion)
        b.showBpm = getBool(K_SHOW_BPM, b.showBpm)
        b.chevronMode = getInt(K_CHEVRON_MODE, b.chevronMode)
        b.markerScalePct = getInt(K_MARKER_SIZE, b.markerScalePct)
        updateMarkerImage(b, getBool(K_USE_IMAGE, false))

        GESTURE_KEYS.forEachIndexed { i, key -> actions[i] = getInt(key, actions[i]) }

        if (!dragging) {
            barW = getInt(K_BAR_W, barW)
            barH = getInt(K_BAR_H, barH)
            barYPct = getInt(K_BAR_Y_PCT, barYPct)
            computeLayout()
            wm.updateViewLayout(b, params)
        }
    }

    /** Loads the chosen picture (only when the file changed) or goes back to circles. */
    private fun updateMarkerImage(b: BeatBarView, use: Boolean) {
        val f = File(filesDir, MARKER_FILE)
        if (!use || !f.exists()) {
            b.markerBitmap = null
            imageStamp = -1L
            return
        }
        val stamp = f.lastModified() xor f.length()
        if (stamp == imageStamp && b.markerBitmap != null) return
        imageStamp = stamp
        b.markerBitmap = BitmapFactory.decodeFile(f.path)
    }

    // ------------------------------------------------------------ layout and position

    private fun computeLayout() {
        val dm = resources.displayMetrics
        params.width = (dm.widthPixels * barW / 100f).toInt().coerceAtLeast((120 * dm.density).toInt())
        params.height = (barH * dm.density).toInt()
        params.y = (maxY() * barYPct / 100f).toInt()
    }

    private fun maxY(): Int = (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0)

    private fun setY(y: Int) {
        val b = bar ?: return
        params.y = y.coerceIn(0, maxY())
        wm.updateViewLayout(b, params)
    }

    /** The position saved by the last drag, or null if there is none. */
    private fun readSavedY(): Int? = try {
        File(filesDir, POS_FILE).readText().trim().toIntOrNull()?.coerceIn(0, 100)
    } catch (e: Exception) {
        null
    }

    /** Remembers where the bar was dropped, in a small file the app reads when it opens. */
    private fun savePosition() {
        val m = maxY()
        barYPct = if (m == 0) 0 else (params.y * 100f / m).roundToInt().coerceIn(0, 100)
        try {
            File(filesDir, POS_FILE).writeText(barYPct.toString())
        } catch (e: Exception) {
            // Not saved: the bar simply opens at its old position next time.
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val b = bar ?: return
        computeLayout()
        wm.updateViewLayout(b, params)
    }

    // ------------------------------------------------------------ gestures

    private val gestureListener = object : OverlayGestures.Listener {
        private var startY = 0

        override fun onTouch(down: Boolean) {
            bar?.chevronsTouch(down)
        }

        override fun onDragStart() {
            dragging = true
            startY = params.y
        }

        override fun onDrag(dy: Float) {
            setY(startY + dy.roundToInt())
        }

        override fun onDragEnd() {
            dragging = false
            savePosition()
        }

        override fun onGesture(gesture: Int) {
            val action = actions[gesture]
            if (action == ACT_NONE) return
            bar?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            runAction(action)
        }

        override fun hasHold() = actions[GES_HOLD] != ACT_NONE
        override fun hasTripleTap() = actions[GES_TRIPLE] != ACT_NONE
    }

    private fun runAction(action: Int) {
        when (action) {
            ACT_OPEN -> openApp()
            ACT_PAUSE -> togglePause()
            ACT_STOP -> stopSelf()
            ACT_REST -> if (bar?.forceRest() == false) {
                Toast.makeText(this, "Turn on Slow rest in the app first", Toast.LENGTH_SHORT).show()
            }
            ACT_RESET -> bar?.resetTempo()
            ACT_HIDE -> hideFor(10_000L)
        }
    }

    /** Freezes the bar until called again; resuming runs the countdown first. */
    fun togglePause() {
        val b = bar ?: return
        b.setManualPause(!b.manualPause)
        paused = b.manualPause
        notifyState()
    }

    /** Hides the bar (touches pass through to the app below); the rhythm keeps going underneath. */
    private fun hideFor(ms: Long) {
        setHidden(true)
        handler.removeCallbacks(unhide)
        handler.postDelayed(unhide, ms)
    }

    private fun setHidden(hide: Boolean) {
        val b = bar ?: return
        b.hidden = hide
        params.flags = if (hide) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        wm.updateViewLayout(b, params)
        if (!hide) b.pokeChevrons()
    }

    private fun notifyState() {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_PAUSED, paused)
        )
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Beat overlay", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Beat overlay running")
            .setContentText("Tap to open settings")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::gestures.isInitialized) gestures.cancel()
        bar?.let { wm.removeView(it) }
        bar = null
        running = false
        paused = false
        notifyState()
        unregisterReceiver(commands)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "overlay"

        // Messages between the app and the overlay (they are separate processes). Only this app can send or hear them.
        const val ACTION_STATE = "com.example.beatoverlay.STATE"    // overlay -> app: running, paused
        const val ACTION_PING = "com.example.beatoverlay.PING"      // app -> overlay: send your state
        const val ACTION_PAUSE = "com.example.beatoverlay.PAUSE"    // app -> overlay: toggle pause
        const val EXTRA_RUNNING = "running"
        const val EXTRA_PAUSED = "paused"

        /** Holds the bar's vertical position (0-100) after a drag; the app reads it when it opens. */
        const val POS_FILE = "bar_y_pct.txt"
    }
}