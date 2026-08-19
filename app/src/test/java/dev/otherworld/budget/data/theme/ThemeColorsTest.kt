package dev.otherworld.budget.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure-JVM tests for the colour maths. No Android, no MockWebServer -- [ThemeColors] is
 * deterministic and side-effect-free, so these just feed it seeds and check the bytes.
 */
class ThemeColorsTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun r(argb: Int) = (argb shr 16) and 0xFF
    private fun g(argb: Int) = (argb shr 8) and 0xFF
    private fun b(argb: Int) = argb and 0xFF

    /** Asserts two opaque colours are within [delta] on every channel (round-trip tolerance). */
    private fun assertCloseColor(expected: Int, actual: Int, delta: Int = 3) {
        assertTrue(
            "expected #%06X within $delta of #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF),
            abs(r(expected) - r(actual)) <= delta &&
                abs(g(expected) - g(actual)) <= delta &&
                abs(b(expected) - b(actual)) <= delta,
        )
    }

    // --- parsing / usability -----------------------------------------------------------------

    @Test fun `parses 6-digit hex with and without hash`() {
        assertEquals(0xFF0082C9.toInt(), ThemeColors.parseHex("#0082c9"))
        assertEquals(0xFF0082C9.toInt(), ThemeColors.parseHex("0082C9"))
    }

    @Test fun `parses 3-digit shorthand`() {
        // #08c -> #0088cc
        assertEquals(0xFF0088CC.toInt(), ThemeColors.parseHex("#08c"))
    }

    @Test fun `rejects malformed hex`() {
        assertNull(ThemeColors.parseHex("nope"))
        assertNull(ThemeColors.parseHex("#12"))
        assertNull(ThemeColors.parseHex("#12345"))
        assertNull(ThemeColors.parseHex("#gggggg"))
        assertNull(ThemeColors.parseHex(""))
        assertFalse(ThemeColors.isUsable("not-a-colour"))
        assertTrue(ThemeColors.isUsable("#0082c9"))
    }

    @Test fun `from returns null for an unusable seed so callers fall back`() {
        assertNull(ThemeColors.from("rgb(0,130,201)"))
        assertNull(ThemeColors.from("#xyz"))
        assertNotNull(ThemeColors.from("#0082c9"))
    }

    // --- WCAG helpers ------------------------------------------------------------------------

    @Test fun `contrast of white on black is the WCAG maximum`() {
        assertEquals(21.0, ThemeColors.contrastRatio(white, black), 0.05)
        assertEquals(1.0, ThemeColors.contrastRatio(white, white), 0.001)
    }

    @Test fun `relative luminance runs black to white`() {
        assertEquals(0.0, ThemeColors.relativeLuminance(black), 0.0001)
        assertEquals(1.0, ThemeColors.relativeLuminance(white), 0.0001)
    }

    // --- the load-bearing part: on-colour is luminance-derived, never hardcoded --------------

    @Test fun `dark seed 0082c9 takes white onPrimary`() {
        val light = ThemeColors.from("#0082c9")!!.light
        assertEquals(white, light.onPrimary)
    }

    @Test fun `light seed khaki takes black onPrimary`() {
        // A user's Nextcloud themed pale khaki: white text here would be unreadable, so black.
        val light = ThemeColors.from("#F0E68C")!!.light
        assertEquals(black, light.onPrimary)
    }

    @Test fun `pure white and pure black seeds stay legible`() {
        // White seed -> a light accent, so black text; black seed -> a dark accent, so white text.
        assertEquals(black, ThemeColors.from("#FFFFFF")!!.light.onPrimary)
        assertEquals(white, ThemeColors.from("#000000")!!.light.onPrimary)
        // And neither collapses to an invisible primary against a white page / black page.
        assertTrue(ThemeColors.contrastRatio(ThemeColors.from("#FFFFFF")!!.light.primary, white) > 1.5)
        assertTrue(ThemeColors.contrastRatio(ThemeColors.from("#000000")!!.dark.primary, black) > 1.5)
    }

    @Test fun `onPrimary always clears the 3-to-1 large-text bar`() {
        // Sweep a spread of seeds; whatever primary comes out, its onPrimary must be readable.
        for (seed in listOf("#0082c9", "#F0E68C", "#FFFFFF", "#000000", "#FF0000", "#00FF00",
            "#123456", "#7f7f7f", "#e91e63", "#4caf50", "#ffeb3b")) {
            val accents = ThemeColors.from(seed)!!
            for (scheme in listOf(accents.light, accents.dark)) {
                assertTrue(
                    "onPrimary unreadable for $seed",
                    ThemeColors.contrastRatio(scheme.onPrimary, scheme.primary) >= 3.0,
                )
                assertTrue(
                    "onPrimaryContainer unreadable for $seed",
                    ThemeColors.contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer) >= 3.0,
                )
            }
        }
    }

    // --- reproduces today's look for the default blue ----------------------------------------

    @Test fun `0082c9 reproduces the app's existing blue ramp near enough`() {
        val accents = ThemeColors.from("#0082c9")!!
        // primary is the seed itself; container is the app's Blue90; inverse is its Blue80 end.
        assertCloseColor(0xFF0082C9.toInt(), accents.light.primary)
        assertCloseColor(0xFFCCEDFF.toInt(), accents.light.primaryContainer)
        assertCloseColor(0xFF8FD8FF.toInt(), accents.light.inversePrimary, delta = 12)
        // Dark primary is the light end of the ramp (Blue80), as the static dark scheme uses.
        assertCloseColor(0xFF8FD8FF.toInt(), accents.dark.primary, delta = 12)
    }

    // --- scheme shape ------------------------------------------------------------------------

    @Test fun `light primary is darker than its container, dark primary is lighter than its container`() {
        val a = ThemeColors.from("#0082c9")!!
        assertTrue(
            ThemeColors.relativeLuminance(a.light.primary) < ThemeColors.relativeLuminance(a.light.primaryContainer),
        )
        assertTrue(
            ThemeColors.relativeLuminance(a.dark.primary) > ThemeColors.relativeLuminance(a.dark.primaryContainer),
        )
    }

    @Test fun `on-colours are only ever pure black or white`() {
        val a = ThemeColors.from("#e91e63")!!
        for (c in listOf(a.light.onPrimary, a.light.onPrimaryContainer, a.dark.onPrimary, a.dark.onPrimaryContainer)) {
            assertTrue(c == white || c == black)
        }
    }

    @Test fun `generation is deterministic`() {
        assertEquals(ThemeColors.from("#0082c9"), ThemeColors.from("#0082c9"))
        assertEquals(ThemeColors.from("#F0E68C"), ThemeColors.from("#f0e68c"))
    }
}
