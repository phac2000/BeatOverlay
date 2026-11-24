package com.example.beatoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

/**
 * Scrolling rhythm track. Beat markers travel from the right through a hit-line in the
 * middle of the bar and carry on to the left.
 *  - Tempo grows exponentially every minute (up to a cap).
 *  - After a while the tempo jumps randomly; the size of the jumps keeps growing slowly.
 *  - Random pauses freeze the track, then a countdown runs before it restarts.
 *  - Slow "rests" (low tempo) can follow a pause, replace a pause, or start by themselves.
 *  - Each beat (and each countdown tick) can vibrate, with adjustable strength and duration.
 *  - The ends of the bar fade out; small up / down chevrons hint that the bar can be dragged.
 *  - The beat marker can be a circle or a picture the user chose.
 */
class BeatBarView(context: Context) : View(context) {

    class Config(
        var bpm: Int = 67,
        var growthPct: Int = 0,         // % tempo growth per minute (compounding)
        var maxBpm: Int = 300,          // cap for the ramp (before randomness)

        var haptics: Boolean = true,
        var hapticStrengthPct: Int = 100,  // vibration strength, 1-100 %
        var hapticMs: Int = 35,            // vibration duration in milliseconds

        var pausesEnabled: Boolean = false,
        var gapMinSec: Int = 13,       // time between pauses
        var gapMaxSec: Int = 21,
        var lenMinSec: Int = 6,        // pause length
        var lenMaxSec: Int = 10,
        var countdownSec: Int = 3,     // countdown before restarting after a pause (0 = off)

        var restEnabled: Boolean = false,
        var restChancePct: Int = 11,   // chance a rest follows a pause
        var restSwapPct: Int = 20,     // chance a scheduled pause turns into a rest instead
        var restSwapOn: Boolean = false,    // "pause becomes a rest" switch
        var restChanceOn: Boolean = false,  // "rest after a pause" switch
        var restBpm: Int = 74,
        var restLenMinSec: Int = 8,    // rest length
        var restLenMaxSec: Int = 15,
        var restRandom: Boolean = false, // rests may also start by themselves
        var restGapMinSec: Int = 20,   // time between self-started rests
        var restGapMaxSec: Int = 40,

        var chaosStartMin: Int = 1,    // randomness starts after this many minutes (0 = from the start)
        var chaosPct: Int = 50,        // starting max +/- tempo jump in percent (0 = off)
        var chaosGrowPct: Int = 10,    // percentage points added to the jump size every minute
        var chaosEnabled: Boolean = false, // master switch for random tempo drift
    )

    var config = Config()

    /** Left and right ends fade to transparent. Percent of the bar width per side; 0 = off. */
    var edgeFadePct = 20
        set(value) {
            val v = value.coerceIn(0, 50)
            if (v == field) return      // same value: keep the fade as it is
            field = v
            fadeShader = null
            invalidate()
        }

    /** 0 = chevrons fade out a few seconds after start / touch, 1 = always visible, 2 = never. */
    var chevronMode = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Size of the beat marker in percent (100 = normal). */
    var markerScalePct = 100

    /** A picture to use instead of the circle, or null for circles. */
    var markerBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** While true nothing is drawn, but the rhythm and the vibration carry on. */
    var hidden = false
        set(value) {
            field = value
            invalidate()
        }

    /** Beat markers flash in place instead of scrolling across the bar. */
    var reducedMotion = false
    var showBpm = false

    /** True while the user has paused the bar by hand (everything is frozen, timers included). */
    var manualPause = false
        private set

    fun setManualPause(on: Boolean) {
        if (on) {
            manualPause = true
        } else if (manualPause) {
            manualPause = false
            pauseRemainingMs = 0
            restRemainingMs = 0
            countdownRemainingMs = config.countdownSec * 1000L
            lastCountdownDigit = -1
            lastFrame = 0
        }
        invalidate()
    }

    /** Starts a slow rest right now. Returns false when slow rests are switched off. */
    fun forceRest(): Boolean {
        if (!config.restEnabled) return false
        pauseRemainingMs = 0
        countdownRemainingMs = 0
        startRest()
        return true
    }

    /** Puts the speed-up and the random drift back to the start. */
    fun resetTempo() {
        elapsedMs = 0
        chaosOffset = 0f
        beatsUntilChaosPick = 0
    }

    /** Shows the chevrons for [holdMs] (then they fade). Called when the bar starts. */
    fun pokeChevrons(holdMs: Long = 4000) {
        chevronUntil = SystemClock.elapsedRealtime() + holdMs + CHEVRON_FADE_MS
        invalidate()
    }

