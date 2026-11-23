package eth.serotine.ui.swarm

/**
 * A Swarm node's screen (docs/swarm.md; docs/wallet-ux.md, *Swarm nodes*), display-ready: the
 * node's chequebook and postage batches as the light client proves them, and adding one by hand.
 */
sealed interface SwarmUiState {

    /** Not read yet. */
    data object Loading : SwarmUiState

    /** Nothing to show: the account is no Swarm node's, or cannot be read now, as [message] says. */
    data class Unavailable(val message: String) : SwarmUiState

    /** The account left the wallet: the screen leaves. */
    data object Gone : SwarmUiState

    /**
     * The node [label] at [address] (EIP-55): its [chequebooks] with [chequebookNote] saying why
     * there are none; its [batches] with [batchNote] likewise; [note] about the candidates kept
     * (set aside once damaged); [adding], the form that adds one by hand, when open; [addReason],
     * why Add is greyed out (Gnosis Chain turned off), null when it can be done; [buy], Buy a batch,
     * greyed out with its reason when it cannot be done here; and [message], what the last action
     * said when it could not be done.
     */
    data class Ready(
        val label: String,
        val address: String,
        val chequebooks: List<ChequebookRow>,
        val chequebookNote: String?,
        val batches: List<BatchRow>,
        val batchNote: String?,
        val note: String? = null,
        val adding: AddForm? = null,
        val addReason: String? = null,
        val buy: SwarmAction = SwarmAction(),
        val message: String? = null,
    ) : SwarmUiState
}

/**
 * An action shown either way (owner ruling, 2026-10-18, #55): done when [reason] is null, greyed out
 * otherwise, a tap saying [reason]; [turnOn] when turning Gnosis Chain on is the way to it.
 */
data class SwarmAction(val reason: String? = null, val turnOn: Boolean = false)

/**
 * A chequebook kept for the node: its EIP-55 [address]; its [lines] once verified ("Balance 12
 * BZZ", "Free to withdraw 12 BZZ", "Paid out 0 BZZ"), empty otherwise; [warning] where a
 * cheque of it bounced; [status], where it stands: verified at a block, held as last verified,
 * not read yet, or why it is not available; and Withdraw ([withdraw]) for one proven the node's,
 * null otherwise.
 */
data class ChequebookRow(
    val address: String,
    val lines: List<String>,
    val warning: String?,
    val status: String,
    val withdraw: SwarmAction? = null,
)

/**
 * A batch kept for the node: its [id] (`0x` and 64 hex digits) and [shortId]; its [capacity]
 * ("Depth 22 · 7.73 GB of 17.18 GB theoretical") and [timeLeft] ("About 41 days left at the current
 * price", "Expired") once verified, null otherwise; [immutable]; [status] as a chequebook's; and
 * Top up ([topUp]) and Dilute ([dilute]) for a batch proven the node's, null otherwise.
 */
data class BatchRow(
    val id: String,
    val shortId: String,
    val capacity: String?,
    val timeLeft: String?,
    val immutable: Boolean,
    val status: String,
    val topUp: SwarmAction? = null,
    val dilute: SwarmAction? = null,
)

/** What is added by hand: a chequebook by its address, or a batch by its id. */
enum class AddKind { CHEQUEBOOK, BATCH }

/**
 * The form that adds a [kind] by hand: the [text] typed, [checking] while it is read on chain,
 * and [error], why it was not kept. [attempt] tells one form, and one check of it, from another,
 * so that a check's answer reaches only the form that asked; it is not drawn.
 */
data class AddForm(
    val kind: AddKind,
    val text: String = "",
    val checking: Boolean = false,
    val error: String? = null,
    val attempt: Long = 0,
)
