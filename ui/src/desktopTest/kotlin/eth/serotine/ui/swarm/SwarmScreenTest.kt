package eth.serotine.ui.swarm

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SwarmScreen] headless. */
class SwarmScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private val ready = SwarmUiState.Ready(
        label = "Bee node",
        address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        chequebooks = listOf(
            ChequebookRow(
                "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                listOf("Balance 1.2 BZZ"),
                SwarmTexts.BOUNCED,
                "Verified at block 1",
            ),
        ),
        chequebookNote = null,
        batches = listOf(
            BatchRow(
                "0x" + "ab".repeat(32),
                "0xabababab…abab",
                "Depth 22 · 7.73 GB of 17.18 GB theoretical",
                "About 41 days left at the current price",
                immutable = true,
                status = "Verified at block 1",
                topUp = SwarmAction(),
                dilute = SwarmAction(SwarmTexts.NODE_KEY_NOT_HELD),
            ),
        ),
        batchNote = null,
    )

    private fun show(state: SwarmUiState) = rule.setContent {
        SerotineTheme {
            SwarmScreen(
                state,
                onBack = { events += "back" },
                actions = SwarmActions(
                    onStartAdd = { events += "add $it" },
                    onAddText = { events += "text $it" },
                    onConfirmAdd = { events += "confirm" },
                    onRemoveChequebook = { events += "remove chequebook $it" },
                    onRemoveBatch = { events += "remove batch $it" },
                    onCopy = { events += "copy $it" },
                    onTurnOn = { events += "turn on" },
                    onBuy = { events += "buy" },
                    onTopUp = { events += "top up $it" },
                    onDilute = { events += "dilute $it" },
                ),
            )
        }
    }

    @Test
    fun theChequebookAndTheBatchesAreShownWithWhatTheyHold() {
        show(ready)
        rule.onNodeWithText("Balance 1.2 BZZ").assertIsDisplayed()
        rule.onNodeWithText(SwarmTexts.BOUNCED).assertIsDisplayed()
        rule.onNodeWithText("Depth 22 · 7.73 GB of 17.18 GB theoretical").assertIsDisplayed()
        rule.onNodeWithText("Immutable").assertIsDisplayed()
        rule.onNodeWithText("About 41 days left at the current price").assertIsDisplayed()
    }

    @Test
    fun removeAndAddCallTheirIntents() {
        show(ready)
        rule.onAllNodesWithText("Remove")[0].performClick()
        rule.onAllNodesWithText("Remove")[1].performClick()
        rule.onNodeWithText("Add chequebook").performClick()
        rule.onNodeWithText("Add a batch by id").performClick()
        rule.onNodeWithText("Copy id").performClick()
        assertEquals(
            listOf(
                "remove chequebook 0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                "remove batch 0x" + "ab".repeat(32),
                "add CHEQUEBOOK",
                "add BATCH",
                "copy 0x" + "ab".repeat(32),
            ),
            events,
        )
    }

    @Test
    fun theFormTakesTheTextAndSaysWhyItWasNotKept() {
        show(ready.copy(adding = AddForm(AddKind.BATCH, error = SwarmTexts.NOT_A_BATCH_ID)))
        rule.onNodeWithText(SwarmTexts.NOT_A_BATCH_ID).assertIsDisplayed()
        rule.onNodeWithTag(SWARM_ADD_FIELD_TAG).performTextInput("0x12")
        rule.onNodeWithTag(SWARM_ADD_CONFIRM_TAG).performClick()
        // The state here is fixed, so the field may report its reset as well: what was typed, then the confirm.
        assertEquals("text 0x12", events.first())
        assertEquals("confirm", events.last())
    }

    @Test
    fun aGreyedOutAddSaysWhyAndOffersTurnOn() {
        show(ready.copy(addReason = SwarmTexts.NETWORK_OFF))
        rule.onNodeWithText("Add chequebook").performClick()
        rule.onNodeWithText(SwarmTexts.NETWORK_OFF).assertIsDisplayed()
        rule.onNodeWithText("Turn on Gnosis Chain").performClick()
        assertEquals(listOf("turn on"), events, "the form does not open")
    }

    @Test
    fun noneFoundSaysHowToAddOne() {
        show(ready.copy(chequebooks = emptyList(), chequebookNote = SwarmTexts.NO_CHEQUEBOOK))
        rule.onNodeWithText(SwarmTexts.NO_CHEQUEBOOK).assertIsDisplayed()
    }

    @Test
    fun aBatchActionOpensItsFormOrSaysWhatIsMissing() {
        show(ready)
        rule.onNodeWithTag(SWARM_BUY_TAG).performClick()
        rule.onNodeWithTag("$SWARM_TOP_UP_TAG-0x" + "ab".repeat(32)).performClick()
        assertEquals(listOf("buy", "top up 0x" + "ab".repeat(32)), events)
        // Greyed out: a tap says why, and opens nothing.
        rule.onNodeWithTag("$SWARM_DILUTE_TAG-0x" + "ab".repeat(32)).performClick()
        rule.onNodeWithText(SwarmTexts.NODE_KEY_NOT_HELD).assertIsDisplayed()
        assertEquals(2, events.size)
    }
}
