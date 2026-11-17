package com.example.beatoverlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat

/** A rounded card. New rows go to [target], which is the card itself or a switch-gated area inside it. */
class Card(val view: LinearLayout) {
    var target: LinearLayout = view
}

/** Builds the widgets used by every screen, coloured from the current [Palette]. */
class Ui(private val ctx: Context, val p: Palette, private val store: SettingsStore) {

    private val density = ctx.resources.displayMetrics.density
    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(v: Int) = (v * density).toInt()

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    fun ripple(color: Int, radiusDp: Int) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(60, 128, 128, 128)),
        rounded(color, radiusDp),
        rounded(Color.BLACK, radiusDp)
    )

    fun tv(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        typeface = if (bold) medium else Typeface.SANS_SERIF
    }

    fun heading(v: View) = ViewCompat.setAccessibilityHeading(v, true)

    /**
     * Short haptic tick for the bottom-bar buttons. [firm] is used for Start / Pause / Resume and Stop.
     * Uses the standard view feedback, so it follows the system's touch-feedback setting.
     */
    private fun tick(v: View, firm: Boolean) {
        if (!store.bool(K_NAV_HAPTICS)) return
        val effect = when {
            !firm -> HapticFeedbackConstants.VIRTUAL_KEY
            Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.CONTEXT_CLICK
        }
        v.performHapticFeedback(effect)
    }

    // ------------------------------------------------------------ cards

    fun card(parent: LinearLayout, title: String): Card {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 28)
            elevation = if (p.isDark) 0f else dp(2).toFloat()
            setPadding(dp(18), dp(18), dp(18), dp(20))
        }
        val t = tv(title, 22f, p.text, true).apply { gravity = Gravity.CENTER }
        heading(t)
        c.addView(t)
        parent.addView(c, lp(matchParent, wrapContent).apply { bottomMargin = dp(12) })
        return Card(c)
    }

    fun note(card: Card, text: String) {
        card.target.addView(tv(text, 13f, p.sub).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    /** Rows added after this go back to the card itself instead of the switch-gated area. */
    fun endGate(card: Card) {
        card.target = card.view
    }

    private fun setEnabledTree(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is ViewGroup) for (i in 0 until v.childCount) setEnabledTree(v.getChildAt(i), on)
    }

    /** On/off switch. With gate = true, the rows added after it are greyed out while it is off. */
    fun toggle(card: Card, key: String, title: String, gate: Boolean = false) {
        val checked = intArrayOf(android.R.attr.state_checked)
        val sw = Switch(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(p.text)
            typeface = Typeface.SANS_SERIF
            minimumHeight = dp(48)
            setPadding(0, dp(8), 0, dp(2))
            isChecked = store.bool(key)
            thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(p.thumbOn, p.thumbOff))
            trackTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(p.accentStrong, p.track))
        }
        card.target.addView(sw, lp(matchParent, wrapContent))

        if (gate) {
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            card.view.addView(body)
            card.target = body
            fun applyGate(on: Boolean) {
                body.alpha = if (on) 1f else 0.38f
                setEnabledTree(body, on)
            }
            applyGate(sw.isChecked)
            // Rows are added after this point, so grey them out as they arrive when the switch starts off.
            body.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    if (child != null && !sw.isChecked) setEnabledTree(child, false)
                }
                override fun onChildViewRemoved(parent: View?, child: View?) {}
            })
            sw.setOnCheckedChangeListener { _, on -> store.setBool(key, on); applyGate(on) }
        } else {
            sw.setOnCheckedChangeListener { _, on -> store.setBool(key, on) }
        }
    }

    // ------------------------------------------------------------ slider row

    private fun stepper(glyph: String, description: String) = TextView(ctx).apply {
        text = glyph
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)      // fixed size so it never outgrows the button
        gravity = Gravity.CENTER
        setTextColor(p.onAccent)
        background = ripple(p.accent, 12)
        isClickable = true
        isFocusable = true
        contentDescription = description
    }

    /**
     * One setting: label, - / + / number box / unit, and a slider underneath.
     * [zero] is shown in place of the unit when the value is 0 (for example "off").
     */
    fun slider(card: Card, key: String, title: String, min: Int, max: Int, unit: String, zero: String? = null) {
        store.values[key] = store.int(key).coerceIn(min, max)

        val name = tv(title, 16f, p.text)
        val minus = stepper("−", "Decrease $title")
        val plus = stepper("+", "Increase $title")
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(p.text)
            background = rounded(p.field, 10)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            minimumHeight = dp(40)
            contentDescription = title
            setText(store.int(key).toString())
        }
        val unitView = tv(unit, 13f, p.sub).apply {
            gravity = Gravity.CENTER_VERTICAL
            minWidth = dp(42)
        }
        val seek = SeekBar(ctx).apply {
            this.max = max - min
            progress = store.int(key) - min
            setPadding(dp(12), dp(8), dp(12), dp(8))
            progressTintList = ColorStateList.valueOf(p.accentStrong)
            thumbTintList = ColorStateList.valueOf(p.accentStrong)
            progressBackgroundTintList = ColorStateList.valueOf(p.track)
            contentDescription = title
        }

        fun showUnit(v: Int) {
            val u = if (v == 0 && zero != null) zero else unit
            unitView.text = u
            ViewCompat.setStateDescription(seek, if (v == 0 && zero != null) zero else "$v $unit")
        }
        showUnit(store.int(key))

        var updating = false
        fun refresh(v: Int, rewriteText: Boolean) {
            updating = true
            store.setInt(key, v)
            seek.progress = v - min
            showUnit(v)
            if (rewriteText) input.setText(v.toString())
            updating = false
        }
        fun commitText() {
            val n = input.text.toString().toIntOrNull() ?: store.int(key)
            refresh(n.coerceIn(min, max), true)
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, pr: Int, fromUser: Boolean) {
                if (fromUser) refresh(pr + min, true)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        minus.setOnClickListener { input.clearFocus(); refresh((store.int(key) - 1).coerceAtLeast(min), true) }
        plus.setOnClickListener { input.clearFocus(); refresh((store.int(key) + 1).coerceAtMost(max), true) }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val n = s.toString().toIntOrNull()
                if (n != null && n in min..max) refresh(n, false)   // out-of-range values are fixed on Done
            }
        })
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitText() }
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitText()
                v.clearFocus()
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else false
        }

        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(minus, lp(dp(40), dp(40)))
        controls.addView(plus, lp(dp(40), dp(40)).apply { marginStart = dp(6) })
        controls.addView(input, lp(dp(58), wrapContent).apply { marginStart = dp(6) })
        controls.addView(unitView, lp(wrapContent, wrapContent).apply { marginStart = dp(8) })

        // With a large system font the label gets its own line so nothing is squeezed.
        val stacked = ctx.resources.configuration.fontScale > 1.25f
        val top = LinearLayout(ctx).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (stacked) {
            top.addView(name, lp(matchParent, wrapContent))
            top.addView(controls, lp(wrapContent, wrapContent).apply { topMargin = dp(8) })
        } else {
            top.addView(name, lp(0, wrapContent, 1f).apply { marginEnd = dp(8) })
            top.addView(controls)
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        box.addView(top)
        box.addView(seek, lp(matchParent, wrapContent).apply { topMargin = dp(2) })
        card.target.addView(box)
    }

    // ------------------------------------------------------------ other controls

    /** Row of choices, one selected (used for the theme). */
    fun segmented(card: Card, title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        card.target.addView(tv(title, 16f, p.text).apply { setPadding(0, dp(12), 0, dp(8)) })
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.field, 24)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        options.forEachIndexed { i, option ->
            val on = i == selected
            val seg = tv(option, 15f, if (on) p.onAccent else p.text, on).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(40)
                isSelected = on
                isClickable = true
                isFocusable = true
                background = if (on) rounded(p.accent, 20) else null
                contentDescription = "$title: $option"
                ViewCompat.setStateDescription(this, if (on) "Selected" else "Not selected")
                setOnClickListener { onSelect(i) }
            }
            group.addView(seg, lp(0, wrapContent, 1f))
        }
        card.target.addView(group, lp(matchParent, wrapContent))
    }

    fun pillButton(text: String, filled: Boolean, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        typeface = medium
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(if (filled) p.onAccent else p.onTonal)
        background = ripple(if (filled) p.accent else p.tonal, 28)
        setOnClickListener { onClick() }
    }

    fun nameField(hint: String) = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(p.sub)
        setSingleLine()
        textSize = 16f
        setTextColor(p.text)
        background = rounded(p.field, 12)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        minimumHeight = dp(48)
        filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(24))
    }

    fun iconButton(icon: Int, description: String, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(p.text)
        contentDescription = description
        setPadding(dp(12), dp(12), dp(12), dp(12))
        isClickable = true
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 128, 128, 128)),
            null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
        )
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------ floating navigation bar

    /**
     * One destination / action in the floating bar: icon on a pill, label underneath.
     * [filled] gives it the strong accent colour (used for the main Start / Pause / Resume button).
     */
    fun navItem(
        icon: Int, label: String, selected: Boolean, enabled: Boolean,
        filled: Boolean = false, strong: Boolean = false, badge: Boolean = false, firmTick: Boolean = false,
        onClick: () -> Unit
    ): View {
        val fillIt = filled || badge          // a badge (changes waiting) also gets the highlighted pill
        val indicator = FrameLayout(ctx).apply {
            if (fillIt) background = rounded(p.accent, 16)
            else if (selected) background = rounded(p.navIndicator, 16)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        indicator.addView(
            ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(
                    if (fillIt) p.onAccent else if (selected) p.onTonal else if (strong) p.text else p.sub
                )
            },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        )
        if (badge) {
            // Small dot above the top-right of the icon.
            indicator.addView(
                View(ctx).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.onAccent) }
                },
                FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = dp(29)
                    topMargin = dp(1)
                }
            )
        }
        val caption = tv(label, 11f, if (selected || fillIt || strong) p.text else p.sub, selected || fillIt || strong).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(1), dp(4), dp(1), dp(4))
            addView(indicator, lp(dp(44), dp(30)))
            addView(caption, lp(wrapContent, wrapContent).apply { topMargin = dp(2) })
            contentDescription = if (enabled) label else "$label, available after the overlay starts"
            isSelected = selected
            isEnabled = enabled            // a disabled item is greyed out and cannot be pressed
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.5f
            background = ripple(Color.TRANSPARENT, 20)
            setOnClickListener {
                tick(this, filled || firmTick)
                onClick()
            }
        }
    }

    /**
     * "Applied" animation for an Apply item built with badge = true: the check pops, the dot shrinks
     * away and the highlight fades to the grey "nothing to apply" look. [onDone] runs at the end.
     */
    fun playApplied(item: View, onDone: () -> Unit) {
        val group = item as? ViewGroup
        val indicator = group?.getChildAt(0) as? ViewGroup
        val caption = group?.getChildAt(1) as? TextView
        val icon = indicator?.getChildAt(0) as? ImageView
        if (group == null || indicator == null || caption == null || icon == null) {
            onDone()
            return
        }
        val dot = if (indicator.childCount > 1) indicator.getChildAt(1) else null

        icon.animate().scaleX(1.3f).scaleY(1.3f).setDuration(140).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                icon.animate().scaleX(1f).scaleY(1f).setDuration(280)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }
            .start()
        dot?.animate()?.scaleX(0f)?.scaleY(0f)?.alpha(0f)?.setDuration(200)?.start()

        val highlight = indicator.background
        val argb = ArgbEvaluator()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360
            startDelay = 120
            addUpdateListener { a ->
                val f = a.animatedFraction
                highlight?.alpha = ((1f - f) * 255).toInt()
                icon.imageTintList = ColorStateList.valueOf(argb.evaluate(f, p.onAccent, p.sub) as Int)
                caption.setTextColor(argb.evaluate(f, p.text, p.sub) as Int)
                group.alpha = 1f - 0.5f * f          // ends at the same look as a disabled item
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDone()
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------ Stop button

    /** The Stop button beside the floating bar: same corner style, strong colour, icon over label. */
    fun stopButton(onClick: () -> Unit): View {
        val fg = if (p.isDark) p.onAccent else Color.WHITE
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            elevation = dp(8).toFloat()          // same depth as the bar; the bar is added later, so it draws on top
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_stop)
                    imageTintList = ColorStateList.valueOf(fg)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                lp(dp(24), dp(24))
            )
            addView(tv("Stop", 11f, fg, true), lp(wrapContent, wrapContent).apply { topMargin = dp(2) })
            isClickable = true
            isFocusable = true
            background = ripple(p.accentStrong, 32)
            setOnClickListener {
                tick(this, true)
                onClick()
            }
        }
    }
}