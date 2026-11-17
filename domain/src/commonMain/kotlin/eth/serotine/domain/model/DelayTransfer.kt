package eth.serotine.domain.model

/**
 * A plain transfer out of a Gnosis Pay account's Safe ([safe]), queued through its Delay modifier
 * ([delay]) and executed by it after the cooldown (docs/gnosis-pay.md, *Sending*): [amount] of
 * [asset] to [recipient]. What the Delay holds of it is the call the Safe makes: to [to] with
 * [value] and [data], operation CALL, never DELEGATECALL. Only [of] makes one, and only a plain
 * transfer: the Safe's currency as the value with no data, or an ERC-20 `transfer` with no value,
 * never to the Safe, its modules or the Delay (owner's decisions, 2026-11-15; CLAUDE.md, *Trust*).
 */
@ConsistentCopyVisibility
data class DelayTransfer private constructor(
    val safe: Address,
    val delay: Address,
    val asset: TransferAsset,
    val recipient: Address,
    val amount: UInt256,
) {
    private val token: Token? get() = (asset as? TransferAsset.Erc20Token)?.token

    /** The address the Safe calls: the recipient for its currency, the token's contract for a token. */
    val to: Address get() = token?.address ?: recipient

    /** The call's value: the amount for the Safe's currency, nothing for a token. */
    val value: UInt256 get() = if (token == null) amount else UInt256.ZERO

    /** The call's data: none for the Safe's currency, the token's `transfer(recipient, amount)`. */
    val data: ByteArray get() = token?.let { Erc20.transferData(recipient, amount) } ?: ByteArray(0)

    /**
     * The Delay's hash of the call, `keccak256(abi.encodePacked(to, value, data, operation))` with
     * operation CALL (0): what its queue holds (`txHash(n)`) and what `executeNextTx` checks.
     * Computed once: the queued list compares it for every queueing at every poll.
     */
    val hash: Digest = Digest.of(keccak256(to.toBytes() + value.toBytes32() + data + byteArrayOf(CALL)).toHex())

    /** `execTransactionFromModule(to, value, data, CALL)`: what a member sends the Delay to queue it. */
    fun queueData(): ByteArray = moduleCall(QUEUE)

    /**
     * The queueing as [sender] sends it, as the address it goes to and its call data: [queueData]
     * to the Delay when [via] is empty ([sender] is a member); otherwise through the Safes in [via],
     * the Delay's member first and the Safe [sender] owns last, each executing the call of the one
     * before it as [SafeCalls.execAsOwner], confirmed by the owner that calls it (owner's request,
     * 2026-11-17). The transaction then goes to the last Safe of [via].
     */
    fun queueCall(sender: Address, via: List<Address>): Pair<Address, ByteArray> {
        var to = delay
        var data = queueData()
        via.forEachIndexed { i, safe ->
            data = SafeCalls.execAsOwner(to, data, owner = via.getOrNull(i + 1) ?: sender)
            to = safe
        }
        return to to data
    }

    /** `executeNextTx(to, value, data, CALL)`: what is sent the Delay to execute it once its cooldown has passed. */
    fun executeData(): ByteArray = moduleCall(EXECUTE)

    private fun moduleCall(selector: ByteArray): ByteArray =
        selector + AbiWords.word(to) + value.toBytes32() + AbiWords.word(DATA_OFFSET) + AbiWords.word(CALL.toLong()) +
            AbiWords.dynamic(data)

    companion object {
        private const val CALL: Byte = 0

        /** The offset of `data` in the four arguments' head: four words. */
        private const val DATA_OFFSET = 4L * 32

        private val QUEUE = AbiWords.selector("execTransactionFromModule(address,uint256,bytes,uint8)")
        private val EXECUTE = AbiWords.selector("executeNextTx(address,uint256,bytes,uint8)")

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
