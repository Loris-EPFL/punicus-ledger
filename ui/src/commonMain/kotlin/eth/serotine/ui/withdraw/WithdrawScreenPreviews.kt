package eth.serotine.ui.withdraw

import androidx.compose.runtime.Composable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The withdrawal screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. WithdrawScreenPreviewsTest renders each one in withdrawScreenPreviews, at the end.
// The addresses are made up (EIP-55's own examples among them).

private const val NODE = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val CHEQUEBOOK = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"

private val form = WithdrawStep.Compose(amount = "", all = "1.2")

private val ready = WithdrawUiState.Ready(
    node = "For Bee node, 0x5aAe…eAed",
    chequebook = CHEQUEBOOK,
    lines = listOf("Balance 1.5 BZZ", "Free to withdraw 1.2 BZZ", "Verified at block 43,000,512"),
    step = form,
)

private val review = WithdrawStep.Review(
    lines = listOf(
        "Withdraws" to "1 BZZ",
        "To" to "Bee node, $NODE: the node's own address, always",
        "Chequebook" to CHEQUEBOOK,
        "Free to withdraw" to "1.2 BZZ",
        "Fee" to "0.00009 xDAI at most, normal fees",
        "Read at" to "Chequebook at block 43,000,520, fees at block 43,000,520",
    ),
    warnings = listOf(WithdrawTexts.CHEQUES_WARNING, WithdrawTexts.NODE_KEY_WARNING),
    unlock = UnlockStep(biometrics = true),
)

@Composable
private fun WithdrawPreview(state: WithdrawUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        WithdrawScreen(state = state, onBack = {}, actions = WithdrawActions())
    }
}

@Preview
@Composable
private fun WithdrawFormPreview() = WithdrawPreview(ready)

@Preview
@Composable
private fun WithdrawFormDarkPreview() = WithdrawPreview(ready, darkTheme = true)

/** More than is free: the form says how much is. */
@Preview
@Composable
private fun WithdrawRefusedPreview() = WithdrawPreview(
    ready.copy(step = form.copy(amount = "2", error = "At most 1.2 BZZ is free to withdraw now.")),
)

@Preview
@Composable
private fun WithdrawQuotingPreview() = WithdrawPreview(ready.copy(step = form.copy(amount = "1", quoting = true)))

@Preview
@Composable
private fun WithdrawReviewPreview() = WithdrawPreview(ready.copy(step = review))

@Preview
@Composable
private fun WithdrawReviewLedgerPreview() =
    WithdrawPreview(ready.copy(step = review.copy(unlock = UnlockStep(ledger = true))))

/** The chequebook changed since the quote: made again, saying so. */
@Preview
@Composable
private fun WithdrawRequotedPreview() = WithdrawPreview(ready.copy(step = review.copy(note = WithdrawTexts.CHANGED)))

/** Nothing was sent: why, above the unlock. */
@Preview
@Composable
private fun WithdrawNotSentPreview() = WithdrawPreview(
    ready.copy(
        step = review.copy(
            error = "Nothing was signed: whether another transaction took this one's place cannot be verified " +
                "right now. Waiting for the light client to sync…",
        ),
    ),
)

/** A password that did not open the key: said under the field. */
@Preview
@Composable
private fun WithdrawWrongPasswordPreview() = WithdrawPreview(
    ready.copy(
        step = review.copy(unlock = review.unlock.copy(error = "That is not the wallet password. Nothing was signed.")),
    ),
)

/** Quoted again at the tap: the button says it checks on chain. */
@Preview
@Composable
private fun WithdrawCheckingPreview() = WithdrawPreview(ready.copy(step = review.copy(signing = true, checking = true)))

@Preview
@Composable
private fun WithdrawSigningPreview() = WithdrawPreview(ready.copy(step = review.copy(signing = true)))

/** The chequebook not verified now: nothing offered to fill in, the card saying why. */
@Preview
@Composable
private fun WithdrawNotVerifiedPreview() = WithdrawPreview(
    ready.copy(lines = listOf("Waiting for the light client to sync…"), step = form.copy(all = null)),
)

@Preview
@Composable
private fun WithdrawLoadingPreview() = WithdrawPreview(WithdrawUiState.Loading)

@Preview
@Composable
private fun WithdrawNoKeyPreview() = WithdrawPreview(WithdrawUiState.Unavailable(WithdrawTexts.NODE_KEY_NOT_HELD))

/** Every preview above, with the state it shows: what WithdrawScreenPreviewsTest renders. */
internal val withdrawScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("form") { WithdrawFormPreview() },
    ScreenPreview("form-dark") { WithdrawFormDarkPreview() },
    ScreenPreview("refused") { WithdrawRefusedPreview() },
    ScreenPreview("quoting") { WithdrawQuotingPreview() },
    ScreenPreview("review") { WithdrawReviewPreview() },
    ScreenPreview("review-ledger") { WithdrawReviewLedgerPreview() },
    ScreenPreview("requoted") { WithdrawRequotedPreview() },
    ScreenPreview("not-sent") { WithdrawNotSentPreview() },
    ScreenPreview("wrong-password") { WithdrawWrongPasswordPreview() },
    ScreenPreview("checking") { WithdrawCheckingPreview() },
    ScreenPreview("signing") { WithdrawSigningPreview() },
    ScreenPreview("not-verified") { WithdrawNotVerifiedPreview() },
    ScreenPreview("loading") { WithdrawLoadingPreview() },
    ScreenPreview("no-key") { WithdrawNoKeyPreview() },
)