    /** A finger is on the bar: keep the chevrons visible until it lifts, then let them fade. */
    fun chevronsTouch(down: Boolean) {
        chevronHeld = down
        if (!down) pokeChevrons(1500)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pokeChevrons()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fadeShader = null
    }

    private val visibleBeats = 2.5f   // beats that fit between the center line and the right edge

    // Timeline state
    private var lastFrame = 0L
    private var elapsedMs = 0L
    private var phase = 0f
    private var lastBeatIndex = 0
    private var untilPauseMs = -1L
    private var pauseRemainingMs = 0L
    private var countdownRemainingMs = 0L
    private var lastCountdownDigit = -1
    private var restRemainingMs = 0L
    private var untilRestMs = -1L

    // Random drift
    private var shownBpm = 0f
    private var chaosOffset = 0f
    private var beatsUntilChaosPick = 0

    // Chevrons
    private var chevronUntil = 0L
    private var chevronHeld = false

    // The BPM label only changes a few times a second, so its text is built once and reused.
    private var labelKey = -1
    private var labelText = ""

    private var fadeShader: LinearGradient? = null

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val line = Paint().apply {
        color = Color.WHITE; strokeWidth = 10f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND
    }
    private val marker = Paint().apply { color = Color.rgb(255, 80, 120); isAntiAlias = true }
    private val restMarker = Paint().apply { color = Color.rgb(90, 200, 255); isAntiAlias = true }
    private val glow = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val text = Paint().apply {
        color = Color.WHITE; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val imgDst = RectF()
    // Gives a picture a blue tint during a slow rest, like the circle turns blue.
    private val restFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.55f, 0f, 0f, 0f, 0f,
                0f, 0.85f, 0f, 0f, 25f,
                0f, 0f, 1.25f, 0f, 60f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    private val chevronLine = Paint().apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val chevronPath = Path()

    private fun minutes() = elapsedMs / 60000.0

    /** Exponential ramp: start * (1 + g)^minutes, capped. */
    private fun rampedBpm(): Float {
        val g = 1.0 + config.growthPct / 100.0
        val v = config.bpm * g.pow(minutes())
        return v.coerceAtMost(maxOf(config.bpm, config.maxBpm).toDouble()).toFloat()
    }

    private fun chaosActive() =
        config.chaosEnabled && (config.chaosPct > 0 || config.chaosGrowPct > 0) && minutes() >= config.chaosStartMin

    /** Current max jump size (fraction). Starts at chaosPct and keeps growing every minute. */
    private fun chaosAmount(): Float {
        val sinceStart = (minutes() - config.chaosStartMin).coerceAtLeast(0.0)
        val pct = config.chaosPct + config.chaosGrowPct * sinceStart
        return (pct.coerceAtMost(90.0) / 100.0).toFloat()
    }

    private fun targetBpm(): Float {
        if (restRemainingMs > 0) return config.restBpm.toFloat()
        var b = rampedBpm()
        if (chaosActive()) b *= (1f + chaosOffset)
        return b.coerceIn(20f, 400f)
    }

    private fun between(min: Int, max: Int): Int {
        val lo = minOf(min, max); val hi = maxOf(min, max)
        return Random.nextInt(lo, hi + 1)
    }

    private fun startRest() {
        restRemainingMs = between(config.restLenMinSec, config.restLenMaxSec) * 1000L
        untilRestMs = -1
    }

    private fun step(dt: Long) {
        if (manualPause) return
        elapsedMs += dt

        if (config.pausesEnabled) {
            if (pauseRemainingMs > 0) {
                pauseRemainingMs -= dt
                if (pauseRemainingMs <= 0) {
                    pauseRemainingMs = 0
                    untilPauseMs = between(config.gapMinSec, config.gapMaxSec) * 1000L
                    countdownRemainingMs = config.countdownSec * 1000L
                    lastCountdownDigit = -1
                    if (config.restEnabled && config.restChanceOn && Random.nextInt(100) < config.restChancePct) startRest()
                }
            } else if (countdownRemainingMs > 0) {
                countdownRemainingMs = (countdownRemainingMs - dt).coerceAtLeast(0)
                val digit = ceil(countdownRemainingMs / 1000f).toInt()
                if (digit > 0 && digit != lastCountdownDigit) {
                    lastCountdownDigit = digit
                    if (config.haptics) buzz()
                }
            } else {
                if (untilPauseMs < 0) untilPauseMs = between(config.gapMinSec, config.gapMaxSec) * 1000L
                untilPauseMs -= dt
                if (untilPauseMs <= 0) {
                    untilPauseMs = -1
                    if (config.restEnabled && config.restSwapOn && Random.nextInt(100) < config.restSwapPct) {
                        startRest()                      // this "pause" becomes a slow rest
                    } else {
                        pauseRemainingMs = between(config.lenMinSec, config.lenMaxSec) * 1000L
                        restRemainingMs = 0
                    }
                }
            }
        } else {
            pauseRemainingMs = 0
            countdownRemainingMs = 0
            untilPauseMs = -1
        }

        if (pauseRemainingMs == 0L && countdownRemainingMs == 0L) {
            if (!config.restEnabled) {
                restRemainingMs = 0
                untilRestMs = -1
            } else if (restRemainingMs > 0) {
                restRemainingMs = (restRemainingMs - dt).coerceAtLeast(0)
            } else if (config.restRandom) {
                if (untilRestMs < 0) untilRestMs = between(config.restGapMinSec, config.restGapMaxSec) * 1000L
                untilRestMs -= dt
                if (untilRestMs <= 0) startRest()
            } else {
                untilRestMs = -1
            }

            shownBpm = targetBpm()   // tempo changes take effect instantly
            phase += dt * shownBpm / 60000f
            val idx = floor(phase).toInt()
            if (idx > lastBeatIndex) {
                lastBeatIndex = idx
                if (config.haptics) buzz()
                if (chaosActive() && --beatsUntilChaosPick <= 0) {
                    val a = chaosAmount()
                    chaosOffset = (Random.nextFloat() * 2f - 1f) * a   // instant jump
                    beatsUntilChaosPick = between(3, 8)
                }
            }
        }
    }

    private fun buzz() {
        val v = vibrator ?: return
        val amplitude =
            if (v.hasAmplitudeControl()) (config.hapticStrengthPct * 255 / 100).coerceIn(1, 255)
            else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(config.hapticMs.coerceAtLeast(1).toLong(), amplitude))
    }

