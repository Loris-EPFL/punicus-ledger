package eth.serotine.ui.perapp

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.UNAVAILABLE_REASON_TAG
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [PerAppScreen] (screen D9) headless. */
class PerAppScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<Pair<Long, String>>()
    private val copied = mutableListOf<String>()
    private val hosts = mutableListOf<String>()
    private val passwords = mutableListOf<String>()
    private var restores = 0
    private var confirmed = 0
    private var dismissed = 0

    private val row = PerAppRow(
        site = "uniswap.org",
        address = "0x9fA6c0b2E14d7A35c8e01B2f6D3a9c47b1e08D52",
        networks = "Ethereum, Gnosis Chain",
        phrase = "Phrase of Main",
        path = "m/44'/60'/1'/2'/3'",
        chainId = 1,
        hex = "0x9fa6c0b2e14d7a35c8e01b2f6d3a9c47b1e08d52",
    )

    private fun show(state: PerAppUiState) = rule.setContent {
        SerotineTheme {
            PerAppScreen(
                state,
                onBack = {},
                onOpen = { chain, address -> opened += chain to address },
                onCopy = { copied += it },
                onRestore = { restores++ },
                onCloseRestore = {},
                onHostChange = { hosts += it },
                onPhraseChange = {},
                onPasswordChange = { passwords += it },
                onConfirmRestore = { confirmed++ },
                onDismissExplanation = { dismissed++ },
            )
        }
    }

    @Test
    fun aRowShowsItsSitePathAndPhraseOpensItsAccountAndCopiesItsAddress() {
        show(PerAppUiState(loading = false, rows = listOf(row)))
        rule.onNodeWithText("Ethereum, Gnosis Chain · Phrase of Main").assertExists()
        rule.onNodeWithText("m/44'/60'/1'/2'/3'").assertExists()
        rule.onNodeWithTag(PER_APP_ROW_TAG + row.hex).performClick()
        assertEquals(listOf(1L to row.hex), opened)
        rule.onNodeWithContentDescription("Copy address").performClick()
        assertEquals(listOf(row.address), copied)
    }

    @Test
    fun aGreyedOutRestoreIsShownAndATapStillAsks() {
        show(PerAppUiState(loading = false, empty = PerAppViewModel.EMPTY, restoreBlocked = PerAppViewModel.NO_PHRASE))
        rule.onNodeWithTag(PER_APP_RESTORE_TAG).performScrollTo().performClick()
        assertEquals(1, restores)
    }

    @Test
    fun theExplanationSaysWhatIsMissing() {
        show(
            PerAppUiState(
                loading = false,
                restoreBlocked = PerAppViewModel.NO_PHRASE,
                explaining = PerAppViewModel.NO_PHRASE,
            ),
        )
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertExists()
    }

    @Test
    fun theFormTakesAHostAndThePasswordAndRestoresOnlyWithBoth() {
        show(PerAppUiState(loading = false, restore = RestoreForm()))
        rule.onNodeWithTag(PER_APP_CONFIRM_TAG).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(PER_APP_HOST_TAG).performScrollTo().performTextInput("uniswap.org")
        rule.onNodeWithTag(PER_APP_PASSWORD_TAG).performScrollTo().performTextInput("secret")
        // The state is fixed here, so a field may hand its unchanged value back after the text typed: the first counts.
        assertEquals("uniswap.org", hosts.first())
        assertEquals("secret", passwords.first())
    }

    @Test
    fun aFilledFormRestores() {
        show(
            PerAppUiState(
                loading = false,
                restore = RestoreForm(host = "uniswap.org", password = WalletPassword("secret")),
            ),
        )
        rule.onNodeWithTag(PER_APP_CONFIRM_TAG).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }
}
