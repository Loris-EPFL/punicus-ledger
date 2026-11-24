package eth.serotine.ui.postage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [PostageScreen] headless. */
class PostageScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private val payer = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"

    private val form = PostageStep.Compose(
        payers = listOf(PayerItem(payer, "Savings")),
        payer = payer,
        signer = null,
        depths = listOf(DepthItem(20, "Depth 20 · 687.62 MB of 4.29 GB theoretical"), DepthItem(21, "Depth 21")),
        depth = 20,
        days = "",
        daysLabel = PostageTexts.TIME_TO_LIVE,
        immutable = false,
        explains = PostageTexts.explains(PostageAction.BUY),
    )

    private val ready = PostageUiState.Ready("Buy a postage batch", "For Bee node, 0x5aAe…eAed", emptyList(), form)

    private fun show(state: PostageUiState) = rule.setContent {
        SerotineTheme {
            PostageScreen(
                state,
                onBack = { events += "back" },
                actions = PostageActions(
                    onPayer = { events += "payer $it" },
                    onDepth = { events += "depth $it" },
                    onDays = { events += "days $it" },
                    onImmutable = { events += "immutable $it" },
                    onReview = { events += "review" },
                    onPasswordChange = { events += "password" },
                    onSign = { events += "sign" },
                    onOpenPending = { events += "open $it" },
                ),
            )
        }
    }

    @Test
    fun theFormCallsItsIntents() {
        show(ready)
        rule.onNodeWithText("Depth 20 · 687.62 MB of 4.29 GB theoretical").assertIsDisplayed()
        rule.onNodeWithTag("$POSTAGE_DEPTH_TAG-21").performClick()
        rule.onNodeWithTag("$POSTAGE_PAYER_TAG-$payer").performClick()
        rule.onNodeWithTag(POSTAGE_DAYS_TAG).performTextInput("30")
        rule.onNodeWithTag(POSTAGE_IMMUTABLE_TAG).performClick()
        rule.onNodeWithTag(POSTAGE_REVIEW_TAG).performClick()
        // The state here is fixed, so the field may report its reset as well: left out.
        assertEquals(
            listOf("depth 21", "payer $payer", "days 30", "immutable true", "review"),
            events.filterNot { it == "days " },
        )
    }

    @Test
    fun aReviewShowsItsStepItsWarningsAndSigns() {
        show(
            ready.copy(
                step = PostageStep.Review(
                    title = PostageTexts.APPROVAL_TITLE,
                    position = PostageTexts.position(1),
                    lines = listOf("Fee" to "0.0001 xDAI at most, normal fees"),
                    warnings = listOf(PostageTexts.NODE_KEY_WARNING),
                    unlock = UnlockStep(),
                ),
            ),
        )
        rule.onNodeWithText(PostageTexts.position(1)).assertIsDisplayed()
        rule.onNodeWithText(PostageTexts.NODE_KEY_WARNING).assertIsDisplayed()
        rule.onNodeWithTag(POSTAGE_PASSWORD_TAG).performTextInput("pw")
        assertEquals(listOf("password"), events)
    }

    @Test
    fun theWaitOffersTheApprovalsStatus() {
        val hash = "0x" + "ab".repeat(32)
        show(ready.copy(step = PostageStep.Awaiting(hash, PostageTexts.AWAITING)))
        rule.onNodeWithText(PostageTexts.AWAITING).assertIsDisplayed()
        rule.onNodeWithTag(POSTAGE_PENDING_TAG).performClick()
        assertEquals(listOf("open $hash"), events)
    }
}
