package eth.serotine.domain.model

/**
 * A plain transfer out of a Gnosis Pay account's Safe ([safe]), queued through its Delay modifier
 * ([delay]) and executed by it after the cooldown (docs/gnosis-pay.md, *Sending*): [amount] of
 * [asset] to [recipient], a [DelayCall]. Only [of] makes one, and only a plain
 * transfer: the Safe's currency as the value with no data, or an ERC-20 `transfer` with no value,
 * never to the Safe, its modules or the Delay (owner's decisions, 2026-11-15; CLAUDE.md, *Trust*).
 */
@ConsistentCopyVisibility
data class DelayTransfer private constructor(
    override val safe: Address,
    override val delay: Address,
    val asset: TransferAsset,
    val recipient: Address,
    val amount: UInt256,
) : DelayCall {
    private val token: Token? get() = (asset as? TransferAsset.Erc20Token)?.token

    /** The address the Safe calls: the recipient for its currency, the token's contract for a token. */
    override val to: Address get() = token?.address ?: recipient

    /** The call's value: the amount for the Safe's currency, nothing for a token. */
    override val value: UInt256 get() = if (token == null) amount else UInt256.ZERO

    /** The call's data: none for the Safe's currency, the token's `transfer(recipient, amount)`. */
    override val data: ByteArray get() = token?.let { Erc20.transferData(recipient, amount) } ?: ByteArray(0)

    /**
     * The Delay's hash of the call ([DelayCall.hash]). Computed once: the queued list compares it for
     * every queueing at every poll.
     */
    override val hash: Digest = DelayCalls.hash(to, value, data)

    companion object {
        /**
         * The transfer of [amount] of [asset] to [recipient] from the Safe at [safe] through its
         * Delay at [delay], the Safe's enabled modules being [modules]; or why it is refused.
         */
        fun of(
            safe: Address,
            delay: Address,
            modules: List<Address>,
            asset: TransferAsset,
            recipient: Address,
            amount: UInt256,
        ): DelayTransferPlan {
            val token = (asset as? TransferAsset.Erc20Token)?.token
            return when {
                recipient == Address.ZERO -> DelayTransferPlan.ToZeroAddress
                recipient == safe || recipient == delay || recipient in modules -> DelayTransferPlan.ToTheAccount
                token != null && (token.address == recipient) -> DelayTransferPlan.ToTokenContract
                token != null && (token.address == safe || token.address == delay || token.address in modules) ->
                    DelayTransferPlan.ToTheAccount
                amount.isZero -> DelayTransferPlan.NothingToSend
                else -> DelayTransferPlan.Planned(DelayTransfer(safe, delay, asset, recipient, amount))
            }
        }
    }
}

/** What [DelayTransfer.of] gives. */
sealed interface DelayTransferPlan {

    /** [transfer], a plain transfer the Delay may queue. */
    data class Planned(val transfer: DelayTransfer) : DelayTransferPlan

    /** To the zero address: nobody holds its key, so the amount would be lost for good. */
    data object ToZeroAddress : DelayTransferPlan

    /** To the token's own contract, from which nobody can move it out again. */
    data object ToTokenContract : DelayTransferPlan

    /**
     * To the Safe itself, its Delay or one of its modules, or a "token" that is one of them: a call
     * of the account's own contracts is no plain transfer, and could change its settings.
     */
    data object ToTheAccount : DelayTransferPlan

    /** Nothing to move, and no fee is worth paying for it. */
    data object NothingToSend : DelayTransferPlan
}
