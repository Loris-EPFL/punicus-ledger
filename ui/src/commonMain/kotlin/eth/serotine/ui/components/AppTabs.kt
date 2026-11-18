package eth.serotine.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** A tab of the app's bar or rail, as the user sees it: its [label] and its [icon]. */
data class TabItem(val label: String, val icon: ImageVector)

/** The app's tabs (docs/wallet-ux.md, *Navigation*), as `App` draws them and as the previews of a window do. */
object AppTabs {
    /** The accounts, and every screen opened from them. */
    val ACCOUNTS = TabItem("Accounts", SerotineIcons.Wallet)

    /** The app's settings. */
    val SETTINGS = TabItem("Settings", Icons.Filled.Settings)

    /** The debug log, shown only while the user has it turned on in Settings. */
    val DEBUG = TabItem("Debug", Icons.Filled.Build)
}

/** The [tabs] at the side of a wide window, [selected] marked; a tap asks [onSelect]. */
@Composable
fun TabRail(tabs: List<TabItem>, selected: TabItem?, onSelect: (TabItem) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(modifier) {
        tabs.forEach { tab ->
            NavigationRailItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

/** The [tabs] at the bottom of a narrow window, [selected] marked; a tap asks [onSelect]. */
@Composable
fun TabBar(tabs: List<TabItem>, selected: TabItem?, onSelect: (TabItem) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}
