package eth.serotine.ui.accounts

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.ui.components.AMOUNT_MASK
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.HIDE_AMOUNTS
import eth.serotine.ui.components.HIDE_AMOUNTS_TAG
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.SHOW_AMOUNTS
import eth.serotine.ui.theme.SerotineTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleAccountsScreen] headless. */
class SimpleAccountsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var refreshes = 0
    private val actions = mutableListOf<AccountAction>()

    private val ethereum = NetworkLabel.of(Network.ETHEREUM)
    private val main = AccountItem(
        "Main",
        "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        ethereum.chainId,
        AccountKind.EOA,
        Control.FULL_CONTROL,
        "3.2041 ETH",
        balanceVerified = true,
        network = ethereum,
        networkLine = "Ethereum",
        accountTotal = "€12,025.55",
    )
    private val strip = LightClientItem("Verified at block 23,614,077 · 8 s ago", null, false, VERIFIED)

    private var toggles = 0

    private fun show(state: AccountsUiState, selected: SelectedAccount? = null) = rule.setContent {
        SerotineTheme {
            SimpleAccountsScreen(
                state = state,
                selected = selected,
                onWatchAddress = {},
                onImportPhrase = {},
                onCreateAccount = {},
                onCreateSafe = {},
                onAccountAction = { actions += it },
                onRefresh = { refreshes++ },
                onToggleAmounts = { toggles++ },
            )
        }
    }

    private fun ready(vararg accounts: AccountItem) = AccountsUiState.Ready(
        listOf(NetworkSection(ethereum.chainId, "Ethereum", false, true, strip, accounts.toList())),
        total = "€12,025.55",
        freshness = "Updated 2 min ago · pull down to refresh",
    )

    @Test
    fun eachNetworksAccountsHaveAHeaderSayingItsSyncStateWhoseTapFoldsThemAway() {
        val gnosis = NetworkLabel.of(Network.GNOSIS)
        val onGnosis =
            main.copy(label = "Card", chainId = gnosis.chainId, network = gnosis, networkLine = "Gnosis Chain")
        val syncing = LightClientItem(
            "Verifying sync committees",
            0.25f,
            true,
            NOT_VERIFIED,
            simpleHeader = "Syncing · 3 of 12 periods",
        )
        show(
            AccountsUiState.Ready(
                listOf(
                    NetworkSection(
                        ethereum.chainId,
                        "Ethereum",
                        false,
                        true,
                        strip.copy(simpleHeader = "Up to date"),
                        listOf(main),
                    ),
                    NetworkSection(gnosis.chainId, "Gnosis Chain", false, true, syncing, listOf(onGnosis)),
                ),
                total = "€12,025.55",
                freshness = "Updated 2 min ago · pull down to refresh",
            ),
        )

        // The header's words are one heading to a screen reader: its node holds them.
        rule.onNodeWithTag(ACCOUNTS_NETWORK_HEADER_TAG + ethereum.chainId).assert(hasText("Up to date"))
        rule.onNodeWithTag(ACCOUNTS_NETWORK_HEADER_TAG + gnosis.chainId).assert(hasText("Syncing · 3 of 12 periods"))
        // A tap on a header folds its network's accounts away, and opens nothing; another brings them back.
        rule.onNodeWithTag(ACCOUNTS_NETWORK_HEADER_TAG + gnosis.chainId).performClick()
        assertEquals(emptyList(), actions)
        rule.onNodeWithText("Card").assertDoesNotExist()
        rule.onNodeWithText("Main").assertExists()
        rule.onNodeWithTag(ACCOUNTS_NETWORK_HEADER_TAG + gnosis.chainId)
            .assert(hasText("Gnosis Chain · 1 account"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        rule.onNodeWithTag(ACCOUNTS_NETWORK_HEADER_TAG + gnosis.chainId).performClick()
        rule.onNodeWithText("Card").assertExists()
    }

    @Test
    fun aTapOnAPinnedHeaderFoldsItsNetworkAndNeverOpensTheRowUnderIt() {
        val many = (0 until 30).map { n ->
            main.copy(label = "Account $n", address = "0x" + n.toString(16).padStart(40, '0'))
        }
        show(ready(*many.toTypedArray()))

        // Scrolled until rows have passed under the header pinned on top.
        rule.onNodeWithTag(ACCOUNTS_LIST_TAG).performScrollToNode(hasText("Account 20"))
        rule.onNodeWithText("Account 0").assertDoesNotExist()
        rule.onNodeWithTag(ACCOUNTS_NETWORK_HEADER_TAG + ethereum.chainId).performClick()

        assertEquals(emptyList(), actions, "the tap is the header's: it folds, and opens no account")
        rule.onNodeWithText("Account 20").assertDoesNotExist()
        // A row's own tap still opens its account.
        rule.onNodeWithTag(ACCOUNTS_NETWORK_HEADER_TAG + ethereum.chainId).performClick()
        rule.onNodeWithTag(ACCOUNTS_LIST_TAG).performScrollToNode(hasText("Account 20"))
        rule.onNodeWithText("Account 20").performClick()
        assertEquals(1, actions.size)
    }

    @Test
    fun aFoldedNetworkKeepsTheAccountOpenBesideTheListAndAQuickSecondTapUnfoldsIt() {
        val spare = main.copy(label = "Spare", address = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")
        show(ready(main, spare), SelectedAccount(ethereum.chainId, "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"))
        val header = rule.onNodeWithTag(ACCOUNTS_NETWORK_HEADER_TAG + ethereum.chainId)

        header.performClick()
        rule.onNodeWithText("Main").assertExists()
        rule.onNodeWithText("Spare").assertDoesNotExist()
        header.assert(hasText("Ethereum · 1 account"))

        // Two taps before the screen draws again: unfolded, then folded again, never stored twice.
        rule.mainClock.autoAdvance = false
        header.performClick()
        header.performClick()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Spare").assertDoesNotExist()
        // So one more tap unfolds it.
        header.performClick()
        rule.onNodeWithText("Spare").assertExists()
    }

    @Test
    fun theAccountShownBesideTheListIsTheOneRowSelected() {
        // Its address in lower case, as a route carries it: the row's is EIP-55.
        show(
            ready(main, main.copy(label = "Spare", address = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")),
            SelectedAccount(ethereum.chainId, "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        )

        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        rule.onAllNodes(selected).assertCountEquals(1)
        rule.onNode(selected and hasAnyDescendant(hasText("Main"))).assertExists()
        rule.onNodeWithTag(ACCOUNTS_SELECTED_TAG).assertExists()
    }

    @Test
    fun noRowIsSelectedWhileNoAccountIsShownBesideTheList() {
        show(ready(main, main.copy(label = "Spare", address = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")))

        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(0)
        rule.onNodeWithTag(ACCOUNTS_SELECTED_TAG).assertDoesNotExist()
    }

    @Test
    fun anEmptyWalletSaysWhichNetworksAreStillSyncing() {
        val syncing = strip.copy(simpleText = "Syncing Ethereum · 1 of 4 periods")
        val synced = strip.copy(simpleText = null)
        show(
            AccountsUiState.Ready(
                emptyList(),
                lightClients = listOf(
                    NetworkLightClient(Network.ETHEREUM.chainId, "Ethereum", false, syncing),
                    NetworkLightClient(Network.GNOSIS.chainId, "Gnosis Chain", false, synced),
                ),
            ),
        )

        // A network still syncing says so before the first account (#66); a synced one draws no line.
        rule.onNodeWithTag(ACCOUNTS_EMPTY_LIGHT_CLIENT_TAG + Network.ETHEREUM.chainId)
            .assertTextEquals("Syncing Ethereum · 1 of 4 periods")
        rule.onNodeWithTag(ACCOUNTS_EMPTY_LIGHT_CLIENT_TAG + Network.GNOSIS.chainId).assertDoesNotExist()
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
    }

    @Test
    fun theEyeAtTheTopLeftSaysWhatItDoesAndAsksToToggle() {
        show(ready(main))
        rule.onNodeWithContentDescription(HIDE_AMOUNTS).assertIsDisplayed().performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun hiddenAmountsAreDrawnAsTheyComeAndTheEyeIsClosed() {
        val hidden = ready(main).withAmountsHidden(true) as AccountsUiState.Ready
        show(hidden)

        rule.onNodeWithContentDescription(SHOW_AMOUNTS).assertIsDisplayed()
        // The total and the account's total: two figures, both masked.
        rule.onAllNodesWithText("€$AMOUNT_MASK").assertCountEquals(2)
        rule.onNodeWithText("$AMOUNT_MASK ETH").assertIsDisplayed()
        rule.onNodeWithText("€12,025.55").assertDoesNotExist()
        rule.onNodeWithText("3.2041 ETH").assertDoesNotExist()
        // The name and the network stay.
        rule.onNodeWithText("Main").assertIsDisplayed()
    }

    @Test
    fun noEyeWhileThereIsNothingToHide() {
        show(AccountsUiState.Loading())
        rule.onNodeWithTag(HIDE_AMOUNTS_TAG).assertDoesNotExist()
    }

    @Test
    fun aRowsMenuAddsAnAccountFromTheSamePhrase() {
        show(ready(main))

        rule.onNodeWithText("Main").performTouchInput { longClick() }
        rule.onNodeWithText("New account from the same phrase…").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Derive(ethereum.chainId, main.address)), actions)
    }

    @Test
    fun theTotalSaysHowFreshItIsAndNoBlockNumberIsShown() {
        show(ready(main))

        rule.onNodeWithTag(ACCOUNTS_TOTAL_TAG).assertTextEquals("€12,025.55")
        rule.onNodeWithTag(ACCOUNTS_FRESHNESS_TAG).assertTextEquals("Updated 2 min ago · pull down to refresh")
        rule.onNodeWithText("Verified at block", substring = true).assertDoesNotExist()
        // The network named in text and by its icon: in its header and on the account's row.
        rule.onAllNodesWithText("Ethereum").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Ethereum").assertCountEquals(2)
    }

    @Test
    fun theRefreshButtonAsksForARefresh() {
        show(ready(main))

        rule.onNodeWithTag(ACCOUNTS_REFRESH_TAG).performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun aRowOpensItsAccount() {
        show(ready(main))

        rule.onNodeWithText("Main").performClick()
        assertEquals(listOf<AccountAction>(AccountAction.Open(main.chainId, main.address)), actions)
    }

    @Test
    fun aNetworkThatCannotBeCheckedIsSaidAndItsRowsAreDated() {
        show(
            ready(main.copy(balanceVerified = false, accountTotal = null, ageNote = "3 h ago · may have changed"))
                .copy(
                    unreachable = listOf(
                        UnreachableNetwork(ethereum.chainId, "Can't check Ethereum right now", "The app keeps trying."),
                    ),
                ),
        )

        rule.onNodeWithTag(ACCOUNTS_UNREACHABLE_TAG + ethereum.chainId).assertIsDisplayed()
        rule.onNodeWithText("Can't check Ethereum right now").assertIsDisplayed()
        rule.onNodeWithText("3 h ago · may have changed").assertIsDisplayed()
    }

    @Test
    fun aDamagedListSetAsideIsSaidAboveTheEmptyList() {
        show(AccountsUiState.Ready(emptyList(), notice = "Your saved account list was damaged and has been set aside."))

        rule.onNodeWithTag(ACCOUNTS_NOTICE_TAG).assertIsDisplayed()
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
    }

    @Test
    fun whatANetworkHasQueuedIsSaidAndALostOneUntilDismissed() {
        var dismissed = 0
        rule.setContent {
            SerotineTheme {
                SimpleAccountsScreen(
                    state = ready(main).copy(
                        queued = listOf("Ethereum: 1 waiting to send · Keep Punicus open to send it sooner"),
                        lostNotice = "A transaction waiting to be sent was lost.",
                    ),
                    onWatchAddress = {},
                    onImportPhrase = {},
                    onCreateAccount = {},
                    onCreateSafe = {},
                    onAccountAction = {},
                    onRefresh = {},
                    onDismissLost = { dismissed++ },
                )
            }
        }

        rule.onNodeWithTag(ACCOUNTS_QUEUED_TAG)
            .assertTextEquals("Ethereum: 1 waiting to send · Keep Punicus open to send it sooner")
        rule.onNodeWithText("A transaction waiting to be sent was lost.").assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
    }
}
