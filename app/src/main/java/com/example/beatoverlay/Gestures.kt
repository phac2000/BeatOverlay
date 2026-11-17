package com.example.beatoverlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Turns raw touches into: vertical drag, hold, double tap, triple tap, swipe left, swipe right.
 * Used by the floating bar and by the practice pad in the setup tutorial.
 *
 * A single tap does nothing. A double tap is reported straight away when triple tap has no action,
 * otherwise it waits a moment to see whether a third tap follows.
 */
class OverlayGestures(ctx: Context, private val l: Listener) : View.OnTouchListener {

    interface Listener {
        /** Finger down (true) or lifted / cancelled (false). */
        fun onTouch(down: Boolean) {}
        fun onDragStart() {}
        /** Vertical distance since the finger went down. */
        fun onDrag(dy: Float) {}
        fun onDragEnd() {}
        /** One of GES_HOLD, GES_DOUBLE, GES_TRIPLE, GES_SWIPE_L, GES_SWIPE_R. */
        fun onGesture(gesture: Int)
        fun hasHold(): Boolean = true
        fun hasTripleTap(): Boolean = true
    }

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val tapGapMs = 280L
    private val swipeMin = 48f * ctx.resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var mode = IDLE
    private var taps = 0

    private val longPress = Runnable {
        if (mode == UNDECIDED) {
            mode = DONE
            taps = 0
            l.onGesture(GES_HOLD)
        }
    }
    private val tapResolver = Runnable {
        val n = taps
        taps = 0
        resolveTaps(n)
    }

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                mode = UNDECIDED
                handler.removeCallbacks(tapResolver)
                l.onTouch(true)
                if (l.hasHold()) handler.postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (mode == UNDECIDED && (abs(dx) > slop || abs(dy) > slop)) {
                    handler.removeCallbacks(longPress)
                    taps = 0
                    mode = if (abs(dy) >= abs(dx)) DRAG else SWIPE
                    if (mode == DRAG) l.onDragStart()
                }
                if (mode == DRAG) l.onDrag(dy)
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                l.onTouch(false)
                when (mode) {
                    UNDECIDED -> {
                        taps++
                        if (taps >= 3 || (taps == 2 && !l.hasTripleTap())) {
                            handler.removeCallbacks(tapResolver)
                            val n = taps
                            taps = 0
                            resolveTaps(n)
                        } else {
                            handler.postDelayed(tapResolver, tapGapMs)
                        }
                    }
                    DRAG -> l.onDragEnd()
                    SWIPE -> {
                        val dx = e.rawX - downX
                        if (abs(dx) >= swipeMin) l.onGesture(if (dx < 0) GES_SWIPE_L else GES_SWIPE_R)
                    }
                }
                mode = IDLE
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                l.onTouch(false)
                if (mode == DRAG) l.onDragEnd()
                mode = IDLE
                taps = 0
            }
        }
        return true
    }

    private fun resolveTaps(n: Int) {
        when {
            n >= 3 -> l.onGesture(GES_TRIPLE)
            n == 2 -> l.onGesture(GES_DOUBLE)
        }
    }

    /** Call when the view goes away. */
    fun cancel() {
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val IDLE = 0
        const val UNDECIDED = 1
        const val DRAG = 2
        const val SWIPE = 3
        const val DONE = 4
    }
}