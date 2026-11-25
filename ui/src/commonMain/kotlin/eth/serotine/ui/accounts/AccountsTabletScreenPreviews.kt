package eth.serotine.ui.accounts

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.domain.model.Network
import eth.serotine.ui.PreviewDevice
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.TABLET_HEIGHT_DP
import eth.serotine.ui.TABLET_WIDTH_DP
import eth.serotine.ui.accountdetail.AccountDetailActivitySelectedSample
import eth.serotine.ui.accountdetail.AccountDetailDailySample
import eth.serotine.ui.activitydetail.ActivityDetailSentSample
import eth.serotine.ui.components.AppTabs
import eth.serotine.ui.components.DetailBesideAccount
import eth.serotine.ui.components.ListBesideDetail
import eth.serotine.ui.components.TabRail
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Accounts tab on a tablet held sideways, in Detailed view (docs/wallet-ux.md, *Navigation*): the tabs in a
// rail, the Accounts list and beside it the account open, or the pane that says none is. Drawn with the
// rail and the layout App draws, around the list's and the account's own screens with their previews'
// sample data. AccountsTabletScreenPreviewsTest renders each one in accountsTabletScreenPreviews, at
// the end, at the tablet's size.

/** Daily, on Sepolia, open beside the list. */
private val shown = SelectedAccount(Network.SEPOLIA.chainId, "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")

/**
 * The Accounts tab of a tablet's window, in either view: the tabs in a rail, the [list] and beside it the
 * [detail], with the rail and the layout App draws ([TabRail], [ListBesideDetail]).
 */
@Composable
internal fun AccountsTabletWindow(darkTheme: Boolean, list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    SerotineTheme(darkTheme = darkTheme) {
        Row(Modifier.fillMaxSize()) {
            TabRail(listOf(AppTabs.ACCOUNTS, AppTabs.SETTINGS), AppTabs.ACCOUNTS, onSelect = {})
            ListBesideDetail(
                listShown = true,
                list = list,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                detail = detail,
            )
        }
    }
}

@Composable
private fun AccountsTabletPreview(darkTheme: Boolean, selected: SelectedAccount?, detail: @Composable () -> Unit) =
    AccountsTabletWindow(darkTheme, list = { AccountsListSample(selected) }, detail = detail)

@Preview(widthDp = TABLET_WIDTH_DP, heightDp = TABLET_HEIGHT_DP)
@Composable
private fun AccountsTabletAccountOpenPreview() =
    AccountsTabletPreview(darkTheme = false, shown) { AccountDetailDailySample() }

@Preview(widthDp = TABLET_WIDTH_DP, heightDp = TABLET_HEIGHT_DP)
@Composable
private fun AccountsTabletAccountOpenDarkPreview() =
    AccountsTabletPreview(darkTheme = true, shown) { AccountDetailDailySample() }

/**
 * An Activity row's detail open beside its account, a third column (owner's request, 2026-11-25): the tablet is wider
 * than 1200 dp. Drawn with the split App draws ([DetailBesideAccount]).
 */
@Preview(widthDp = TABLET_WIDTH_DP, heightDp = TABLET_HEIGHT_DP)
@Composable
private fun AccountsTabletActivityDetailOpenPreview() =
    AccountsTabletPreview(darkTheme = false, shown) {
        DetailBesideAccount(true, account = { AccountDetailActivitySelectedSample() }) { ActivityDetailSentSample() }
    }

@Preview(widthDp = TABLET_WIDTH_DP, heightDp = TABLET_HEIGHT_DP)
@Composable
private fun AccountsTabletActivityDetailOpenDarkPreview() =
    AccountsTabletPreview(darkTheme = true, shown) {
        DetailBesideAccount(true, account = { AccountDetailActivitySelectedSample() }) { ActivityDetailSentSample() }
    }

/** No account open yet: the pane beside the list says how to open one. */
@Preview(widthDp = TABLET_WIDTH_DP, heightDp = TABLET_HEIGHT_DP)
@Composable
private fun AccountsTabletNoAccountOpenPreview() =
    AccountsTabletPreview(darkTheme = false, selected = null) { NoAccountOpenScreen(NO_ACCOUNT_OPEN_HINT) }

/** Every preview above, which AccountsTabletScreenPreviewsTest renders: a new preview goes in here too. */
internal val accountsTabletScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("account-open", PreviewDevice.TABLET) { AccountsTabletAccountOpenPreview() },
    ScreenPreview("account-open-dark", PreviewDevice.TABLET) { AccountsTabletAccountOpenDarkPreview() },
    ScreenPreview("no-account-open", PreviewDevice.TABLET) { AccountsTabletNoAccountOpenPreview() },
    ScreenPreview("activity-detail-open", PreviewDevice.TABLET) { AccountsTabletActivityDetailOpenPreview() },
    ScreenPreview("activity-detail-open-dark", PreviewDevice.TABLET) { AccountsTabletActivityDetailOpenDarkPreview() },
)
