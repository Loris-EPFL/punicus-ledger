package eth.serotine.domain.model

/**
 * A call a Gnosis Pay account's Safe ([safe]) makes through its Delay modifier ([delay]): queued by
 * a key that controls the Delay, then executed after the cooldown (docs/gnosis-pay.md, *Sending*).
 * What the Delay holds of it is the call the Safe makes: to [to] with [value] and [data], operation
 * CALL, never DELEGATECALL. Only two kinds exist, each made only by its own planner, which refuses
 * what the rules refuse: a plain transfer ([DelayTransfer]), and a member of the Delay added or
 * removed ([DelayMemberChange]; owner ruling, 2026-11-18). Nothing else is ever queued
 * (CLAUDE.md, *Trust*).
 */
sealed interface DelayCall {
    /** The Gnosis Pay account's Safe, which makes the call once it is executed. */
    val safe: Address

    /** The Safe's Delay modifier, which queues the call. */
    val delay: Address

    /** The address the Safe calls. */
    val to: Address

    /** The call's value, in the Safe's currency's smallest unit. */
    val value: UInt256

    /** The call's data. */
    val data: ByteArray

    /**
     * The Delay's hash of the call, `keccak256(abi.encodePacked(to, value, data, operation))` with
     * operation CALL (0): what its queue holds (`txHash(n)`) and what `executeNextTx` checks.
     */
    val hash: Digest

    /** `execTransactionFromModule(to, value, data, CALL)`: what a member sends the Delay to queue it. */
    fun queueData(): ByteArray = DelayCalls.moduleCall(DelayCalls.QUEUE, to, value, data)

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
    fun executeData(): ByteArray = DelayCalls.moduleCall(DelayCalls.EXECUTE, to, value, data)
}

/** Whether this call and [other] are the same call of one Delay for one Safe: what its queue tells apart. */
fun DelayCall.sameCall(other: DelayCall): Boolean = safe == other.safe && delay == other.delay && hash == other.hash

/** The encoding every [DelayCall] shares: its hash and the Delay's two module calls. */
internal object DelayCalls {
    private const val CALL: Byte = 0

    /** The offset of `data` in the four arguments' head: four words. */
    private const val DATA_OFFSET = 4L * 32

    val QUEUE = AbiWords.selector("execTransactionFromModule(address,uint256,bytes,uint8)")
    val EXECUTE = AbiWords.selector("executeNextTx(address,uint256,bytes,uint8)")

    /** `keccak256(abi.encodePacked(to, value, data, uint8(CALL)))`. */
    fun hash(to: Address, value: UInt256, data: ByteArray): Digest =
        Digest.of(keccak256(to.toBytes() + value.toBytes32() + data + byteArrayOf(CALL)).toHex())

    /** The Delay's [selector] (queue or execute) with the four arguments, operation CALL. */
    fun moduleCall(selector: ByteArray, to: Address, value: UInt256, data: ByteArray): ByteArray =
        selector + AbiWords.word(to) + value.toBytes32() + AbiWords.word(DATA_OFFSET) + AbiWords.word(CALL.toLong()) +
            AbiWords.dynamic(data)
}
