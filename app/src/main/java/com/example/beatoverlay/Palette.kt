package com.example.beatoverlay

import androidx.core.graphics.toColorInt

/** All colors used by the app. Light and dark versions; the screen is rebuilt when the theme changes. */
data class Palette(
    val isDark: Boolean,
    val bg: Int,
    val card: Int,
    val text: Int,
    val sub: Int,
    val accent: Int,          // filled buttons, +/- buttons
    val accentStrong: Int,    // sliders and switch tracks (needs 3:1 contrast on the card)
    val onAccent: Int,        // text/icons on accent
    val tonal: Int,           // secondary buttons
    val onTonal: Int,
    val field: Int,           // number boxes, segmented control
    val track: Int,           // inactive slider / switch track
    val thumbOn: Int,
    val thumbOff: Int,
    val navBg: Int,
    val navIndicator: Int
) {
    companion object {
        private fun c(s: String) = s.toColorInt()

        val LIGHT = Palette(
            isDark = false,
            bg = c("#FBF0EE"), card = c("#FFFFFF"), text = c("#3E3232"), sub = c("#6F6363"),
            accent = c("#FF9E9B"), accentStrong = c("#D9534F"), onAccent = c("#3D1412"),
            tonal = c("#F6DCDA"), onTonal = c("#3D1412"),
            field = c("#EFE8E7"), track = c("#CFC6C5"),
            thumbOn = c("#FF9E9B"), thumbOff = c("#8D8383"),
            navBg = c("#FFFFFF"), navIndicator = c("#FFD3D0")
        )

        val DARK = Palette(
            isDark = true,
            bg = c("#1E1818"), card = c("#2E2524"), text = c("#F1E6E4"), sub = c("#B8AAA8"),
            accent = c("#FF9E9B"), accentStrong = c("#FF9E9B"), onAccent = c("#3D1412"),
            tonal = c("#5A3A39"), onTonal = c("#F7DAD8"),
            field = c("#403534"), track = c("#5E5150"),
            thumbOn = c("#FF9E9B"), thumbOff = c("#B8AAA8"),
            navBg = c("#3A2F2E"), navIndicator = c("#6B403E")
        )
    }
}
