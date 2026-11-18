package eth.serotine.ui.safesettings

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.MODULE_ENABLED_REFUSAL
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.ShareItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.gnosispay.ActionItem
import eth.serotine.ui.gnosispay.MemberRow
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A Safe's settings in simple mode in Android Studio's preview pane, one preview per state it draws
// differently from Detailed view. Built from display-ready values, never from the ViewModel.
// SimpleSafeSettingsScreenPreviewsTest renders each one in simpleSafeSettingsScreenPreviews, at the
// end. The addresses are EIP-55's own examples; the names and blocks are made up.

private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val FRIEND = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"

private val safe = SendFrom("Treasury", TREASURY)

private val overview = SafeSettingsUiState.Overview(
    network = "Sepolia",
    safe = safe,
    title = "Safe 1.4.1 · nonce 12",
    signers = "2 of 3 owners must sign",
    owners = listOf(
        SettingsOwner(DAILY, "Daily", heldHere = true),
        SettingsOwner(SAVINGS, "Savings", heldHere = false),
        SettingsOwner(FRIEND, label = null, heldHere = false),
    ),
    modules = emptyList(),
    guard = null,
    checkedAt = "Verified at block 8,412,307",
    simpleCheckedAt = "Checked on chain",
    changeReason = null,
    enableModuleReason = MODULE_ENABLED_REFUSAL,
    updateReason = "Updating a Safe's version runs a migration contract's code as the Safe, a delegate call, which " +
        "Punicus does not sign. It comes once a migration contract is pinned here.",
)

private val review = SafeSettingsUiState.Review(
    network = "Sepolia",
    safe = safe,
    action = SafeTxActionItem(
        title = "Add an owner",
        addresses = listOf(ActionAddress("New owner", FRIEND)),
        notes = listOf("The threshold becomes 2"),
    ),
    nonce = "Safe nonce 12",
    simulation = "The change, run as a verified call from the Safe at block 8,412,307, goes through.",
    simpleSimulation = "Checked on chain: the change should go through. The Safe's own checks — its owners' " +
        "signatures and its nonce — and its guard run when its execution is quoted.",
    owners = listOf(AccountChoice("Daily", DAILY)),
    owner = DAILY,
    unlock = UnlockStep(),
)

private const val REVERTS = "The change reverts, run as a verified call from the Safe at block 8,412,307: the Safe " +
    "refuses it as it is now. Nothing was proposed."
private const val SIMPLE_REVERTS = "The change reverts, as checked on chain: the Safe refuses it as it is now. " +
    "Nothing was proposed."

private val refused = SafeSettingsUiState.Edit(
    network = "Sepolia",
    safe = safe,
    form = SettingsForm(
        kind = SettingsChangeKind.CHANGE_THRESHOLD,
        title = "Change the threshold",
        explanation = "How many of the Safe's owners must sign each of its transactions.",
        threshold = ThresholdField(1, 3, "1 of 3 owners must sign"),
        error = REVERTS,
        simpleError = SIMPLE_REVERTS,
        canReview = true,
    ),
)

@Composable
private fun SimpleSafeSettingsPreview(state: SafeSettingsUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SimpleSafeSettingsScreen(state = state, actions = SafeSettingsActions(), onBack = {})
    }
}

/** The settings: checked on chain, no block, no strip; the block behind Details. */
@Preview
@Composable
private fun SimpleSafeSettingsOverviewPreview() = SimpleSafeSettingsPreview(overview)

@Preview
@Composable
private fun SimpleSafeSettingsOverviewDarkPreview() = SimpleSafeSettingsPreview(overview, darkTheme = true)

/**
 * A Gnosis Pay account's settings (owner ruling, 2026-11-18): its Delay's members, checked on chain,
 * the Delay's block behind Details as the Safe's is.
 */
@Preview
@Composable
private fun SimpleSafeSettingsGnosisPayPreview() = SimpleSafeSettingsPreview(
    overview.copy(
        network = "Gnosis Chain",
        safe = SendFrom("Card", TREASURY),
        title = "Safe 1.3.0 · nonce 3",
        signers = "1 of 1 owners must sign",
        owners = listOf(SettingsOwner("0x0000000000000000000000000000000000000002", label = null, heldHere = false)),
        modules = listOf(SAVINGS),
        checkedAt = "Verified at block 43,000,512",
        changeReason = "No owner of this Safe has its key on this device: it is watch-only here, so no change of " +
            "its settings can be proposed from it. An owner's recovery phrase, imported, makes it possible.",
        delayMembers = DelayMembers(
            members = listOf(
                MemberRow(DAILY, "Daily", heldHere = true, remove = ActionItem(true, null)),
                MemberRow(FRIEND, null, false, "A Safe: 1 of its 9 owners signs", ActionItem(true, null)),
            ),
            add = ActionItem(true, null),
            checkedAt = "Verified by the light client at block 43,000,512",
            simpleCheckedAt = "Checked on chain",
            note = "This is a Gnosis Pay account. Its one owner is a placeholder no key holds: the members of its " +
                "Delay module $SAVINGS control it. A member added or removed is queued through the Delay and " +
                "takes effect once executed after its wait, like a transfer.",
        ),
    ),
)

/** A change's review: its run should go through, checked on chain. */
@Preview
@Composable
private fun SimpleSafeSettingsReviewPreview() = SimpleSafeSettingsPreview(review)

/** A change the Safe reverts on, refused on the form: checked on chain, its block behind Details. */
@Preview
@Composable
private fun SimpleSafeSettingsRefusedPreview() = SimpleSafeSettingsPreview(refused)

/** A signature the queue could not keep, and the change reverting when proposed again: no block. */
@Preview
@Composable
private fun SimpleSafeSettingsReviewRefusedPreview() = SimpleSafeSettingsPreview(
    review.copy(
        simpleSimulation = "When it was proposed, it was checked on chain: the change was to go through then. " +
            "The Safe's own checks — its owners' signatures and its nonce — and its guard run when its execution " +
            "is quoted.",
        error = REVERTS,
        simpleError = SIMPLE_REVERTS,
        share = ShareItem(
            text = "{\"v\":1,\"chainId\":11155111,\"safe\":\"0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb\",\"tx\":{…}}",
            qr = true,
            note = "The raw transaction and 1 signature: the other device decodes it and checks it against the chain " +
                "itself.",
        ),
    ),
)

/** Every preview above, which SimpleSafeSettingsScreenPreviewsTest renders: a new preview goes in here too. */
internal val simpleSafeSettingsScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("overview") { SimpleSafeSettingsOverviewPreview() },
    ScreenPreview("overview-dark") { SimpleSafeSettingsOverviewDarkPreview() },
    ScreenPreview("gnosis-pay") { SimpleSafeSettingsGnosisPayPreview() },
    ScreenPreview("refused") { SimpleSafeSettingsRefusedPreview() },
    ScreenPreview("review") { SimpleSafeSettingsReviewPreview() },
    ScreenPreview("review-refused") { SimpleSafeSettingsReviewRefusedPreview() },
)
