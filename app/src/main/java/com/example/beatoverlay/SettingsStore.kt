package com.example.beatoverlay

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/**
 * Every setting lives here and is saved the moment it changes.
 * Also handles presets and export / import (a JSON file).
 */
class SettingsStore(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val values = mutableMapOf<String, Int>()
    val flags = mutableMapOf<String, Boolean>()

    /** Called after any change (the screen uses it to update a running overlay). */
    var onChange: (() -> Unit)? = null

    init {
        load()
    }

    private fun systemReducedMotion(): Boolean = try {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) {
        false
    }

    private fun load() {
        INT_DEFAULTS.forEach { (k, d) -> values[k] = prefs.getInt(k, d) }
        BOOL_DEFAULTS.forEach { (k, d) -> flags[k] = prefs.getBoolean(k, d) }
        // First run: follow Android's "remove animations" setting.
        if (!prefs.contains(K_REDUCED)) flags[K_REDUCED] = systemReducedMotion()
        syncPosition()
    }

    /** The bar position lives in a small file shared with the overlay, which runs in its own process. */
    private val posFile get() = File(ctx.filesDir, OverlayService.POS_FILE)

    /**
     * The overlay saves its position to a file when you drag it. Picks that up.
     * Before the first drag there is no file, so the saved setting is used.
     * Returns true if the position changed.
     */
    fun syncPosition(): Boolean {
        val fromFile = try {
            posFile.readText().trim().toIntOrNull()?.coerceIn(0, 100)
        } catch (e: Exception) {
            null
        }
        val v = fromFile ?: prefs.getInt(K_BAR_Y_PCT, INT_DEFAULTS.getValue(K_BAR_Y_PCT))
        val changed = values[K_BAR_Y_PCT] != v
        values[K_BAR_Y_PCT] = v
        return changed
    }

    fun int(k: String): Int = values[k] ?: INT_DEFAULTS.getValue(k)
    fun bool(k: String): Boolean = flags[k] ?: BOOL_DEFAULTS.getValue(k)

    private fun putInt(k: String, v: Int) {
        values[k] = v
        prefs.edit().putInt(k, v).apply()
        if (k == K_BAR_Y_PCT) {
            try {
                posFile.writeText(v.toString())     // keeps the file and the setting the same
            } catch (e: Exception) {
                // The setting is still saved; only the shared copy is missed.
            }
        }
    }

    private fun putBool(k: String, v: Boolean) {
        flags[k] = v
        prefs.edit().putBoolean(k, v).apply()
    }

    /**
     * While true (the Home screen), edits to rhythm settings are only kept in memory until applyPending().
     * The saved values (what the overlay uses) are the "applied" ones.
     */
    var staging = false

    /** Called when a staged edit happens, so the Apply button can update. */
    var onPendingChange: (() -> Unit)? = null

    fun setInt(k: String, v: Int) {
        if (staging && k in PRESET_INTS) {
            values[k] = v
            onPendingChange?.invoke()
            return
        }
        putInt(k, v)
        onChange?.invoke()
    }

    fun setBool(k: String, v: Boolean) {
        if (staging && k in PRESET_BOOLS) {
            flags[k] = v
            onPendingChange?.invoke()
            return
        }
        putBool(k, v)
        onChange?.invoke()
    }

    // ------------------------------------------------------------ staged edits

    fun appliedInt(k: String): Int = prefs.getInt(k, INT_DEFAULTS.getValue(k))
    fun appliedBool(k: String): Boolean = prefs.getBoolean(k, BOOL_DEFAULTS.getValue(k))

    /** True when any rhythm setting differs from what is applied. Changing a value back counts as no change. */
    fun hasPending(): Boolean {
        for (k in PRESET_INTS) if (int(k) != appliedInt(k)) return true
        for (k in PRESET_BOOLS) if (bool(k) != appliedBool(k)) return true
        return false
    }

    /** Saves the staged values and tells a running overlay about them. */
    fun applyPending() {
        val e = prefs.edit()
        PRESET_INTS.forEach { e.putInt(it, int(it)) }
        PRESET_BOOLS.forEach { e.putBoolean(it, bool(it)) }
        e.apply()
        onChange?.invoke()
    }

    /** Throws the staged values away and goes back to the applied ones. */
    fun discardPending() {
        PRESET_INTS.forEach { values[it] = appliedInt(it) }
        PRESET_BOOLS.forEach { flags[it] = appliedBool(it) }
    }

    // ------------------------------------------------------------ presets

    private fun savedPresets(): JSONObject = try {
        JSONObject(prefs.getString("presets", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    /** Newest first: the stored JSON keeps insertion order, so the list is just reversed. */
    fun userPresets(): List<String> = savedPresets().keys().asSequence().toList().reversed()

    /** Name shown in the Home header: the last preset that was saved or applied. */
    var currentPreset: String
        get() = prefs.getString("current_preset", "Default") ?: "Default"
        private set(v) {
            prefs.edit().putString("current_preset", v).apply()
        }

    fun builtInPresets(): List<String> = BUILT_IN.keys.toList()

    fun isBuiltIn(name: String) = BUILT_IN.containsKey(name)

    fun savePreset(name: String) {
        val o = JSONObject()
        PRESET_INTS.forEach { o.put(it, int(it)) }
        PRESET_BOOLS.forEach { o.put(it, bool(it)) }
        val all = savedPresets()
        all.remove(name)   // so re-saving an existing name moves it to the top
        all.put(name, o)
        prefs.edit().putString("presets", all.toString()).apply()
        currentPreset = name
    }

    fun deletePreset(name: String) {
        val all = savedPresets()
        all.remove(name)
        prefs.edit().putString("presets", all.toString()).apply()
    }

    /** Applies a preset. Anything the preset does not mention goes back to its default. */
    fun applyPreset(name: String): Boolean {
        val src: Map<String, Any> = BUILT_IN[name]
            ?: savedPresets().optJSONObject(name)?.let { j -> j.keys().asSequence().associateWith { j.get(it) } }
            ?: return false
        PRESET_INTS.forEach { k -> putInt(k, (src[k] as? Number)?.toInt() ?: INT_DEFAULTS.getValue(k)) }
        PRESET_BOOLS.forEach { k -> putBool(k, (src[k] as? Boolean) ?: BOOL_DEFAULTS.getValue(k)) }
        currentPreset = name
        onChange?.invoke()
        return true
    }

    // ------------------------------------------------------------ export / import

    fun exportJson(): String {
        val s = JSONObject()
        values.forEach { (k, v) -> s.put(k, v) }
        flags.forEach { (k, v) -> s.put(k, v) }
        return JSONObject()
            .put("app", "BeatOverlay")
            .put("version", 1)
            .put("settings", s)
            .put("presets", savedPresets())
            .toString(2)
    }

    /** Returns null on success, or a message describing what went wrong. */
    fun importJson(text: String): String? {
        try {
            val root = JSONObject(text)
            if (root.optString("app") != "BeatOverlay") return "That is not a Beat Overlay settings file."
            root.optJSONObject("settings")?.let { s ->
                INT_DEFAULTS.keys.forEach { k -> (s.opt(k) as? Number)?.let { putInt(k, it.toInt()) } }
                BOOL_DEFAULTS.keys.forEach { k -> (s.opt(k) as? Boolean)?.let { putBool(k, it) } }
            }
            root.optJSONObject("presets")?.let { imported ->
                val all = savedPresets()
                imported.keys().forEach { name -> all.remove(name); all.put(name, imported.get(name)) }
                prefs.edit().putString("presets", all.toString()).apply()
            }
            onChange?.invoke()
            return null
        } catch (e: Exception) {
            return "Could not read that file."
        }
    }

    companion object {
        val INT_DEFAULTS: Map<String, Int> = linkedMapOf(
            K_BPM to 67, K_GROWTH to 0, K_MAX to 300,
            K_GAP_MIN to 13, K_GAP_MAX to 21, K_LEN_MIN to 6, K_LEN_MAX to 10, K_COUNTDOWN to 3,
            K_REST_BPM to 74, K_REST_LEN_MIN to 8, K_REST_LEN_MAX to 15, K_REST_SWAP to 20, K_REST_CHANCE to 11,
            K_REST_GAP_MIN to 20, K_REST_GAP_MAX to 40,
            K_CHAOS_START to 1, K_CHAOS_PCT to 50, K_CHAOS_GROW to 10,
            K_H_STRENGTH to 100, K_H_MS to 35, K_ALPHA_PCT to 80, K_EDGE_FADE to 20, K_THEME to 0,
            K_BAR_W to 90, K_BAR_H to 36, K_BAR_Y_PCT to 8, K_CHEVRON_MODE to 0, K_MARKER_SIZE to 100,
            K_G_HOLD to ACT_OPEN, K_G_DOUBLE to ACT_PAUSE, K_G_TRIPLE to ACT_NONE,
            K_G_SWIPE_L to ACT_NONE, K_G_SWIPE_R to ACT_NONE
        )

        val BOOL_DEFAULTS: Map<String, Boolean> = linkedMapOf(
            K_PAUSES to false, K_REST_ENABLED to false, K_REST_RANDOM to false, K_CHAOS_ENABLED to false,
            K_HAPTICS to true, K_REDUCED to false, K_SETUP_DONE to false, K_USE_IMAGE to false, K_NAV_HAPTICS to true,
            K_SHOW_BPM to false
        )

        /** Settings that belong to the app, not to a rhythm preset. */
        private val APP_INTS = setOf(
            K_H_STRENGTH, K_H_MS, K_ALPHA_PCT, K_EDGE_FADE, K_THEME,
            K_BAR_W, K_BAR_H, K_BAR_Y_PCT, K_CHEVRON_MODE, K_MARKER_SIZE,
            K_G_HOLD, K_G_DOUBLE, K_G_TRIPLE, K_G_SWIPE_L, K_G_SWIPE_R
        )
        private val APP_BOOLS = setOf(K_HAPTICS, K_REDUCED, K_SETUP_DONE, K_USE_IMAGE, K_NAV_HAPTICS, K_SHOW_BPM)

        val PRESET_INTS: Set<String> = INT_DEFAULTS.keys - APP_INTS
        val PRESET_BOOLS: Set<String> = BOOL_DEFAULTS.keys - APP_BOOLS

        val BUILT_IN: Map<String, Map<String, Any>> = linkedMapOf(
            "Default" to emptyMap<String, Any>(),
            "Warm-up" to mapOf(
                K_BPM to 100, K_GROWTH to 5, K_MAX to 180,
                K_PAUSES to false, K_REST_ENABLED to false, K_CHAOS_ENABLED to false,
                K_CHAOS_PCT to 0, K_CHAOS_GROW to 0
            ),
            "Endurance" to mapOf(
                K_BPM to 120, K_GROWTH to 3, K_MAX to 200,
                K_GAP_MIN to 30, K_GAP_MAX to 60, K_LEN_MIN to 4, K_LEN_MAX to 8,
                K_REST_SWAP to 0, K_REST_CHANCE to 30, K_REST_BPM to 80,
                K_CHAOS_START to 3, K_CHAOS_PCT to 10, K_CHAOS_GROW to 2
            ),
            "Chaos" to mapOf(
                K_BPM to 155, K_GROWTH to 12, K_MAX to 300,
                K_PAUSES to true, K_REST_ENABLED to true, K_REST_RANDOM to true, K_CHAOS_ENABLED to true,
                K_GAP_MIN to 8, K_GAP_MAX to 15, K_LEN_MIN to 3, K_LEN_MAX to 8,
                K_REST_SWAP to 40, K_REST_CHANCE to 25, K_REST_GAP_MIN to 12, K_REST_GAP_MAX to 25,
                K_CHAOS_START to 0, K_CHAOS_PCT to 50, K_CHAOS_GROW to 10
            )
        )
    }
}