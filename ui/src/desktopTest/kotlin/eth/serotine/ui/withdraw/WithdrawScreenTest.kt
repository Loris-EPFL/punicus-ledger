package eth.serotine.ui.withdraw

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [WithdrawScreen] headless. */
class WithdrawScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private val ready = WithdrawUiState.Ready(
        node = "For Bee node, 0x5aAe…eAed",
        chequebook = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        lines = listOf("Free to withdraw 1.2 BZZ"),
        step = WithdrawStep.Compose(amount = "", all = "1.2"),
    )

    private fun show(state: WithdrawUiState) = rule.setContent {
        SerotineTheme {
            WithdrawScreen(
                state,
                onBack = { events += "back" },
                actions = WithdrawActions(
                    onAmount = { events += "amount $it" },
                    onReview = { events += "review" },
                    onPasswordChange = { events += "password" },
                    onSign = { events += "sign" },
                ),
            )
        }
    }

    @Test
    fun theFormTakesAnAmountOrAllThatIsFree() {
        show(ready)
        rule.onNodeWithTag(WITHDRAW_AMOUNT_TAG).performTextInput("1")
        rule.onNodeWithTag(WITHDRAW_ALL_TAG).performClick()
        rule.onNodeWithTag(WITHDRAW_REVIEW_TAG).performClick()
        // The state here is fixed, so the field may report its reset as well: left out.
        assertEquals(listOf("amount 1", "amount 1.2", "review"), events.filterNot { it == "amount " })
    }

    @Test
    fun theReviewWarnsOfUncashedChequesAndOfBee() {
        show(
            ready.copy(
                step = WithdrawStep.Review(
                    lines = listOf("Withdraws" to "1 BZZ"),
                    warnings = listOf(WithdrawTexts.CHEQUES_WARNING, WithdrawTexts.NODE_KEY_WARNING),
                    unlock = UnlockStep(),
                ),
            ),
        )
        rule.onNodeWithText(WithdrawTexts.CHEQUES_WARNING).assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithTag(WITHDRAW_WARNING_TAG).fetchSemanticsNodes().size)
        rule.onNodeWithTag(WITHDRAW_PASSWORD_TAG).performTextInput("pw")
        assertEquals(listOf("password"), events)
    }
}
