package dev.otherworld.budget.data.theme

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns a single seed colour -- the hex string a Nextcloud instance reports as its theming
 * colour (`ocs.data.capabilities.theming.color`) -- into the handful of *accent* colour-scheme
 * roles the app paints its brand with, for both the light and the dark scheme.
 *
 * Deliberately small, pure and framework-free: no Android imports, no Compose, no
 * colour-utilities library (the app must stay FOSS-buildable with no proprietary or
 * Material-colour-utilities dependency), so it is plain-JVM unit-testable and produces the same
 * bytes on every device. It generates only the roles the UI actually draws the brand on --
 * `primary`/`onPrimary`, `primaryContainer`/`onPrimaryContainer` and `inversePrimary` (audited
 * against `ui/theme/Color.kt`: buttons use `primary`, the capture FAB uses `primaryContainer`,
 * the Capture banners are `Surface(tonalElevation=…)` tinted by `surfaceTint`==`primary`, and a
 * snackbar action uses `inversePrimary`). Every neutral role -- surfaces, backgrounds, outlines,
 * secondary/tertiary, error -- is left to the app's hand-tuned static scheme: the brand colour
 * drives the accents, not the page, so a garish theme colour never bleeds into readable surfaces.
 *
 * ### Method
 * - **Hue and saturation** come from the seed; the ramp is then built at fixed HSL *lightnesses*
 *   ("tones"), the same approach a tonal palette takes. For the default Nextcloud blue `#0082c9`
 *   this reproduces the app's existing hand-written blue ramp near-exactly (its whole primary
 *   ramp is hue 201 / S 100% at varying lightness), so a user on a default-themed server sees no
 *   change from the logged-out look.
 * - **Light `primary`** keeps the seed's *own* lightness (clamped into a usable band) rather than
 *   forcing a fixed tone, so a genuinely light seed (pale grey, khaki, yellow) stays light and
 *   therefore takes a black foreground -- the whole point of computing the foreground.
 * - **Dark `primary`** is pinned to a light tone regardless of the seed: on a near-black surface
 *   a mid-tone accent recedes instead of standing out (see `ui/theme/Theme.kt`'s dark KDoc).
 * - **Every on-colour is chosen by WCAG relative luminance**, never hardcoded: white is used only
 *   where it clears the 3:1 contrast bar (WCAG AA for large text and UI components -- what button
 *   and FAB labels need), otherwise black. So a dark seed like `#0082c9` gets white text and a
 *   light seed like `#F0E68C` gets black text, computed rather than assumed. (`#0082c9`'s white
 *   pairing is 4.17:1 -- below the 4.5:1 *small*-text bar, but it is Nextcloud's own brand pairing
 *   and clears the 3:1 large-text/UI bar; the point retired here is that white is no longer
 *   *hardcoded* for every seed, which would leave a pale-themed server with unreadable labels.)
 *
 * Deterministic and side-effect-free. A malformed or unparseable seed makes [from] return `null`,
 * which callers read as "unusable -- fall back to the default scheme".
 */
object ThemeColors {

    /** The five brand-derived roles of one scheme, as opaque `0xFFRRGGBB` ARGB ints. */
    data class AccentScheme(
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val inversePrimary: Int,
    )

    /** The generated accents for both schemes. */
    data class Accents(val light: AccentScheme, val dark: AccentScheme)

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()

    // Light primary keeps the seed's lightness but never so light it vanishes on the near-white
    // page, nor so dark it reads as plain black. Containers/inverse/dark-primary use fixed tones.
    private const val LIGHT_PRIMARY_MIN_L = 0.10
    private const val LIGHT_PRIMARY_MAX_L = 0.60
    private const val CONTAINER_LIGHT_L = 0.90
    private const val CONTAINER_DARK_L = 0.30
    private const val PRIMARY_DARK_L = 0.80
    private const val INVERSE_LIGHT_L = 0.80
    private const val INVERSE_DARK_L = 0.40

    // 3:1 is WCAG AA for large text (>=18.66px, or >=14px bold) and for UI components -- the
    // category button and FAB labels fall in. White is preferred where it meets it, matching the
    // convention Nextcloud's own clients use, and black takes over on light backgrounds where
    // white would fail. Because white failing 3:1 requires luminance > 0.30 and black failing it
    // requires luminance < 0.10, at least one always passes, so a legible foreground is guaranteed.
    private const val ON_COLOUR_MIN_CONTRAST = 3.0

    /** True when [seedHex] parses to a colour this utility can build a scheme from. */
    fun isUsable(seedHex: String): Boolean = parseHex(seedHex) != null

    /**
     * Builds the accent roles for both schemes from [seedHex] (e.g. `"#0082c9"`, with or without
     * the leading `#`, 3- or 6-digit). Returns `null` for anything unparseable so the caller falls
     * back to the default scheme rather than rendering a broken one.
     */
    fun from(seedHex: String): Accents? {
        val seed = parseHex(seedHex) ?: return null
        val (h, s, lSeed) = rgbToHsl(seed)

        val lightPrimary = hslToArgb(h, s, lSeed.coerceIn(LIGHT_PRIMARY_MIN_L, LIGHT_PRIMARY_MAX_L))
        val lightContainer = hslToArgb(h, s, CONTAINER_LIGHT_L)
        val light = AccentScheme(
            primary = lightPrimary,
            onPrimary = onColorFor(lightPrimary),
            primaryContainer = lightContainer,
            onPrimaryContainer = onColorFor(lightContainer),
            inversePrimary = hslToArgb(h, s, INVERSE_LIGHT_L),
        )

        val darkPrimary = hslToArgb(h, s, PRIMARY_DARK_L)
        val darkContainer = hslToArgb(h, s, CONTAINER_DARK_L)
        val dark = AccentScheme(
            primary = darkPrimary,
            onPrimary = onColorFor(darkPrimary),
            primaryContainer = darkContainer,
            onPrimaryContainer = onColorFor(darkContainer),
            inversePrimary = hslToArgb(h, s, INVERSE_DARK_L),
        )

        return Accents(light, dark)
    }

    /**
     * White or black against [background], whichever the eye can read: white when it clears
     * [ON_COLOUR_MIN_CONTRAST] (3:1), otherwise black. Both are returned opaque.
     */
    fun onColorFor(background: Int): Int =
        if (contrastRatio(WHITE, background) >= ON_COLOUR_MIN_CONTRAST) WHITE else BLACK

    /** WCAG 2.x contrast ratio between two opaque colours, in `[1, 21]`. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** WCAG 2.x relative luminance of an opaque colour, in `[0, 1]`. */
    fun relativeLuminance(argb: Int): Double {
        fun channel(c8: Int): Double {
            val c = c8 / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Parses `#RRGGBB`, `RRGGBB`, `#RGB` or `RGB` (case-insensitive) into an opaque `0xFFRRGGBB`
     * ARGB int, or `null` if it is not one of those. Any alpha the string carries is ignored --
     * a theme colour is always painted opaque.
     */
    fun parseHex(raw: String): Int? {
        val body = raw.trim().removePrefix("#")
        val rrggbb = when (body.length) {
            3 -> buildString { body.forEach { append(it); append(it) } }
            6 -> body
            else -> return null
        }
        if (!rrggbb.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val rgb = rrggbb.toLong(16)
        return (0xFF000000L or rgb).toInt()
    }

    private fun rgbToHsl(argb: Int): Triple<Double, Double, Double> {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val l = (max + min) / 2.0
        val s = if (delta == 0.0) 0.0 else delta / (1.0 - abs(2.0 * l - 1.0))
        val h = when {
            delta == 0.0 -> 0.0
            max == r -> 60.0 * (((g - b) / delta) % 6.0)
            max == g -> 60.0 * (((b - r) / delta) + 2.0)
            else -> 60.0 * (((r - g) / delta) + 4.0)
        }.let { if (it < 0.0) it + 360.0 else it }
        return Triple(h, s, l)
    }

    private fun hslToArgb(h: Double, s: Double, l: Double): Int {
        val c = (1.0 - abs(2.0 * l - 1.0)) * s
        val x = c * (1.0 - abs((h / 60.0) % 2.0 - 1.0))
        val m = l - c / 2.0
        val (r1, g1, b1) = when {
            h < 60.0 -> Triple(c, x, 0.0)
            h < 120.0 -> Triple(x, c, 0.0)
            h < 180.0 -> Triple(0.0, c, x)
            h < 240.0 -> Triple(0.0, x, c)
            h < 300.0 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val r = ((r1 + m) * 255.0).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255.0).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255.0).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
