package com.example.beatoverlay

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
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

        const val WALLPAPER = 8
        val COLOR_NAMES = listOf(
            "Rose", "Ocean", "Forest", "Violet", "Amber", "Mono", "Dracula", "Catppuccin", "Wallpaper"
        )

        /** Wallpaper colors come from Android 12 (API 31) and newer. */
        fun wallpaperAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /** [color] is the K_COLOR number. Any unknown number gives Rose. */
        fun of(ctx: Context, color: Int, dark: Boolean): Palette = when (color) {
            1 -> if (dark) OCEAN_DARK else OCEAN_LIGHT
            2 -> if (dark) FOREST_DARK else FOREST_LIGHT
            3 -> if (dark) VIOLET_DARK else VIOLET_LIGHT
            4 -> if (dark) AMBER_DARK else AMBER_LIGHT
            5 -> if (dark) MONO_DARK else MONO_LIGHT
            6 -> if (dark) DRACULA_DARK else DRACULA_LIGHT
            7 -> if (dark) MOCHA else LATTE
            WALLPAPER -> if (wallpaperAvailable()) wallpaper(ctx, dark) else if (dark) DARK else LIGHT
            else -> if (dark) DARK else LIGHT
        }

        /** Thumb (on) uses the accent color. */
        private fun make(
            dark: Boolean, bg: String, card: String, text: String, sub: String,
            accent: String, strong: String, onAccent: String, tonal: String, onTonal: String,
            field: String, track: String, thumbOff: String, navBg: String, navIndicator: String
        ) = Palette(
            dark, c(bg), c(card), c(text), c(sub), c(accent), c(strong), c(onAccent),
            c(tonal), c(onTonal), c(field), c(track), c(accent), c(thumbOff), c(navBg), c(navIndicator)
        )

        // ---- Rose (the original look)
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

        // ---- Ocean
        private val OCEAN_LIGHT = make(false, "#EEF4FA", "#FFFFFF", "#1F2A33", "#55636E",
            "#8FC1F0", "#1F6FB5", "#0B2540", "#D6E6F5", "#0B2540",
            "#E4EBF1", "#C3CDD6", "#7D8A95", "#FFFFFF", "#C6DFF7")
        private val OCEAN_DARK = make(true, "#121A22", "#1D2833", "#E3EBF2", "#A5B3C0",
            "#8FC1F0", "#8FC1F0", "#0B2540", "#2F4A63", "#D6E6F5",
            "#2A3744", "#46586A", "#A5B3C0", "#263340", "#2F5278")

        // ---- Forest
        private val FOREST_LIGHT = make(false, "#EEF5EE", "#FFFFFF", "#202B22", "#56655A",
            "#94CF9B", "#2E7D3A", "#0D2B12", "#D8EAD9", "#0D2B12",
            "#E4ECE4", "#C4D0C5", "#7F8C81", "#FFFFFF", "#C5E5C9")
        private val FOREST_DARK = make(true, "#141B15", "#1F2A21", "#E2EBE3", "#A7B5A9",
            "#94CF9B", "#94CF9B", "#0D2B12", "#34503A", "#D8EAD9",
            "#2B372D", "#475849", "#A7B5A9", "#28352B", "#35603C")

        // ---- Violet
        private val VIOLET_LIGHT = make(false, "#F4F0FA", "#FFFFFF", "#2A2433", "#62596E",
            "#C3A6F0", "#7141B8", "#2A0E4F", "#E5DAF5", "#2A0E4F",
            "#EAE5F0", "#CDC5D6", "#857C91", "#FFFFFF", "#DCC9F7")
        private val VIOLET_DARK = make(true, "#19151F", "#262030", "#EBE4F2", "#B3A9C0",
            "#C3A6F0", "#C3A6F0", "#2A0E4F", "#473560", "#E5DAF5",
            "#342C40", "#54496A", "#B3A9C0", "#30283D", "#54387D")

        // ---- Amber
        private val AMBER_LIGHT = make(false, "#FAF3E6", "#FFFFFF", "#33291A", "#6B5F4B",
            "#F5C15A", "#A86800", "#3A2400", "#F4E4C4", "#3A2400",
            "#EFE8DA", "#D2C8B3", "#8E836E", "#FFFFFF", "#FAD99A")
        private val AMBER_DARK = make(true, "#1C1710", "#2A2318", "#F1E8D6", "#BDB19A",
            "#F5C15A", "#F5C15A", "#3A2400", "#5A4620", "#F4E4C4",
            "#3A3222", "#5E5239", "#BDB19A", "#352D1E", "#6B5224")

        // ---- Mono
        private val MONO_LIGHT = make(false, "#F2F2F2", "#FFFFFF", "#1C1C1C", "#5E5E5E",
            "#BDBDBD", "#424242", "#121212", "#E4E4E4", "#1C1C1C",
            "#EBEBEB", "#CFCFCF", "#808080", "#FFFFFF", "#D9D9D9")
        private val MONO_DARK = make(true, "#121212", "#1E1E1E", "#EDEDED", "#A8A8A8",
            "#D0D0D0", "#D0D0D0", "#121212", "#3A3A3A", "#E4E4E4",
            "#2C2C2C", "#4A4A4A", "#A8A8A8", "#2A2A2A", "#484848")

        // ---- Dracula (dark) and Alucard (its official light version)
        private val DRACULA_DARK = make(true, "#282A36", "#343746", "#F8F8F2", "#B4BAD6",
            "#BD93F9", "#BD93F9", "#282A36", "#44475A", "#F8F8F2",
            "#44475A", "#565A75", "#8E94B8", "#3B3E50", "#5B4B8A")
        private val DRACULA_LIGHT = make(false, "#FFFBEB", "#FFFEF7", "#1F1F1F", "#5A5640",
            "#644AC9", "#644AC9", "#FFFFFF", "#E8E2F7", "#2B1F66",
            "#EFEBDC", "#CFCBB5", "#8B876E", "#FFFEF7", "#D9D0F5")

        // ---- Catppuccin: Mocha (dark) and Latte (light)
        private val MOCHA = make(true, "#1E1E2E", "#313244", "#CDD6F4", "#A6ADC8",
            "#CBA6F7", "#CBA6F7", "#1E1E2E", "#45475A", "#CDD6F4",
            "#181825", "#585B70", "#9399B2", "#313244", "#514A73")
        private val LATTE = make(false, "#EFF1F5", "#FFFFFF", "#4C4F69", "#6C6F85",
            "#8839EF", "#8839EF", "#FFFFFF", "#E4DCF9", "#4C2A85",
            "#E6E9EF", "#BCC0CC", "#8C8FA1", "#FFFFFF", "#DDD0F8")

        // ---- Wallpaper (Android 12+): the colors the phone picked from the wallpaper
        @RequiresApi(Build.VERSION_CODES.S)
        private fun wallpaper(ctx: Context, dark: Boolean): Palette {
            fun col(id: Int) = ctx.getColor(id)
            return if (dark) Palette(
                isDark = true,
                bg = col(android.R.color.system_neutral1_900), card = col(android.R.color.system_neutral1_800),
                text = col(android.R.color.system_neutral1_100), sub = col(android.R.color.system_neutral2_300),
                accent = col(android.R.color.system_accent1_200), accentStrong = col(android.R.color.system_accent1_200),
                onAccent = col(android.R.color.system_accent1_900),
                tonal = col(android.R.color.system_accent2_700), onTonal = col(android.R.color.system_accent2_100),
                field = col(android.R.color.system_neutral1_700), track = col(android.R.color.system_neutral2_600),
                thumbOn = col(android.R.color.system_accent1_200), thumbOff = col(android.R.color.system_neutral2_300),
                navBg = col(android.R.color.system_neutral1_700), navIndicator = col(android.R.color.system_accent2_600)
            ) else Palette(
                isDark = false,
                bg = col(android.R.color.system_neutral1_50), card = col(android.R.color.system_neutral1_0),
                text = col(android.R.color.system_neutral1_900), sub = col(android.R.color.system_neutral2_700),
                accent = col(android.R.color.system_accent1_200), accentStrong = col(android.R.color.system_accent1_600),
                onAccent = col(android.R.color.system_accent1_900),
                tonal = col(android.R.color.system_accent2_100), onTonal = col(android.R.color.system_accent2_900),
                field = col(android.R.color.system_neutral2_100), track = col(android.R.color.system_neutral2_200),
                thumbOn = col(android.R.color.system_accent1_200), thumbOff = col(android.R.color.system_neutral2_500),
                navBg = col(android.R.color.system_neutral1_0), navIndicator = col(android.R.color.system_accent1_100)
            )
        }
    }
}