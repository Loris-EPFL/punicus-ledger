package eth.serotine.ui.accounts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Renders the stateless [NoAccountOpenScreen] headless, and checks what it says by the list's state. */
class NoAccountOpenScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val daily = AccountItem(
        "Daily",
        "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        1L,
        AccountKind.EOA,
        Control.FULL_CONTROL,
        "1.25 ETH",
        balanceVerified = true,
    )

    @Test
    fun itSaysWhatItShowsAndHowToOpenAnAccount() {
        rule.setContent { SerotineTheme { NoAccountOpenScreen(NO_ACCOUNT_OPEN_HINT) } }

        rule.onNodeWithText("No account open").assertIsDisplayed()
        rule.onNodeWithText(NO_ACCOUNT_OPEN_HINT).assertIsDisplayed()
    }

    @Test
    fun withoutAHintItShowsItsTitleAlone() {
        rule.setContent { SerotineTheme { NoAccountOpenScreen(hint = null) } }

        rule.onNodeWithText("No account open").assertIsDisplayed()
        rule.onNodeWithText(NO_ACCOUNT_OPEN_HINT).assertDoesNotExist()
        rule.onNodeWithText(NO_ACCOUNT_YET_HINT).assertDoesNotExist()
    }

    @Test
    fun theHintFollowsWhatTheListHolds() {
        assertEquals(NO_ACCOUNT_YET_HINT, noAccountOpenHint(AccountsUiState.Ready(emptyList())))
        val section = NetworkSection(
            1L,
            "Ethereum",
            testnet = false,
            on = true,
            LightClientItem("Verified", null, false, VERIFIED),
            listOf(daily),
        )
        assertEquals(NO_ACCOUNT_OPEN_HINT, noAccountOpenHint(AccountsUiState.Ready(listOf(section))))
        // Nothing to choose while the list is read, or cannot be.
        assertNull(noAccountOpenHint(AccountsUiState.Loading()))
        assertNull(noAccountOpenHint(AccountsUiState.ListUnavailable("The account list cannot be read.")))
    }
}
