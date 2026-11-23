package eth.serotine.ui.withdraw

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.WithdrawalQuote
import eth.serotine.domain.usecase.WithdrawalRefusal
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.callRefusalText
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.grouped
import eth.serotine.ui.postage.PostageTexts
import eth.serotine.ui.swarm.SwarmTexts

/** The words of the withdrawal screen (docs/swarm.md, *Withdraw from the chequebook*). */
internal object WithdrawTexts {

    const val BZZ_DECIMALS = 16
    private const val NATIVE_DECIMALS = 18
    private val network = Network.GNOSIS

    const val TITLE = "Withdraw from the chequebook"

    /** What the review warns of, always (docs/swarm.md, *Withdraw from the chequebook*). */
    const val CHEQUES_WARNING =
        "Bee may have issued cheques from this chequebook that are not cashed yet. Punicus cannot see them. If " +
            "one cannot be paid in full it bounces, the chequebook is marked as bounced for good, and peers may " +
            "stop serving the node."

    const val NODE_KEY_WARNING = PostageTexts.NODE_KEY_WARNING
    const val NODE_KEY_NOT_HELD = SwarmTexts.NODE_KEY_NOT_WITHDRAWING
    const val NOT_SWARM = SwarmTexts.NOT_SWARM
    const val LIST_UNAVAILABLE = SwarmTexts.LIST_UNAVAILABLE

    fun bzz(plur: UInt256) = exactAmount(plur, BZZ_DECIMALS, "BZZ")

    fun amountError(text: String): String? = when {
        text.isBlank() -> "Say how much, in BZZ."
        UInt256.parseUnits(text.trim(), BZZ_DECIMALS) == null -> "An amount in BZZ, with at most 16 decimals."
        else -> null
    }

    fun lines(quote: WithdrawalQuote, node: String): List<Pair<String, String>> = listOf(
        "Withdraws" to bzz(quote.amount),
        "To" to "$node, ${Eip55.format(quote.quote.from)}: the node's own address, always",
        "Chequebook" to Eip55.format(quote.chequebook.address),
        "Free to withdraw" to bzz(quote.chequebook.liquidBalance),
        "Fee" to "${exactAmount(quote.quote.maxFee, NATIVE_DECIMALS, network.nativeSymbol)} at most, normal fees",
        "Read at" to "Chequebook at block ${grouped(quote.readAt)}, fees at block ${grouped(quote.quote.feeBlock)}",
    )

    fun refused(refusal: WithdrawalRefusal): String = when (refusal) {
        WithdrawalRefusal.NotPinned -> "No chequebook contracts are pinned on Gnosis Chain in this release."
        is WithdrawalRefusal.NotTheNodes -> when (val finding = refusal.finding) {
            is ChequebookFinding.OthersIssuer -> SwarmTexts.othersChequebook(Eip55.format(finding.issuer))
            else -> SwarmTexts.NO_LONGER_A_CHEQUEBOOK
        }
        WithdrawalRefusal.NothingToWithdraw -> "A withdrawal takes something: say how much."
        is WithdrawalRefusal.MoreThanFree -> "At most ${bzz(refusal.free)} is free to withdraw now."
        is WithdrawalRefusal.Unavailable -> "Not quoted: " + SwarmTexts.unavailable(refusal.reason)
        is WithdrawalRefusal.NotQuoted -> callRefusalText(refusal.refusal, network)
    }

    const val QUOTE_FAILED = PostageTexts.QUOTE_FAILED
    const val SEND_FAILED = PostageTexts.SEND_FAILED
    const val NONCE_TAKEN = PostageTexts.NONCE_TAKEN
    const val CHANGED =
        "The chequebook changed since the quote: Bee may have cashed out, or a cheque was cashed. Nothing was " +
            "signed; the quote was made again: check it, then sign."

    fun notChecked(reason: ReadUnavailable) = PostageTexts.notChecked(reason)

    fun biometricReason(quote: WithdrawalQuote) = "Withdraw ${bzz(quote.amount)} from the chequebook on Gnosis Chain"
}
