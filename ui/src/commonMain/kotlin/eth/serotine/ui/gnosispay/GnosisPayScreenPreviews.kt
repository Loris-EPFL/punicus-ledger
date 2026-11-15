package eth.serotine.ui.gnosispay

import androidx.compose.runtime.Composable
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Gnosis Pay screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. GnosisPayScreenPreviewsTest renders each one in gnosisPayScreenPreviews, at the end.
// The addresses are made up (EIP-55's own examples among them).

private val overview = GnosisPayUiState.Ready(
    label = "Card",
    address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
    delay = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
    waits = "A transfer waits 3 min in the Delay module, then can be executed for 30 min. After that it expires, " +
        "and nothing moves.",
    checkedAt = "Verified by the light client at block 43,000,512",
    members = listOf(MemberRow("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", "Card owner", heldHere = true)),
    send = ActionItem(enabled = true, reason = null),
    queued = emptyList(),
    queuedNote = null,
    step = GnosisPayStep.Overview(),
)

private val waiting = QueuedRow(
    id = "0x" + "ab".repeat(32),
    title = "50 EURe to 0xD1220A…cC0e",
    status = "Waits until Nov 16, 2026, 3:00 PM",
    execute = ActionItem(enabled = false, reason = "The Delay module holds it until Nov 16, 2026, 3:00 PM."),
)

private val ready = QueuedRow(
    id = "0x" + "cd".repeat(32),
    title = "12.5 XDAI to 0x5aAeb6…eAed",
    status = "Ready to execute until Nov 17, 2026, 10:00 PM",
    execute = ActionItem(enabled = true, reason = null),
)

@Composable
private fun GnosisPayPreview(state: GnosisPayUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        GnosisPayScreen(state = state, onBack = {}, actions = GnosisPayActions())
    }
}

@Preview
@Composable
private fun GnosisPayOverviewPreview() = GnosisPayPreview(overview)

@Preview
@Composable
private fun GnosisPayOverviewDarkPreview() = GnosisPayPreview(overview.copy(queued = listOf(waiting)), darkTheme = true)

@Preview
@Composable
private fun GnosisPayQueuedPreview() = GnosisPayPreview(overview.copy(queued = listOf(waiting, ready)))

@Preview
@Composable
private fun GnosisPayExecuteRefusedPreview() = GnosisPayPreview(
    overview.copy(
        queued = listOf(ready),
        step = GnosisPayStep.Overview(
            error = "The controlling account holds too little xDAI for the fee. It pays the fees of both the " +
                "queueing and the execution.",
        ),
    ),
)

@Preview
@Composable
private fun GnosisPayWatchedPreview() = GnosisPayPreview(
    overview.copy(
        members = listOf(MemberRow("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", null, heldHere = false)),
        send = ActionItem(
            enabled = false,
            reason = "The key of the account that controls this Gnosis Pay account is not on this device.",
        ),
    ),
)

@Preview
@Composable
private fun GnosisPayComposePreview() = GnosisPayPreview(
    overview.copy(
        step = GnosisPayStep.Compose(
            assets = listOf(
                AssetItem(null, "XDAI", "0.42 XDAI"),
                AssetItem("0xcB444e90D8198415266c6a2724b7900fb12FC56E", "EURe", "1,250 EURe"),
                AssetItem(
                    "0x52908400098527886E0F7030069857D2E4169EE7",
                    "EURe",
                    "3 EURe",
                    warning = "Not the built-in EURe: another contract",
                ),
            ),
            asset = "0xcB444e90D8198415266c6a2724b7900fb12FC56E",
            to = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
            amount = "50",
            sender = "Card owner",
        ),
    ),
)

@Preview
@Composable
private fun GnosisPayComposeErrorPreview() = GnosisPayPreview(
    overview.copy(
        step = GnosisPayStep.Compose(
            assets = listOf(AssetItem(null, "XDAI", "0.42 XDAI")),
            asset = null,
            to = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
            amount = "5000",
            sender = "Card owner",
            error = "The account holds less than that. The transfer would be queued, wait, and then fail, its " +
                "fees paid.",
        ),
    ),
)

@Preview
@Composable
private fun GnosisPayReviewPreview() = GnosisPayPreview(
    overview.copy(
        step = GnosisPayStep.Review(
            title = "Queue 50 EURe to 0xD1220A…9aDb",
            lines = listOf(
                "From" to "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
                "To" to "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
                "Amount" to "50 EURe",
                "Fee" to "0.0002 XDAI at most, normal (fees at block 43,000,511)",
                "Signer's XDAI" to "0.42 XDAI now, at least 0.4198 XDAI after (block 43,000,512)",
                "What happens" to "Queued now. It waits 3 min, then needs a second transaction to execute it, " +
                    "within 30 min.",
            ),
            fee = FeeLevel.NORMAL,
            unlock = UnlockStep(),
        ),
    ),
)

@Preview
@Composable
private fun GnosisPayReviewRequotedPreview() = GnosisPayPreview(
    overview.copy(
        step = GnosisPayStep.Review(
            title = "Execute 12.5 XDAI to 0x5aAeb6…eAed",
            lines = listOf(
                "To" to "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
                "Amount" to "12.5 XDAI",
                "Fee" to "0.0003 XDAI at most, normal (fees at block 43,000,530)",
                "What happens" to "Executes the transfer: the account's funds move now.",
            ),
            fee = FeeLevel.NORMAL,
            unlock = UnlockStep(),
            note = "The quote was over a minute old, so it was made again: check it, then sign.",
        ),
    ),
)

@Preview
@Composable
private fun GnosisPayLoadingPreview() = GnosisPayPreview(GnosisPayUiState.Loading)

@Preview
@Composable
private fun GnosisPayUnavailablePreview() = GnosisPayPreview(
    GnosisPayUiState.Unavailable(
        "Whether this is a Gnosis Pay account cannot be verified right now: the light client is still syncing.",
    ),
)

/** Every preview above, which GnosisPayScreenPreviewsTest renders: a new preview goes in here too. */
internal val gnosisPayScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("overview") { GnosisPayOverviewPreview() },
    ScreenPreview("overview-dark") { GnosisPayOverviewDarkPreview() },
    ScreenPreview("queued") { GnosisPayQueuedPreview() },
    ScreenPreview("execute-refused") { GnosisPayExecuteRefusedPreview() },
    ScreenPreview("watched") { GnosisPayWatchedPreview() },
    ScreenPreview("compose") { GnosisPayComposePreview() },
    ScreenPreview("compose-error") { GnosisPayComposeErrorPreview() },
    ScreenPreview("review") { GnosisPayReviewPreview() },
    ScreenPreview("review-requoted") { GnosisPayReviewRequotedPreview() },
    ScreenPreview("loading") { GnosisPayLoadingPreview() },
    ScreenPreview("unavailable") { GnosisPayUnavailablePreview() },
)
