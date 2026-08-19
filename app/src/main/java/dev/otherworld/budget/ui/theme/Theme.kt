package dev.otherworld.budget.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.otherworld.budget.data.theme.ThemeColors

/**
 * Light scheme. `primary` is Nextcloud blue itself (`#0082c9`), so the accent the user sees on
 * the Connect button, the capture FAB and the tonally-elevated queue banners is the same blue as
 * the server the app connects to, rather than Material3's stock purple.
 *
 * `surfaceTint` is left to default to `primary`, which is what carries that blue into every
 * `Surface(tonalElevation = …)` -- the Capture screen's two banners are exactly that -- so they
 * pick the accent up automatically instead of needing their own colour.
 *
 * Contrast note: `onPrimary` here (white on the default `#0082c9`) is 4.2:1 -- clearing WCAG AA
 * for large text and non-text UI components (3:1) but just under the 4.5:1 small-text threshold.
 * It is kept deliberately for the *default* scheme: it is the exact pairing Nextcloud's own web UI
 * and mobile clients use, and shifting the brand blue a few percent darker to win 0.3 of a point
 * would make the accent visibly not-Nextcloud-blue next to them. What the earlier build did wrong
 * was *hardcode* white for every conceivable brand colour: once the app adopts a signed-in
 * server's theme (see [BudgetReceiptsTheme]'s `seedHex`), that white is no longer assumed -- the
 * foreground is chosen by WCAG relative luminance in [ThemeColors], so a pale or bright themed
 * server gets black text where white would be unreadable. Everything actually *read* -- field
 * labels, banner text, the error notice -- is on a surface role at 12:1 or better regardless.
 */
private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    // Stated, not defaulted: Material3 1.3 draws dropdown menus, date pickers and alert dialogs
    // on these roles, and their defaults are the baseline purple-tinted neutrals -- see
    // Color.kt's note on the surfaceContainer ramp.
    surfaceDim = SurfaceDimL,
    surfaceBright = SurfaceBrightL,
    surfaceContainerLowest = SurfaceContainerLowestL,
    surfaceContainerLow = SurfaceContainerLowL,
    surfaceContainer = SurfaceContainerL,
    surfaceContainerHigh = SurfaceContainerHighL,
    surfaceContainerHighest = SurfaceContainerHighestL,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    inversePrimary = InversePrimaryLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

/**
 * Dark scheme. `primary` is the *light* end of the same blue ramp, not `#0082c9` itself:
 * Material3 flips the tonal direction in dark mode, and the seed at tone 40 on a near-black
 * surface is too dim to read as an accent at all -- the FAB would recede instead of standing
 * out. The seed returns as `inversePrimary`, which is what a light-on-dark snackbar action uses.
 */
private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceDim = SurfaceDimD,
    surfaceBright = SurfaceBrightD,
    surfaceContainerLowest = SurfaceContainerLowestD,
    surfaceContainerLow = SurfaceContainerLowD,
    surfaceContainer = SurfaceContainerD,
    surfaceContainerHigh = SurfaceContainerHighD,
    surfaceContainerHighest = SurfaceContainerHighestD,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    inversePrimary = InversePrimaryDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

/**
 * The app's single Material3 theme wrapper, used at the root of [dev.otherworld.budget.MainActivity]'s
 * content. It switches between the two schemes above on the system dark-mode setting and does
 * nothing else -- no typography override, because nothing in the product spec calls for a bespoke
 * type scale, and no dynamic ("Material You") colour, because pinning to a fixed pair of schemes
 * keeps every screen's appearance identical across devices, which is easier to reason about and
 * to screenshot for the F-Droid listing (Task 17).
 *
 * [seedHex] carries the runtime brand colour: `null` -- logged out, no fetch yet, a failed fetch,
 * or a theming-less server -- uses the static schemes above unchanged, so a fresh/logged-out user
 * sees exactly today's Nextcloud blue. A non-null seed (the colour the signed-in server reports)
 * overrides *only* the accent roles the app paints its brand on -- primary, its container, and the
 * inverse-primary a snackbar uses -- via [ThemeColors]; every neutral role (surfaces, outlines,
 * secondary/tertiary, error) is kept from the static scheme, so the brand colour drives the
 * accents and never tints the page. `surfaceTint` is realigned to the new primary so the Capture
 * banners (`Surface(tonalElevation=…)`) pick the colour up too. A malformed seed makes
 * [ThemeColors.from] return `null` and falls back here to the static scheme.
 */
@Composable
fun BudgetReceiptsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    seedHex: String? = null,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColors else LightColors
    val colorScheme = remember(seedHex, darkTheme) { base.applyingSeed(seedHex, darkTheme) }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * Returns this static scheme with the brand accent roles replaced by ones derived from [seedHex],
 * or the scheme unchanged when there is no usable seed. Pure over its inputs, so it is safe to
 * `remember`.
 */
private fun ColorScheme.applyingSeed(seedHex: String?, darkTheme: Boolean): ColorScheme {
    val accents = seedHex?.let { ThemeColors.from(it) } ?: return this
    val roles = if (darkTheme) accents.dark else accents.light
    val primary = Color(roles.primary)
    return copy(
        primary = primary,
        onPrimary = Color(roles.onPrimary),
        primaryContainer = Color(roles.primaryContainer),
        onPrimaryContainer = Color(roles.onPrimaryContainer),
        inversePrimary = Color(roles.inversePrimary),
        // surfaceTint defaults to `primary` in the static scheme, but that default was captured at
        // construction; realign it so tonally-elevated surfaces (the Capture banners) tint with the
        // adopted colour rather than the old blue.
        surfaceTint = primary,
    )
}
