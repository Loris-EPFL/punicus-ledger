package eth.serotine.ui.withdraw

import eth.serotine.ui.components.UnlockStep

/**
 * A Swarm node's xBZZ withdrawn from its chequebook (docs/swarm.md, *Withdraw from the
 * chequebook*), display-ready: the form, then the review with the unlock that signs it.
 */
sealed interface WithdrawUiState {

    /** Not read yet. */
    data object Loading : WithdrawUiState

    /** Nothing can be done here: [message] says why (no key here to sign with, no Swarm node). */
    data class Unavailable(val message: String) : WithdrawUiState

    /** The node left the wallet: the screen leaves. */
    data object Gone : WithdrawUiState

    /**
     * The [node] it is for ("For Bee node, 0x5aAe…eAed"), the [chequebook] (EIP-55), its figures as
     * last verified ([lines]: balance, free to withdraw, where it was verified), and the [step].
     */
    data class Ready(
        val node: String,
        val chequebook: String,
        val lines: List<String>,
        val step: WithdrawStep,
    ) : WithdrawUiState
}

/** Where the screen is: the form or the review. */
sealed interface WithdrawStep {

    /**
     * The form: the [amount] typed, in BZZ, [all] what is free to withdraw as typed into it by All
     * (null while it is not verified, or nothing is), [error] why the last Review quoted nothing,
     * [quoting] while it is quoted, and [pending], the hash of an earlier withdrawal from here on its
     * way that holds this one back, offered as its status.
     */
    data class Compose(
        val amount: String,
        val all: String?,
        val error: String? = null,
        val quoting: Boolean = false,
        val pending: String? = null,
    ) : WithdrawStep

    /**
     * The review: its [lines] (label to value), the [warnings] it carries (uncashed cheques; the
     * node's own key), the [unlock] that signs, [signing] while it signs or is quoted again
     * ([checking]), [error] why the last try sent nothing, and [note] why it was quoted again.
     */
    data class Review(
        val lines: List<Pair<String, String>>,
        val warnings: List<String>,
        val unlock: UnlockStep,
        val signing: Boolean = false,
        val checking: Boolean = false,
        val error: String? = null,
        val note: String? = null,
    ) : WithdrawStep
}
