package eth.serotine.ui.dappproposal

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders the stateless [DappProposalScreen] headless. */
class DappProposalScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val toggled = mutableListOf<String>()
    private var approves = 0
    private var rejects = 0
    private var dismissed = 0
    private val passwords = mutableListOf<String>()
    private var phraseChanges = 0
    private val picked = mutableListOf<String?>()

    private fun show(state: DappProposalUiState) = rule.setContent {
        SerotineTheme {
            DappProposalScreen(
                state,
                onToggle = { toggled += it },
                onApprove = { approves++ },
                onReject = { rejects++ },
                onDismissExplanation = { dismissed++ },
                onBack = {},
                onPasswordChange = { passwords += it },
                onChoosePhrase = { phraseChanges++ },
                onPickPhrase = { picked += it },
                aliasActions = AliasActions(
                    onChoose = { aliasEvents += "choose" },
                    onPick = { aliasEvents += "pick $it" },
                    onAnswer = { aliasEvents += "answer $it" },
                    onHostChange = { aliasEvents += "host $it" },
                    onType = { aliasEvents += "type" },
                ),
            )
        }
    }

    private val aliasEvents = mutableListOf<String>()

    @Test
    fun aSiteWithNoAddressOffersAnotherSitesListsThemAndAsksFirst() {
        show(
            state.copy(
                aliasOffer = AliasOffer("No address for uniswap.app yet", null),
                aliasChoices = listOf(
                    AliasChoice("0x6", "uniswap.org", "Also seen by uniswap.exchange", "0x9fa6…8D52"),
                ),
            ),
        )
        rule.onNodeWithText("No address for uniswap.app yet").assertExists()
        rule.onNodeWithTag(DAPP_PROPOSAL_ALIAS_CHOICE_TAG + "0x6").performClick()
        rule.onNodeWithText("Also seen by uniswap.exchange").assertExists()
        assertEquals(listOf("pick 0x6"), aliasEvents)
    }

    @Test
    fun anOldSitesHostIsTypedInTheList() {
        show(state.copy(aliasChoices = emptyList(), aliasHost = "app.uniswap.org"))
        rule.onNodeWithTag(DAPP_PROPOSAL_ALIAS_HOST_TAG).performTextInput("x")
        rule.onNodeWithTag(DAPP_PROPOSAL_ALIAS_TYPE_TAG).performClick()
        assertEquals("type", aliasEvents.last())
        assertTrue(aliasEvents.any { it.startsWith("host ") })
    }

    @Test
    fun anAddressMadeAgainAsksForThePasswordInTheQuestion() {
        val question = AliasQuestion("Use uniswap.org's address?", "Made again.", needsPassword = true)
        show(state.copy(confirmingAlias = question))
        rule.onNodeWithTag(DAPP_PROPOSAL_ALIAS_PASSWORD_TAG).performTextInput("secret")
        assertEquals(listOf("secret"), passwords)
    }

    @Test
    fun theQuestionIsAnsweredWithUseIt() {
        show(state.copy(confirmingAlias = AliasQuestion("Use uniswap.org's address?", "The two sites can link.")))
        rule.onNodeWithText("The two sites can link.").assertExists()
        rule.onNodeWithTag(DAPP_PROPOSAL_ALIAS_YES_TAG).performClick()
        assertEquals(listOf("answer true"), aliasEvents)
    }

    @Test
    fun usingAnotherSitesAddressIsGreyedOutWhereThereIsNoneAndStillAsks() {
        show(state.copy(aliasOffer = AliasOffer("No address for uniswap.app yet", DappProposalViewModel.NO_OTHER_SITE)))
        rule.onNodeWithTag(DAPP_PROPOSAL_ALIAS_TAG).performScrollTo().assert(unavailable).performClick()
        assertEquals(listOf("choose"), aliasEvents)
    }

    private val unavailable = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable")

    private val state = DappProposalUiState(
        loading = false,
        name = "A dApp",
        url = "https://a.test",
        accounts = listOf(
            AccountOption("11155111:0x1", "Main", "0x0000…0001", chosen = false, unavailable = null),
            AccountOption("1:0x1", "Savings", "0x0000…0001", chosen = false, unavailable = "This dApp did not ask for Ethereum."),
        ),
    )

    @Test
    fun theDappsClaimIsMarkedAsSuch() {
        show(state)

        rule.onNodeWithText("Stated by the dApp, not verified: Punicus cannot check who is asking.").assertExists()
    }

    @Test
    fun aGreyedOutAccountAndAGreyedOutApproveStillAnswerATap() {
        show(state)

        rule.onNodeWithTag(DAPP_PROPOSAL_ACCOUNT_TAG + "1:0x1").performScrollTo().assert(unavailable).performClick()
        assertEquals(listOf("1:0x1"), toggled)
        rule.onNodeWithText("Approve").performScrollTo().assert(unavailable).performClick()
        assertEquals(1, approves)
        rule.onNodeWithText("Reject").performClick()
        assertEquals(1, rejects)
    }

    @Test
    fun anExplanationIsShownAndDismissed() {
        show(state.copy(explaining = Explanation("Not offered to this dApp", "This dApp did not ask for Ethereum.")))

        rule.onNodeWithText("Not offered to this dApp").assertExists()
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
    }

    private val newAddress = NewAddressOption(
        title = "New address for uniswap.org",
        where = "app.uniswap.org, site uniswap.org · On Ethereum",
        chosen = true,
        unavailable = null,
        phrase = "From the recovery phrase of Main",
        changeable = true,
    )

    @Test
    fun aGreyedOutNewAddressStillAnswersATapAndAsksNoPassword() {
        show(state.copy(newAddress = newAddress.copy(chosen = false, unavailable = "No recovery phrase here.")))

        rule.onNodeWithTag(DAPP_PROPOSAL_PASSWORD_TAG).assertDoesNotExist()
        rule.onNodeWithTag(DAPP_PROPOSAL_NEW_ADDRESS_TAG).performScrollTo().assert(unavailable).performClick()
        assertEquals(listOf(DappProposalViewModel.NEW_KEY), toggled)
    }

    @Test
    fun theNewAddressTickedAsksForThePassword() {
        show(state.copy(newAddress = newAddress))

        rule.onNodeWithTag(DAPP_PROPOSAL_PASSWORD_TAG).performScrollTo().performTextInput("pw")
        assertEquals(listOf("pw"), passwords)
        rule.onNodeWithText("Change").performClick()
        assertEquals(1, phraseChanges)
    }

    @Test
    fun aPhraseIsPickedOrTheListCancelled() {
        val phrases = listOf(
            PhraseOption("1:0x1", "Main", "0x0000…0001", chosen = true),
            PhraseOption("1:0x3", "Imported", "0x0000…0003", chosen = false),
        )
        show(state.copy(newAddress = newAddress, phrases = phrases))

        rule.onNodeWithText("Imported").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("1:0x3", null), picked)
        assertEquals(0, phraseChanges)
    }
}
