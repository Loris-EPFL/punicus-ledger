package eth.serotine.ui.gnosispay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        waits = "A transfer waits 25 h in the Delay module, then can be executed for 31 h.",
        checkedAt = "Verified by the light client at block 1",
        members = listOf(MemberRow("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", "Card owner", heldHere = true)),
        send = ActionItem(enabled = true, reason = null),
        queued = listOf(
            QueuedRow("0xabc", "1 XDAI to 0x1111…1111", "Ready to execute", ActionItem(enabled = true, reason = null)),
        ),
        queuedNote = null,
        step = GnosisPayStep.Overview,
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
}
