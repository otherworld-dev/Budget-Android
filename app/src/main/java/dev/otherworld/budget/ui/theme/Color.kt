package dev.otherworld.budget.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's **default** palette, derived by hand from a single seed -- Nextcloud blue
 * [NextcloudBlue], `#0082c9`, the accent the user already associates with the server this app
 * talks to. This is what a fresh or logged-out user sees, and the fallback whenever no server
 * theme colour has been adopted. Once signed in, the app adopts the server's own Nextcloud theme
 * colour and regenerates the *accent* roles (primary and its container, plus inverse-primary) from
 * it via [dev.otherworld.budget.data.theme.ThemeColors] -- choosing each on-colour by WCAG
 * luminance rather than the hardcoded white below -- while keeping every neutral role here
 * untouched (see [BudgetReceiptsTheme]). The default blue is unchanged, so nothing shifts for a
 * logged-out user or a default-themed server.
 *
 * Hand-written rather than generated at runtime: Material3's own seeding
 * (`dynamicColorScheme`/`Material Theme Builder`) would either pull in the device's wallpaper
 * colours -- which the spec explicitly rules out, since every screen should look the same on
 * every device and in every screenshot -- or add a colour-utilities dependency for something
 * that resolves to a fixed table of constants anyway. These *are* that table.
 *
 * Construction: the primary ramp is HSL hue 201 (the seed's own hue) at full saturation, sampled
 * at the tones Material3 assigns to each role -- 40 for light `primary`, 80 for dark, 90/10 for
 * the containers, and so on. Secondary is the same hue at ~45% saturation, tertiary is hue 261
 * (hue + 60, Material's own tertiary rotation) at 35%, and the neutrals are the same hue at 4-8%
 * so surfaces sit under the accent rather than fighting it. Error stays on the Material3 baseline
 * reds: nothing about a Nextcloud accent should change what "this went wrong" looks like.
 */
internal val NextcloudBlue = Color(0xFF0082C9)

// --- Primary ramp: hue 201, S 100% -------------------------------------------------------
private val Blue10 = Color(0xFF002133)
private val Blue20 = Color(0xFF003552)
private val Blue30 = Color(0xFF004C75)
private val Blue40 = NextcloudBlue          // the seed itself; L 39% is Material's tone 40
private val Blue80 = Color(0xFF8FD8FF)
private val Blue90 = Color(0xFFCCEDFF)

// --- Secondary: hue 201, S ~45% ----------------------------------------------------------
private val BlueGrey10 = Color(0xFF121C21)
private val BlueGrey20 = Color(0xFF243842)
private val BlueGrey30 = Color(0xFF365363)
private val BlueGrey40 = Color(0xFF476F85)
private val BlueGrey80 = Color(0xFFBDD1DB)
private val BlueGrey90 = Color(0xFFDAE9F1)

// --- Tertiary: hue 261 (seed hue + 60), S 35% --------------------------------------------
private val Violet10 = Color(0xFF171122)
private val Violet20 = Color(0xFF2E2145)
private val Violet30 = Color(0xFF443267)
private val Violet40 = Color(0xFF5B428A)
private val Violet80 = Color(0xFFC7BADE)
private val Violet90 = Color(0xFFE3DDEE)

// --- Neutrals: hue 201 at 4-8% saturation ------------------------------------------------
private val Neutral10 = Color(0xFF191C1E)
private val Neutral20 = Color(0xFF2E3133)
private val Neutral90 = Color(0xFFE1E3E5)
private val Neutral95 = Color(0xFFEFF1F3)
private val Neutral99 = Color(0xFFF8FAFD)
private val NeutralDark = Color(0xFF101416)

/**
 * The `surfaceContainer*` ramp. Easy to leave unset -- `lightColorScheme()`/`darkColorScheme()`
 * fill them in happily -- and then every [androidx.compose.material3.ExposedDropdownMenu],
 * [androidx.compose.material3.DatePickerDialog] and [androidx.compose.material3.AlertDialog] in
 * the app renders on Material3's *baseline purple-tinted* neutrals, floating lilac over a cool
 * blue-white page. Material3 1.3 routes those components' container colours through these roles
 * rather than through `surface`, so they have to be stated too. Same hue 201 at ~25% (light) /
 * ~18% (dark) saturation as the rest of the neutrals, ordered per M3: in **light** mode a higher
 * container level reads *darker* (`Lowest` is pure white, `Highest` the dimmest of the ramp),
 * while in **dark** mode it reads *lighter*. Elevation is a step away from the page either way,
 * and in light mode that direction is down, not up -- which is what the constants below do.
 */
private val SurfaceDimLight = Color(0xFFD6E0E6)
private val SurfaceBrightLight = Color(0xFFF9FAFB)
private val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
private val SurfaceContainerLowLight = Color(0xFFF2F6F7)
private val SurfaceContainerLight = Color(0xFFECF1F4)
private val SurfaceContainerHighLight = Color(0xFFE6ECF0)
private val SurfaceContainerHighestLight = Color(0xFFDFE7EC)

private val SurfaceDimDark = Color(0xFF0D1012)
private val SurfaceBrightDark = Color(0xFF324148)
private val SurfaceContainerLowestDark = Color(0xFF080B0C)
private val SurfaceContainerLowDark = Color(0xFF151B1E)
private val SurfaceContainerDark = Color(0xFF192024)
private val SurfaceContainerHighDark = Color(0xFF242E33)
private val SurfaceContainerHighestDark = Color(0xFF2E3B42)

private val NeutralVariant30 = Color(0xFF41484D)
private val NeutralVariant50 = Color(0xFF71787E)
private val NeutralVariant60 = Color(0xFF8B9297)
private val NeutralVariant80 = Color(0xFFC1C7CE)
private val NeutralVariant90 = Color(0xFFDDE3EA)

// --- Error: Material3 baseline ------------------------------------------------------------
internal val ErrorLight = Color(0xFFBA1A1A)
internal val OnErrorLight = Color(0xFFFFFFFF)
internal val ErrorContainerLight = Color(0xFFFFDAD6)
internal val OnErrorContainerLight = Color(0xFF410002)
internal val ErrorDark = Color(0xFFFFB4AB)
internal val OnErrorDark = Color(0xFF690005)
internal val ErrorContainerDark = Color(0xFF93000A)
internal val OnErrorContainerDark = Color(0xFFFFDAD6)

// Roles, grouped so Theme.kt reads as a scheme rather than a wall of hex.
internal val PrimaryLight = Blue40
internal val OnPrimaryLight = Color(0xFFFFFFFF)
internal val PrimaryContainerLight = Blue90
internal val OnPrimaryContainerLight = Blue10
internal val SecondaryLight = BlueGrey40
internal val OnSecondaryLight = Color(0xFFFFFFFF)
internal val SecondaryContainerLight = BlueGrey90
internal val OnSecondaryContainerLight = BlueGrey10
internal val TertiaryLight = Violet40
internal val OnTertiaryLight = Color(0xFFFFFFFF)
internal val TertiaryContainerLight = Violet90
internal val OnTertiaryContainerLight = Violet10
internal val SurfaceLight = Neutral99
internal val OnSurfaceLight = Neutral10
internal val SurfaceDimL = SurfaceDimLight
internal val SurfaceBrightL = SurfaceBrightLight
internal val SurfaceContainerLowestL = SurfaceContainerLowestLight
internal val SurfaceContainerLowL = SurfaceContainerLowLight
internal val SurfaceContainerL = SurfaceContainerLight
internal val SurfaceContainerHighL = SurfaceContainerHighLight
internal val SurfaceContainerHighestL = SurfaceContainerHighestLight
internal val SurfaceVariantLight = NeutralVariant90
internal val OnSurfaceVariantLight = NeutralVariant30
internal val OutlineLight = NeutralVariant50
internal val OutlineVariantLight = NeutralVariant80
internal val InverseSurfaceLight = Neutral20
internal val InverseOnSurfaceLight = Neutral95
internal val InversePrimaryLight = Blue80

internal val PrimaryDark = Blue80
internal val OnPrimaryDark = Blue20
internal val PrimaryContainerDark = Blue30
internal val OnPrimaryContainerDark = Blue90
internal val SecondaryDark = BlueGrey80
internal val OnSecondaryDark = BlueGrey20
internal val SecondaryContainerDark = BlueGrey30
internal val OnSecondaryContainerDark = BlueGrey90
internal val TertiaryDark = Violet80
internal val OnTertiaryDark = Violet20
internal val TertiaryContainerDark = Violet30
internal val OnTertiaryContainerDark = Violet90
internal val SurfaceDark = NeutralDark
internal val OnSurfaceDark = Neutral90
internal val SurfaceDimD = SurfaceDimDark
internal val SurfaceBrightD = SurfaceBrightDark
internal val SurfaceContainerLowestD = SurfaceContainerLowestDark
internal val SurfaceContainerLowD = SurfaceContainerLowDark
internal val SurfaceContainerD = SurfaceContainerDark
internal val SurfaceContainerHighD = SurfaceContainerHighDark
internal val SurfaceContainerHighestD = SurfaceContainerHighestDark
internal val SurfaceVariantDark = NeutralVariant30
internal val OnSurfaceVariantDark = NeutralVariant80
internal val OutlineDark = NeutralVariant60
internal val OutlineVariantDark = NeutralVariant30
internal val InverseSurfaceDark = Neutral90
internal val InverseOnSurfaceDark = Neutral20
internal val InversePrimaryDark = Blue40
