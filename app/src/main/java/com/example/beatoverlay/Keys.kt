package com.example.beatoverlay

// Names of every setting. Shared by the screen, the saved settings and the overlay service.

// Tempo
const val K_BPM = "bpm"
const val K_GROWTH = "growth"
const val K_MAX = "maxBpm"

// Random pauses
const val K_PAUSES = "pauses"
const val K_GAP_MIN = "gapMin"
const val K_GAP_MAX = "gapMax"
const val K_LEN_MIN = "lenMin"
const val K_LEN_MAX = "lenMax"
const val K_COUNTDOWN = "countdown"

// Slow rest
const val K_REST_ENABLED = "restEnabled"
const val K_REST_BPM = "restBpm"
const val K_REST_LEN_MIN = "restLenMin"
const val K_REST_LEN_MAX = "restLenMax"
const val K_REST_SWAP = "restSwap"
const val K_REST_CHANCE = "restChance"

// Random rests
const val K_REST_RANDOM = "restRandom"
const val K_REST_GAP_MIN = "restGapMin"
const val K_REST_GAP_MAX = "restGapMax"

// Random tempo drift
const val K_CHAOS_START = "chaosStart"
const val K_CHAOS_PCT = "chaosPct"
const val K_CHAOS_GROW = "chaosGrow"
const val K_CHAOS_ENABLED = "chaosEnabled"

// App settings (not part of presets)
const val K_HAPTICS = "haptics"
const val K_H_STRENGTH = "hStrength"
const val K_H_MS = "hMs"
const val K_ALPHA_PCT = "alphaPct"
const val K_EDGE_FADE = "edgeFade"        // 0 = off, else % of the bar width that fades out at each end
const val K_THEME = "theme"               // 0 = follow system, 1 = light, 2 = dark
const val K_REDUCED = "reducedMotion"
const val K_NAV_HAPTICS = "navHaptics"      // tick when a bottom-bar button is pressed
const val K_SHOW_BPM = "showBpm"            // show the tempo number on the overlay bar

// Overlay bar layout (app settings)
const val K_BAR_W = "barW"                // width, % of the screen
const val K_BAR_H = "barH"                // thickness, dp
const val K_BAR_Y_PCT = "barYPct"         // 0 = top of the screen, 100 = bottom (the bar saves this when you drag it)
const val K_CHEVRON_MODE = "chevronMode"  // 0 = fade out after a few seconds, 1 = always, 2 = off

// Beat marker
const val K_USE_IMAGE = "useImage"
const val K_MARKER_SIZE = "markerSize"    // %
const val MARKER_FILE = "marker.png"      // the chosen picture, stored in the app's private files

// First-run setup
const val K_SETUP_DONE = "setupDone"

// Gestures on the overlay: each one is mapped to an action
const val K_G_HOLD = "gHold"
const val K_G_DOUBLE = "gDouble"
const val K_G_TRIPLE = "gTriple"
const val K_G_SWIPE_L = "gSwipeL"
const val K_G_SWIPE_R = "gSwipeR"

const val GES_HOLD = 0
const val GES_DOUBLE = 1
const val GES_TRIPLE = 2
const val GES_SWIPE_L = 3
const val GES_SWIPE_R = 4
val GESTURE_KEYS = listOf(K_G_HOLD, K_G_DOUBLE, K_G_TRIPLE, K_G_SWIPE_L, K_G_SWIPE_R)
val GESTURE_NAMES = listOf("Hold", "Double tap", "Triple tap", "Swipe left", "Swipe right")

const val ACT_NONE = 0
const val ACT_OPEN = 1
const val ACT_PAUSE = 2
const val ACT_STOP = 3
const val ACT_REST = 4
const val ACT_RESET = 5
const val ACT_HIDE = 6
val ACTION_LABELS = listOf(
    "Nothing", "Open the app", "Pause / resume", "Stop the overlay",
    "Start a slow rest", "Reset tempo", "Hide bar for 10 s"
)