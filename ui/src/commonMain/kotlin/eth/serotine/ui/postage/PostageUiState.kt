package eth.serotine.ui.postage

import eth.serotine.ui.components.UnlockStep

/** What the postage screen does for a Swarm node (docs/swarm.md, *The four actions*). */
enum class PostageAction {
    /** Buy a batch the node owns. */
    BUY,

    /** Top up one of the node's batches. */
    TOP_UP,

    /** Dilute one of the node's batches: the node's own key signs. */
    DILUTE,
}

/**
 * A postage batch bought, topped up or diluted for a Swarm node (docs/swarm.md, *The four actions*),
 * display-ready: the form, the review of each transaction with the unlock that signs it, and the
 * wait for an approval to be mined between the two.
 */
sealed interface PostageUiState {

    /** Not read yet. */
    data object Loading : PostageUiState

    /** Nothing can be done here: [message] says why (no Swarm node, no key held here to sign with). */
    data class Unavailable(val message: String) : PostageUiState

    /** The node left the wallet: the screen leaves. */
    data object Gone : PostageUiState

    /**
     * The [title] of what is done ("Buy a postage batch", "Top up batch 0xa1b2…c3d4"), the [node] it
     * is for ("For mynode, 0x0B52…0086"), the batch's figures as last verified ([batch]: capacity,
     * time left, where it was verified; empty for a purchase), and the [step] the screen is at.
     */
    data class Ready(
        val title: String,
        val node: String,
        val batch: List<String>,
        val step: PostageStep,
    ) : PostageUiState
}

/** Where the screen is: the form, a review, or the wait for an approval. */
sealed interface PostageStep {

    /**
     * The form. [payers]: the accounts whose key is held here on Gnosis Chain that may pay, with
     * the one chosen ([payer], EIP-55); empty for a dilution, which [signer] says the node's own key
     * signs. [depths] offered with the one chosen ([depth]); empty for a top-up. [days] as typed with
     * what it means ([daysLabel]); null for a dilution. [immutable] for a purchase; null otherwise.
     * [explains] what the form does in a line; [error] why the last Review quoted nothing; [quoting]
     * while it is quoted; [note], what the last step did that is no error; and [pending], the hash of
     * a transaction from here on its way that holds this one back, offered as its status.
     */
    data class Compose(
        val payers: List<PayerItem>,
        val payer: String?,
        val signer: String?,
        val depths: List<DepthItem>,
        val depth: Int?,
        val days: String?,
        val daysLabel: String?,
        val immutable: Boolean?,
        val explains: String,
        val error: String? = null,
        val quoting: Boolean = false,
        val note: String? = null,
        val pending: String? = null,
    ) : PostageStep

    /**
     * The review of one transaction: its [title], which of the steps it is ([position]: "Step 1 of 2:
     * the approval"; null for a call alone), its [lines] (label to value), the [warnings] it
     * carries (the node's own key; what immutable means), the [unlock] that signs, [signing] while
     * it signs or is quoted again ([checking]), [error] why the last try sent nothing, and [note] why
     * it was quoted again.
     */
    data class Review(
        val title: String,
        val position: String?,
        val lines: List<Pair<String, String>>,
        val warnings: List<String>,
        val unlock: UnlockStep,
        val signing: Boolean = false,
        val checking: Boolean = false,
        val error: String? = null,
        val note: String? = null,
    ) : PostageStep

    /**
     * The approval [hash] is sent, and the call waits for a block to have it, quoted again until it
     * does: [message] says so. Its status is offered.
     */
    data class Awaiting(val hash: String, val message: String) : PostageStep
}

/** An account that may pay: its EIP-55 [address] and its name in the wallet ([label]). */
data class PayerItem(val address: String, val label: String)

/** A depth offered, with what it holds ([label]: "Depth 22 · 7.73 GB of 17.18 GB theoretical"). */
data class DepthItem(val depth: Int, val label: String)
