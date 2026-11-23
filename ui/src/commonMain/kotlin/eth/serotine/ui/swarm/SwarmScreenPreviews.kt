package eth.serotine.ui.swarm

import androidx.compose.runtime.Composable
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Swarm node screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. SwarmScreenPreviewsTest renders each one in swarmScreenPreviews, at the end.
// The addresses and ids are made up (EIP-55's own examples among them).

private val chequebook = ChequebookRow(
    address = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
    lines = listOf("Balance 1.2 BZZ", "Free to withdraw 1.2 BZZ", "Paid out 0.35 BZZ"),
    warning = null,
    status = "Verified at block 43,000,512",
)

private val batch = BatchRow(
    id = "0x" + "a1b2c3d4".repeat(8),
    shortId = "0xa1b2c3d4…c3d4",
    capacity = "Depth 22 · 7.73 GB of 17.18 GB theoretical",
    timeLeft = "About 41 days left at the current price",
    immutable = true,
    status = "Verified at block 43,000,512",
)

private val node = SwarmUiState.Ready(
    label = "Bee node",
    address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
    chequebooks = listOf(chequebook),
    chequebookNote = null,
    batches = listOf(batch, batch.copy(id = "0x" + "0f".repeat(32), shortId = "0x0f0f0f0f…0f0f", immutable = false)),
    batchNote = null,
)

@Composable
private fun SwarmPreview(state: SwarmUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SwarmScreen(state = state, onBack = {}, actions = SwarmActions())
    }
}

@Preview
@Composable
private fun SwarmNodePreview() = SwarmPreview(node)

@Preview
@Composable
private fun SwarmNodeDarkPreview() = SwarmPreview(node, darkTheme = true)

@Preview
@Composable
private fun SwarmEmptyPreview() = SwarmPreview(
    node.copy(
        chequebooks = emptyList(),
        chequebookNote = SwarmTexts.NO_CHEQUEBOOK,
        batches = emptyList(),
        batchNote = SwarmTexts.NO_BATCH,
    ),
)

@Preview
@Composable
private fun SwarmBouncedExpiredPreview() = SwarmPreview(
    node.copy(
        chequebooks = listOf(chequebook.copy(warning = SwarmTexts.BOUNCED)),
        batches = listOf(batch.copy(timeLeft = "Expired", immutable = false)),
    ),
)

@Preview
@Composable
private fun SwarmHeldPreview() = SwarmPreview(
    node.copy(
        chequebooks = listOf(chequebook.copy(status = SwarmTexts.verified(43_000_512, held = true))),
        batches = listOf(
            batch.copy(capacity = null, timeLeft = null, immutable = false, status = SwarmTexts.NOT_READ),
            batch.copy(
                id = "0x" + "0f".repeat(32),
                shortId = "0x0f0f0f0f…0f0f",
                capacity = null,
                timeLeft = null,
                status = SwarmTexts.stated(
                    SwarmTexts.othersBatch("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"),
                    43_000_512,
                    held = true,
                ),
            ),
        ),
    ),
)

@Preview
@Composable
private fun SwarmAddChequebookPreview() = SwarmPreview(
    node.copy(adding = AddForm(AddKind.CHEQUEBOOK, text = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")),
)

@Preview
@Composable
private fun SwarmAddCheckingPreview() = SwarmPreview(
    node.copy(adding = AddForm(AddKind.BATCH, text = "0x" + "a1b2c3d4".repeat(8), checking = true)),
)

@Preview
@Composable
private fun SwarmAddRefusedPreview() = SwarmPreview(
    node.copy(
        adding = AddForm(
            AddKind.BATCH,
            text = "0x" + "0e".repeat(32),
            error = "This batch is owned by 0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB, not this node.",
        ),
    ),
)

/** Gnosis Chain off: Add greyed out, a tap saying so with Turn on (owner ruling, 2026-10-18, #55). */
@Preview
@Composable
private fun SwarmNetworkOffPreview() = SwarmPreview(
    node.copy(
        chequebooks = listOf(
            chequebook.copy(lines = emptyList(), status = SwarmTexts.unavailable(ReadUnavailable.NETWORK_OFF)),
        ),
        batches = emptyList(),
        batchNote = SwarmTexts.NO_BATCH,
        addReason = SwarmTexts.NETWORK_OFF,
    ),
)

/** The candidates kept for the node were damaged and set aside: said above the lists. */
@Preview
@Composable
private fun SwarmSetAsidePreview() = SwarmPreview(
    node.copy(
        chequebooks = emptyList(),
        chequebookNote = SwarmTexts.NO_CHEQUEBOOK,
        batches = emptyList(),
        batchNote = SwarmTexts.NO_BATCH,
        note = SwarmTexts.SET_ASIDE,
    ),
)

/** A removal that could not be saved, said above the lists. */
@Preview
@Composable
private fun SwarmNotRemovedPreview() = SwarmPreview(node.copy(message = SwarmTexts.NOT_REMOVED))

@Preview
@Composable
private fun SwarmLoadingPreview() = SwarmPreview(SwarmUiState.Loading)

@Preview
@Composable
private fun SwarmUnavailablePreview() = SwarmPreview(SwarmUiState.Unavailable(SwarmTexts.NOT_SWARM))

/** Every preview above, with the state it shows: what SwarmScreenPreviewsTest renders. */
internal val swarmScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("node") { SwarmNodePreview() },
    ScreenPreview("node-dark") { SwarmNodeDarkPreview() },
    ScreenPreview("empty") { SwarmEmptyPreview() },
    ScreenPreview("bounced-expired") { SwarmBouncedExpiredPreview() },
    ScreenPreview("held") { SwarmHeldPreview() },
    ScreenPreview("add-chequebook") { SwarmAddChequebookPreview() },
    ScreenPreview("add-checking") { SwarmAddCheckingPreview() },
    ScreenPreview("add-refused") { SwarmAddRefusedPreview() },
    ScreenPreview("network-off") { SwarmNetworkOffPreview() },
    ScreenPreview("not-removed") { SwarmNotRemovedPreview() },
    ScreenPreview("set-aside") { SwarmSetAsidePreview() },
    ScreenPreview("loading") { SwarmLoadingPreview() },
    ScreenPreview("unavailable") { SwarmUnavailablePreview() },
)
