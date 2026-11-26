package eth.serotine.ui.postage

import androidx.compose.runtime.Composable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.swarm.SwarmTexts
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The postage screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. PostageScreenPreviewsTest renders each one in postageScreenPreviews, at the end.
// The addresses and ids are made up (EIP-55's own examples among them).

private const val NODE = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val PAYER = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val STAMP = "0x45a1502382541Cd610CC9068e88727426b696293"
private const val ID = "0xa1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4"

private val payers = listOf(PayerItem(NODE, "Bee node"), PayerItem(PAYER, "Savings"))

private val buyForm = PostageStep.Compose(
    payers = payers,
    payer = PAYER,
    signer = null,
    depths = (17..30).map { DepthItem(it, SwarmTexts.capacity(it)) },
    depth = 20,
    days = "30",
    daysLabel = PostageTexts.TIME_TO_LIVE,
    immutable = false,
    explains = PostageTexts.explains(PostageAction.BUY),
)

private val buy = PostageUiState.Ready(
    title = "Buy a postage batch",
    node = "For Bee node, 0x5aAe…eAed",
    batch = emptyList(),
    step = buyForm,
)

private val batchLines = listOf(
    "Depth 22 · 7.73 GB of 17.18 GB theoretical",
    "About 41 days left at the current price",
    "Verified at block 43,000,512",
)

private val topUp = buy.copy(
    title = "Top up batch 0xa1b2c3d4…c3d4",
    batch = batchLines,
    step = buyForm.copy(
        depths = emptyList(),
        depth = null,
        daysLabel = PostageTexts.TIME_TO_ADD,
        immutable = null,
        explains = PostageTexts.explains(PostageAction.TOP_UP),
    ),
)

private val dilute = buy.copy(
    title = "Dilute batch 0xa1b2c3d4…c3d4",
    batch = batchLines,
    step = buyForm.copy(
        payers = emptyList(),
        payer = null,
        signer = PostageTexts.NODE_SIGNS,
        depths = (23..41).map { DepthItem(it, SwarmTexts.capacity(it)) },
        depth = 23,
        days = null,
        daysLabel = null,
        immutable = null,
        explains = PostageTexts.explains(PostageAction.DILUTE),
    ),
)

private val approval = PostageStep.Review(
    title = PostageTexts.APPROVAL_TITLE,
    position = PostageTexts.position(1),
    lines = listOf(
        "Lets the postage contract take" to "0.2165 BZZ, exactly what the batch costs; never more",
        "Postage contract" to STAMP,
        "From" to "Savings, $PAYER",
        "Fee" to "0.000052 xDAI at most, normal fees",
        "Read at" to "Fees at block 43,000,512",
        "Then" to PostageTexts.APPROVAL_NOTE,
    ),
    warnings = emptyList(),
    unlock = UnlockStep(biometrics = true),
)

private val purchase = PostageStep.Review(
    title = "Buy a postage batch",
    position = PostageTexts.position(2),
    lines = listOf(
        "Owned by" to "Bee node, $NODE",
        "Paid by" to "Savings, $PAYER",
        "Holds" to SwarmTexts.capacity(20),
        "Time to live" to "About 30 days at the current price",
        "Immutable" to "No: once full, its oldest chunks are overwritten",
        "Pays" to "0.2165 BZZ",
        "Batch id" to ID,
        "Postage contract" to STAMP,
        "Fee" to "0.00021 xDAI at most, normal fees",
        "Read at" to "Price and batch at block 43,000,520, fees at block 43,000,520",
    ),
    warnings = emptyList(),
    unlock = UnlockStep(),
)

private val dilution = PostageStep.Review(
    title = "Dilute batch 0xa1b2c3d4…c3d4 to depth 23",
    position = null,
    lines = listOf(
        "Batch" to ID,
        "Holds" to "${SwarmTexts.capacity(22)} → ${SwarmTexts.capacity(23)}",
        "Time left" to "About 41 days → About 20 days",
        "Moves" to "No xBZZ",
        "Signed by" to "Bee node, $NODE",
        "Fee" to "0.00009 xDAI at most, normal fees",
        "Read at" to "Price and batch at block 43,000,520, fees at block 43,000,520",
    ),
    warnings = listOf(PostageTexts.NODE_KEY_WARNING, PostageTexts.IMMUTABLE_WARNING),
    unlock = UnlockStep(),
    note = PostageTexts.NONCE_TAKEN,
)

@Composable
private fun PostagePreview(state: PostageUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        PostageScreen(state = state, onBack = {}, actions = PostageActions())
    }
}

@Preview
@Composable
private fun PostageBuyPreview() = PostagePreview(buy)

@Preview
@Composable
private fun PostageBuyDarkPreview() = PostagePreview(buy, darkTheme = true)

/** The form saying why nothing was quoted, with the transaction on its way that holds it back. */
@Preview
@Composable
private fun PostageRefusedPreview() = PostagePreview(
    buy.copy(step = buyForm.copy(error = PostageTexts.PULL_PENDING, pending = "0x" + "ab".repeat(32))),
)

@Preview
@Composable
private fun PostageQuotingPreview() = PostagePreview(buy.copy(step = buyForm.copy(quoting = true)))

@Preview
@Composable
private fun PostageTopUpPreview() = PostagePreview(topUp)

@Preview
@Composable
private fun PostageDilutePreview() = PostagePreview(dilute)

@Preview
@Composable
private fun PostageApprovalPreview() = PostagePreview(buy.copy(step = approval))

@Preview
@Composable
private fun PostageLedgerApprovalPreview() =
    PostagePreview(buy.copy(step = approval.copy(unlock = UnlockStep(ledger = true))))

@Preview
@Composable
private fun PostageAwaitingPreview() = PostagePreview(
    buy.copy(step = PostageStep.Awaiting("0x" + "ab".repeat(32), PostageTexts.AWAITING)),
)

@Preview
@Composable
private fun PostagePurchasePreview() = PostagePreview(buy.copy(step = purchase))

/** The node's own key signs: Bee's warning, what immutable means, and a quote made again. */
@Preview
@Composable
private fun PostageDilutionPreview() = PostagePreview(dilute.copy(step = dilution))

@Preview
@Composable
private fun PostageSigningPreview() = PostagePreview(buy.copy(step = purchase.copy(signing = true)))

@Preview
@Composable
private fun PostageLoadingPreview() = PostagePreview(PostageUiState.Loading)

@Preview
@Composable
private fun PostageNoPayerPreview() = PostagePreview(PostageUiState.Unavailable(PostageTexts.NO_PAYER))

/** Every preview above, with the state it shows: what PostageScreenPreviewsTest renders. */
internal val postageScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("buy") { PostageBuyPreview() },
    ScreenPreview("buy-dark") { PostageBuyDarkPreview() },
    ScreenPreview("refused") { PostageRefusedPreview() },
    ScreenPreview("quoting") { PostageQuotingPreview() },
    ScreenPreview("top-up") { PostageTopUpPreview() },
    ScreenPreview("dilute") { PostageDilutePreview() },
    ScreenPreview("approval") { PostageApprovalPreview() },
    ScreenPreview("approval-ledger") { PostageLedgerApprovalPreview() },
    ScreenPreview("awaiting") { PostageAwaitingPreview() },
    ScreenPreview("purchase") { PostagePurchasePreview() },
    ScreenPreview("dilution") { PostageDilutionPreview() },
    ScreenPreview("signing") { PostageSigningPreview() },
    ScreenPreview("loading") { PostageLoadingPreview() },
    ScreenPreview("no-payer") { PostageNoPayerPreview() },
)