    /** Draws the marker (a circle, or the chosen picture fitted inside the same circle). */
    private fun drawMarker(canvas: Canvas, x: Float, y: Float, r: Float, mk: Paint, resting: Boolean) {
        val bmp = markerBitmap
        if (bmp == null || bmp.isRecycled) {
            canvas.drawCircle(x, y, r, mk)
            return
        }
        val s = (2f * r) / maxOf(bmp.width, bmp.height)
        val halfW = bmp.width * s / 2f
        val halfH = bmp.height * s / 2f
        imgDst.set(x - halfW, y - halfH, x + halfW, y + halfH)
        imgPaint.alpha = mk.alpha
        imgPaint.colorFilter = if (resting) restFilter else null
        canvas.drawBitmap(bmp, null, imgDst, imgPaint)
    }

    private fun chevronAlpha(now: Long): Float {
        if (chevronMode == 2) return 0f
        val base = if (chevronMode == 1) 0.55f else 0f
        val touch = when {
            chevronHeld -> 1f
            reducedMotion -> if (chevronUntil > now) 1f else 0f
            else -> ((chevronUntil - now) / CHEVRON_FADE_MS.toFloat()).coerceIn(0f, 1f)
        }
        return maxOf(base, touch)
    }

    /** Two small arrows, one pointing up and one down, at the left end: "drag me up or down". */
    private fun drawChevrons(canvas: Canvas, h: Float, alpha: Float) {
        val cx = h * 0.75f
        val cy = h / 2f
        val half = h * 0.17f
        val rise = h * 0.1f
        val offset = h * 0.17f

        chevronPath.reset()
        // up chevron (^)
        chevronPath.moveTo(cx - half, cy - offset + rise)
        chevronPath.lineTo(cx, cy - offset - rise)
        chevronPath.lineTo(cx + half, cy - offset + rise)
        // down chevron (v)
        chevronPath.moveTo(cx - half, cy + offset - rise)
        chevronPath.lineTo(cx, cy + offset + rise)
        chevronPath.lineTo(cx + half, cy + offset - rise)

        val stroke = h * 0.075f
        chevronLine.strokeWidth = stroke + h * 0.05f            // dark outline so it shows on light apps too
        chevronLine.color = Color.argb((alpha * 140).toInt(), 0, 0, 0)
        canvas.drawPath(chevronPath, chevronLine)
        chevronLine.strokeWidth = stroke
        chevronLine.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        canvas.drawPath(chevronPath, chevronLine)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 0L else (now - lastFrame).coerceAtMost(100L)
        lastFrame = now
        step(dt)

        if (hidden) {
            postInvalidateOnAnimation()
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val inPause = pauseRemainingMs > 0 || manualPause
        val inCountdown = countdownRemainingMs > 0
        val frozen = inPause || inCountdown
        val resting = restRemainingMs > 0 && !frozen
        val mk = if (resting) restMarker else marker
        val scale = markerScalePct / 100f

        // Everything is drawn into a layer first; the fade mask is applied to the whole layer at the end.
        val fade = edgeFadePct > 0
        val layer = if (fade) canvas.saveLayer(0f, 0f, w, h, null) else 0
        canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, bg)

        val hitX = w * 0.5f                 // hit-line in the middle
        val travel = w - hitX
        val frac = phase - floor(phase)

        line.alpha = if (frozen) 110 else 255
        canvas.drawLine(hitX, h * 0.04f, hitX, h * 0.96f, line)

        if (reducedMotion) {
            // No traveling markers: one marker on the center line flashes on every beat.
            val flashMs = minOf(140f, 30000f / maxOf(shownBpm, 1f))
            val sinceBeatMs = frac * 60000f / maxOf(shownBpm, 1f)
            val flash = !frozen && sinceBeatMs < flashMs
            mk.alpha = if (frozen) 70 else if (flash) 255 else 120
            drawMarker(canvas, hitX, h / 2, h * 0.34f * scale, mk, resting)
            if (flash) {
                glow.alpha = 110
                canvas.drawCircle(hitX, h / 2, h * 0.46f * scale, glow)
            }
        } else {
            // Start far enough to the left so markers keep scrolling past the center line
            var i = -(floor((hitX / travel) * visibleBeats).toInt() + 1)
            while (true) {
                val beatsAway = i - frac
                val x = hitX + (beatsAway / visibleBeats) * travel
                if (x > w + 20f) break
                if (x >= -20f) {
                    val near = if (frozen) 0f else 1f - (abs(beatsAway).coerceAtMost(0.25f) / 0.25f)
                    val r = (h * 0.26f + h * 0.14f * near) * scale
                    mk.alpha = when {
                        frozen -> 70
                        beatsAway < 0 -> 150   // beats that already passed fade a little
                        else -> 255
                    }
                    drawMarker(canvas, x, h / 2, r, mk, resting)
                    if (near > 0.6f) {
                        glow.alpha = (near * 120).toInt()
                        canvas.drawCircle(hitX, h / 2, r * 1.35f, glow)
                    }
                }
                i++
            }
        }

        when {
            inPause -> {
                text.alpha = 255
                text.textSize = h * 0.5f
                canvas.drawText("PAUSE", w / 2, h / 2 + text.textSize / 3, text)
            }
            inCountdown -> {
                text.alpha = 255
                text.textSize = h * 0.8f
                val digit = ceil(countdownRemainingMs / 1000f).toInt()
                canvas.drawText("$digit", w / 2, h / 2 + text.textSize / 3, text)
            }
            showBpm -> {
                text.alpha = 190
                text.textSize = h * 0.36f
                val bpmInt = shownBpm.toInt()
                val key = bpmInt * 2 + (if (resting) 1 else 0)
                if (key != labelKey) {
                    labelKey = key
                    labelText = (if (resting) "REST " else "") + bpmInt
                }
                // Keep the number inside the part of the bar that is not faded.
                val labelX = if (fade) w * (1f - edgeFadePct / 100f) - h * 0.6f else w - h * 0.9f
                canvas.drawText(labelText, labelX, h / 2 + text.textSize / 3, text)
            }
        }

        if (fade) {
            var s = fadeShader
            if (s == null) {
                val f = edgeFadePct / 100f
                s = LinearGradient(
                    0f, 0f, w, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                    floatArrayOf(0f, f, 1f - f, 1f),
                    Shader.TileMode.CLAMP
                )
                fadeShader = s
            }
            fadePaint.shader = s
            canvas.drawRect(0f, 0f, w, h, fadePaint)
            canvas.restoreToCount(layer)
        }

        // Chevrons are drawn after the fade so the ends of the bar do not dim them.
        val ca = chevronAlpha(now)
        if (ca > 0f) drawChevrons(canvas, h, ca)

        // Paused by hand: nothing moves, so redraw only a few times a second instead of every frame.
        if (manualPause) postInvalidateDelayed(200) else postInvalidateOnAnimation()
    }

    private companion object {
        const val CHEVRON_FADE_MS = 700L
    }
}