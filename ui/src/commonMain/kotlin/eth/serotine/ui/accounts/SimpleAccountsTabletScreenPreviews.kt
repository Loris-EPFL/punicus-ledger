package eth.serotine.ui.accounts

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Network
import eth.serotine.ui.PreviewDevice
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.TABLET_HEIGHT_DP
import eth.serotine.ui.TABLET_WIDTH_DP
import eth.serotine.ui.accountdetail.SimpleAccountActivitySelectedSample
import eth.serotine.ui.accountdetail.SimpleAccountDetailMainSample
import eth.serotine.ui.activitydetail.SimpleActivityDetailSentSample
import eth.serotine.ui.components.DetailBesideAccount
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Accounts tab on a tablet held sideways, in simple mode, the default view (docs/wallet-ux.md,
// *Navigation*): the tabs in a rail, the Accounts list and beside it the account open, or the pane that
// says none is. Drawn by AccountsTabletWindow (AccountsTabletScreenPreviews.kt), around the list's and
// the account's own screens with their previews' sample data. SimpleAccountsTabletScreenPreviewsTest
// renders each one in simpleAccountsTabletScreenPreviews, at the end, at the tablet's size.

/** Main, on Ethereum, open beside the list. */
private val shown = SelectedAccount(Network.ETHEREUM.chainId, "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")

@Composable
private fun SimpleAccountsTabletPreview(
    darkTheme: Boolean,
    selected: SelectedAccount?,
    detail: @Composable () -> Unit,
) = AccountsTabletWindow(darkTheme, list = { SimpleAccountsListSample(selected) }, detail = detail)

@Preview(widthDp = TABLET_WIDTH_DP, heightDp = TABLET_HEIGHT_DP)
@Composable
private fun SimpleAccountsTabletAccountOpenPreview() =
    SimpleAccountsTabletPreview(darkTheme = false, shown) { SimpleAccountDetailMainSample() }

@Preview(widthDp = TABLET_WIDTH_DP, heightDp = TABLET_HEIGHT_DP)
@Composable
private fun SimpleAccountsTabletAccountOpenDarkPreview() =
    SimpleAccountsTabletPreview(darkTheme = true, shown) { SimpleAccountDetailMainSample() }

/** An Activity row's detail open beside its account, a third column (owner's request, 2026-11-25). */
@Preview(widthDp = TABLET_WIDTH_DP, heightDp = TABLET_HEIGHT_DP)
@Composable
private fun SimpleAccountsTabletActivityDetailOpenPreview() =
    SimpleAccountsTabletPreview(darkTheme = false, shown) {
        DetailBesideAccount(true, account = { SimpleAccountActivitySelectedSample() }) {
            SimpleActivityDetailSentSample()
        }
    }

@Preview(widthDp = TABLET_WIDTH_DP, heightDp = TABLET_HEIGHT_DP)
@Composable
private fun SimpleAccountsTabletActivityDetailOpenDarkPreview() =
    SimpleAccountsTabletPreview(darkTheme = true, shown) {
        DetailBesideAccount(true, account = { SimpleAccountActivitySelectedSample() }) {
            SimpleActivityDetailSentSample()
        }
    }

/** No account open yet: the pane beside the list says how to open one. */
@Preview(widthDp = TABLET_WIDTH_DP, heightDp = TABLET_HEIGHT_DP)
@Composable
private fun SimpleAccountsTabletNoAccountOpenPreview() =
    SimpleAccountsTabletPreview(darkTheme = false, selected = null) { NoAccountOpenScreen(NO_ACCOUNT_OPEN_HINT) }

/** Every preview above, which SimpleAccountsTabletScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleAccountsTabletScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("account-open", PreviewDevice.TABLET) { SimpleAccountsTabletAccountOpenPreview() },
    ScreenPreview("account-open-dark", PreviewDevice.TABLET) { SimpleAccountsTabletAccountOpenDarkPreview() },
    ScreenPreview("no-account-open", PreviewDevice.TABLET) { SimpleAccountsTabletNoAccountOpenPreview() },
    ScreenPreview("activity-detail-open", PreviewDevice.TABLET) { SimpleAccountsTabletActivityDetailOpenPreview() },
    ScreenPreview("activity-detail-open-dark", PreviewDevice.TABLET) {
        SimpleAccountsTabletActivityDetailOpenDarkPreview()
    },
)
