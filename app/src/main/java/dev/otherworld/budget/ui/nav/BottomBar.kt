package dev.otherworld.budget.ui.nav

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.otherworld.budget.R

/**
 * The three top-level tabs -- Capture, Overview, Activity (see [TOP_LEVEL_ROUTES]). [currentRoute]
 * drives which item shows selected; it comes from [BudgetNavHost]'s own
 * `currentBackStackEntryAsState()` rather than being tracked here, so this composable has no
 * navigation state of its own to get out of sync with the real back stack.
 *
 * Icon [Icon.contentDescription] is left null on all three: each item also carries a visible
 * text label below the icon (the Material 3 default for [NavigationBarItem]), so a screen reader
 * would otherwise announce the destination twice.
 */
@Composable
fun BudgetBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.CAPTURE,
            onClick = { onSelect(Routes.CAPTURE) },
            icon = { Icon(painterResource(R.drawable.ic_nav_capture), contentDescription = null) },
            label = { Text(stringResource(R.string.nav_capture)) },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.OVERVIEW,
            onClick = { onSelect(Routes.OVERVIEW) },
            icon = { Icon(painterResource(R.drawable.ic_nav_overview), contentDescription = null) },
            label = { Text(stringResource(R.string.nav_overview)) },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.ACTIVITY,
            onClick = { onSelect(Routes.ACTIVITY) },
            icon = { Icon(painterResource(R.drawable.ic_nav_activity), contentDescription = null) },
            label = { Text(stringResource(R.string.nav_activity)) },
        )
    }
}
