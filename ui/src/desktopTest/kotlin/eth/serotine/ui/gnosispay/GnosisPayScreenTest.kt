package eth.serotine.ui.gnosispay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [GnosisPayScreen] headless. */
class GnosisPayScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private val ready = GnosisPayUiState.Ready(
        label = "Card",
        address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        delay = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        waits = "A transfer waits 3 min in the Delay module, then can be executed for 30 min.",
        checkedAt = "Verified by the light client at block 1",
        members = listOf(MemberRow("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", "Card owner", heldHere = true)),
        send = ActionItem(enabled = true, reason = null),
        queued = listOf(
            QueuedRow("0xabc", "1 XDAI to 0x1111…1111", "Ready to execute", ActionItem(enabled = true, null)),
        ),
        queuedNote = null,
        step = GnosisPayStep.Overview(),
    )

    private fun show(state: GnosisPayUiState) = rule.setContent {
        SerotineTheme {
            GnosisPayScreen(
                state,
                onBack = { events += "back" },
                actions = GnosisPayActions(onSend = { events += "send" }, onExecute = { events += "execute $it" }),
            )
        }
    }

    @Test
    fun sendAndExecuteAreOfferedWhenTheyCanBeDone() {
        show(ready)

        rule.onNodeWithText(ready.waits).assertIsDisplayed()
        rule.onNodeWithTag(GNOSIS_PAY_SEND_TAG).performClick()
        rule.onNodeWithTag("$GNOSIS_PAY_EXECUTE_TAG-0xabc").performClick()

        assertEquals(listOf("send", "execute 0xabc"), events)
    }

    @Test
    fun aGreyedOutSendSaysWhatIsMissing() {
        val reason = "The key of the account that controls this Gnosis Pay account is not on this device."
        show(ready.copy(send = ActionItem(enabled = false, reason = reason), queued = emptyList()))

        rule.onNodeWithTag(GNOSIS_PAY_SEND_TAG).performClick()

        rule.onNodeWithText(reason).assertIsDisplayed()
        assertEquals(emptyList(), events)
    }

    @Test
    fun theAddFormSaysWhatAMemberCanDoAndReviewsTheAddressTyped() {
        val explains = "A member can move all of this account's funds, without this device."
        rule.setContent {
            SerotineTheme {
                GnosisPayScreen(
                    ready.copy(step = GnosisPayStep.AddMember(member = "", explains = explains, sender = "Card owner")),
                    onBack = {},
                    actions = GnosisPayActions(
                        onMemberChange = { events += "member $it" },
                        onReviewMember = { events += "review" },
                    ),
                )
            }
        }

        rule.onNodeWithText(explains).assertIsDisplayed()
        rule.onNodeWithTag(GNOSIS_PAY_MEMBER_TAG).performTextInput("0x30953638C67022f6d0F72a5aD86E1241C611Cf45")
        rule.onNodeWithTag(GNOSIS_PAY_MEMBER_REVIEW_TAG).performClick()

        // The field is the state's: typed, then drawn again from the unchanged state, it reports each edit.
        assertEquals("member 0x30953638C67022f6d0F72a5aD86E1241C611Cf45", events.first())
        assertEquals("review", events.last())
    }

    @Test
    fun aMemberChangesReviewShowsItsWarning() {
        val warning = "This member is how this device controls the account."
        show(
            ready.copy(
                step = GnosisPayStep.Review(
                    title = "Queue: remove member 0xdbF03B…C6FB",
                    lines = listOf("Remove member" to "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"),
                    fee = FeeLevel.NORMAL,
                    unlock = UnlockStep(),
                    warning = warning,
                ),
            ),
        )

        rule.onNodeWithTag(GNOSIS_PAY_WARNING_TAG).assertIsDisplayed()
        rule.onNodeWithText(warning).assertIsDisplayed()
    }
}
