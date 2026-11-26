package eth.serotine.domain.model

/** What a [TracedMove] moves. */
sealed interface TracedAsset {
    /** The network's own currency. */
    data object Currency : TracedAsset

    /** A fungible token: an ERC-20, or wrapped currency read from its `Deposit` and `Withdrawal`. */
    data class Fungible(val token: Address) : TracedAsset

    /** An NFT: [id] of [collection], an ERC-721 one or, when [multi], an ERC-1155 one. */
    data class Nft(val collection: Address, val id: UInt256, val multi: Boolean) : TracedAsset

    /** The contract that issues it, or null for the network's currency. */
    val contract: Address?
        get() = when (this) {
            Currency -> null
            is Fungible -> token
            is Nft -> collection
        }
}

/** [amount] of [asset] from [from] to [to], at the verified head. */
data class TracedMove(val asset: TracedAsset, val amount: UInt256, val from: Address, val to: Address)

/** A right over the account's tokens that a traced call grants, as its logs say (docs/clear-signing.md, B2). */
sealed interface TracedRight {
    /** The contract the right is over. */
    val contract: Address

    /** Who may use it. */
    val spender: Address

    /** An ERC-20 allowance of [amount] of [contract]. */
    data class Allowance(override val contract: Address, override val spender: Address, val amount: UInt256) : TracedRight

    /** NFT [id] of [contract], ERC-721's `Approval`. */
    data class OneNft(override val contract: Address, override val spender: Address, val id: UInt256) : TracedRight

    /** Every NFT of [contract], an `ApprovalForAll` set to true. */
    data class AllNfts(override val contract: Address, override val spender: Address) : TracedRight

    /**
     * An allowance through Permit2 ([contract], the pinned one) of [amount] of [token], until [expiration]
     * (unix seconds), from its `Approval` or its `Permit`.
     */
    data class Permit2(
        override val contract: Address,
        override val spender: Address,
        val token: Address,
        val amount: UInt256,
        val expiration: Long,
    ) : TracedRight
}

/** A contract the traced call reached, and whether by a delegate call: its code run in its caller's context. */
data class ReachedContract(val address: Address, val delegatedInto: Boolean)

/**
 * The effects of a traced call (docs/clear-signing.md, *Phase B*, B2), as [TraceReader] reads them from the
 * frames that count: those that neither failed nor sit under one that failed.
 *
 * - [moves]: the account's currency and tokens sent and received.
 * - [rights]: what the call lets someone else do with the account's tokens.
 * - [contracts]: every address the call reached other than the account's own, in the order first reached.
 * - [revert]: set when the call itself fails; then [moves] and [rights] are empty, since nothing it did stays.
 * - [unreadLogs]: logs of a known event that are not the event's encoding, which are not read.
 */
data class TraceEffects(
    val moves: List<TracedMove>,
    val rights: List<TracedRight>,
    val contracts: List<ReachedContract>,
    val revert: TracedRevert?,
    val unreadLogs: Int,
)

/** The call reverted ([error], the EVM's words), with [reason] where the contract gives one. */
data class TracedRevert(val error: String, val reason: String?)

/**
 * What a trace read gives: the [effects] on the account, and [allMoves], every movement of the counted frames,
 * whoever made it, which the recipient check follows to where the tokens end up; and [wrappedNative], the
 * network's pinned wrapped currency it was read with.
 */
data class TraceReading(val effects: TraceEffects, val allMoves: List<TracedMove>, val wrappedNative: Address? = null)

/**
 * Reads a verified trace's effects on [account] (docs/clear-signing.md, *Phase B*, *How it is applied*): a pure
 * function of the frames and the pins.
 *
 * - The currency: the value of each CALL, CREATE, CREATE2 and SELFDESTRUCT frame; a delegate call moves none.
 *   Wrapped currency ([wrappedNative]) is read from its `Deposit` and `Withdrawal`.
 * - Tokens: ERC-20 `Transfer` (three topics), ERC-721 `Transfer` (four), ERC-1155 `TransferSingle` and
 *   `TransferBatch`.
 * - Rights: `Approval` (an amount, or an NFT, other than none) and `ApprovalForAll` (true) with the account as
 *   owner, unless the frame's sender is the spender (a spend of an allowance logs a lower `Approval`); Permit2's
 *   `Approval` and `Permit`, emitted by [permit2] only.
 */
object TraceReader {

    /** Reads [root], the transaction's frame. */
    fun read(account: Address, root: TraceFrame, wrappedNative: Address?, permit2: Address): TraceReading {
        val contracts = linkedMapOf<Address, Boolean>()
        val moves = mutableListOf<TracedMove>()
        val rights = mutableListOf<TracedRight>()
        var unread = 0

        fun visit(frame: TraceFrame, sender: Address, counted: Boolean) {
            val counts = counted && !frame.failed
            frame.to?.takeUnless { isPrecompile(it) || it == account }?.let { contracts[it] = (contracts[it] ?: false) || frame.type.runsInCallersContext }
            // A delegate call runs in its caller's context: its logs and calls have its caller's sender.
            val inner = if (frame.type.runsInCallersContext) sender else frame.from
            if (counts) {
                val value = frame.value
                if (frame.type.movesValue && value != null && value != UInt256.ZERO && frame.to != null) {
                    moves += TracedMove(TracedAsset.Currency, value, frame.from, frame.to)
                }
                for (log in frame.logs) {
                    when (val read = readLog(log, account, inner, wrappedNative, permit2)) {
                        LogRead.Unread -> unread++
                        is LogRead.Moves -> moves += read.moves
                        is LogRead.Right -> rights += read.right
                        LogRead.Nothing -> Unit
                    }
                }
            }
            for (call in frame.calls) visit(call, inner, counts)
        }
        visit(root, root.from, counted = true)

        val revert = root.error?.let { TracedRevert(it, root.revertReason) }
        val counted = if (revert != null) emptyList() else moves
        val effects = TraceEffects(
            moves = counted.filter { (it.from == account) != (it.to == account) },
            rights = if (revert != null) emptyList() else rights,
            contracts = contracts.map { (address, delegated) -> ReachedContract(address, delegated) },
            revert = revert,
            unreadLogs = unread,
        )
        return TraceReading(effects, counted, wrappedNative)
    }

    private sealed interface LogRead {
        data object Nothing : LogRead
        data object Unread : LogRead
        data class Moves(val moves: List<TracedMove>) : LogRead
        data class Right(val right: TracedRight) : LogRead
    }

    private fun readLog(log: TraceLog, account: Address, sender: Address, wrappedNative: Address?, permit2: Address): LogRead {
        val topics = log.topics
        val event = topics.firstOrNull() ?: return LogRead.Nothing
        val words = words(log.data)
        fun address(topic: String): Address? = topicAddress(topic)
        val emitter = log.address
        return when (event) {
            TRANSFER -> when (topics.size) {
                3 -> {
                    val from = address(topics[1]) ?: return LogRead.Unread
                    val to = address(topics[2]) ?: return LogRead.Unread
                    val amount = words?.singleOrNull() ?: return LogRead.Unread
                    LogRead.Moves(listOf(TracedMove(TracedAsset.Fungible(emitter), amount, from, to)))
                }
                4 -> {
                    val from = address(topics[1]) ?: return LogRead.Unread
                    val to = address(topics[2]) ?: return LogRead.Unread
                    if (words?.isEmpty() != true) return LogRead.Unread
                    val id = word(topics[3]) ?: return LogRead.Unread
                    LogRead.Moves(listOf(TracedMove(TracedAsset.Nft(emitter, id, multi = false), UInt256.ONE, from, to)))
                }
                else -> LogRead.Unread
            }
            TRANSFER_SINGLE -> {
                if (topics.size != 4) return LogRead.Unread
                val from = address(topics[2]) ?: return LogRead.Unread
                val to = address(topics[3]) ?: return LogRead.Unread
                val (id, amount) = words?.takeIf { it.size == 2 } ?: return LogRead.Unread
                LogRead.Moves(listOf(TracedMove(TracedAsset.Nft(emitter, id, multi = true), amount, from, to)))
            }
            TRANSFER_BATCH -> {
                if (topics.size != 4) return LogRead.Unread
                val from = address(topics[2]) ?: return LogRead.Unread
                val to = address(topics[3]) ?: return LogRead.Unread
                val (ids, amounts) = twoArrays(words) ?: return LogRead.Unread
                LogRead.Moves(ids.zip(amounts) { id, amount -> TracedMove(TracedAsset.Nft(emitter, id, multi = true), amount, from, to) })
            }
            DEPOSIT, WITHDRAWAL -> {
                if (emitter != wrappedNative) return LogRead.Nothing
                if (topics.size != 2) return LogRead.Unread
                val holder = address(topics[1]) ?: return LogRead.Unread
                val amount = words?.singleOrNull() ?: return LogRead.Unread
                // Wrapping mints the token to the holder; unwrapping burns it: a move between it and the contract.
                val move = if (event == DEPOSIT) TracedMove(TracedAsset.Fungible(emitter), amount, emitter, holder)
                else TracedMove(TracedAsset.Fungible(emitter), amount, holder, emitter)
                LogRead.Moves(listOf(move))
            }
            APPROVAL -> {
                when (topics.size) {
                    3 -> {
                        val owner = address(topics[1]) ?: return LogRead.Unread
                        val spender = address(topics[2]) ?: return LogRead.Unread
                        val amount = words?.singleOrNull() ?: return LogRead.Unread
                        if (owner != account || amount == UInt256.ZERO || sender == spender) LogRead.Nothing
                        else LogRead.Right(TracedRight.Allowance(emitter, spender, amount))
                    }
                    4 -> {
                        val owner = address(topics[1]) ?: return LogRead.Unread
                        val spender = address(topics[2]) ?: return LogRead.Unread
                        if (words?.isEmpty() != true) return LogRead.Unread
                        val id = word(topics[3]) ?: return LogRead.Unread
                        if (owner != account || spender == Address.ZERO || sender == spender) LogRead.Nothing
                        else LogRead.Right(TracedRight.OneNft(emitter, spender, id))
                    }
                    else -> LogRead.Unread
                }
            }
            APPROVAL_FOR_ALL -> {
                if (topics.size != 3) return LogRead.Unread
                val owner = address(topics[1]) ?: return LogRead.Unread
                val operator = address(topics[2]) ?: return LogRead.Unread
                val approved = words?.singleOrNull() ?: return LogRead.Unread
                if (approved != UInt256.ZERO && approved != UInt256.ONE) return LogRead.Unread
                if (owner != account || approved == UInt256.ZERO || sender == operator) LogRead.Nothing
                else LogRead.Right(TracedRight.AllNfts(emitter, operator))
            }
            PERMIT2_APPROVAL -> if (emitter == permit2) permit2Right(topics, words, account, permit2, withNonce = false) else LogRead.Nothing
            PERMIT2_PERMIT -> if (emitter == permit2) permit2Right(topics, words, account, permit2, withNonce = true) else LogRead.Nothing
            else -> LogRead.Nothing
        }
    }

    /** Permit2's `Approval(owner, token, spender; amount, expiration)` or `Permit(…; amount, expiration, nonce)`. */
    private fun permit2Right(topics: List<String>, words: List<UInt256>?, account: Address, permit2: Address, withNonce: Boolean): LogRead {
        if (topics.size != 4) return LogRead.Unread
        val owner = topicAddress(topics[1]) ?: return LogRead.Unread
        val token = topicAddress(topics[2]) ?: return LogRead.Unread
        val spender = topicAddress(topics[3]) ?: return LogRead.Unread
        val values = words?.takeIf { it.size == if (withNonce) 3 else 2 } ?: return LogRead.Unread
        val amount = values[0].takeIf { it <= MAX_UINT160 } ?: return LogRead.Unread
        val expiration = values[1].takeIf { it <= MAX_UINT48 }?.toLongOrNull() ?: return LogRead.Unread
        if (owner != account || amount == UInt256.ZERO) return LogRead.Nothing
        return LogRead.Right(TracedRight.Permit2(permit2, spender, token, amount, expiration))
    }

    /** The 32-byte words of [hex], or null when it is not a whole number of them. */
    private fun words(hex: String): List<UInt256>? {
        val digits = hex.removePrefix("0x")
        if (digits.length % WORD_HEX != 0 || digits.length > MAX_LOG_HEX) return null
        return (0 until digits.length / WORD_HEX).map { word(digits.substring(it * WORD_HEX, (it + 1) * WORD_HEX)) ?: return null }
    }

    private fun word(hex: String): UInt256? {
        val digits = hex.removePrefix("0x")
        if (digits.length != WORD_HEX || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return UInt256.fromBigEndian(digits.hexToBytes())
    }

    /** The address a topic holds, or null when its upper twelve bytes are not zero. */
    private fun topicAddress(topic: String): Address? {
        val digits = topic.removePrefix("0x").lowercase()
        if (digits.length != WORD_HEX || !digits.startsWith(ADDRESS_PADDING)) return null
        return Address.parse("0x" + digits.substring(ADDRESS_PADDING.length))
    }

    /** `(uint256[], uint256[])` in canonical ABI encoding, of one length, or null. */
    private fun twoArrays(words: List<UInt256>?): Pair<List<UInt256>, List<UInt256>>? {
        if (words == null || words.size < 4) return null
        val first = words[0].toLongOrNull() ?: return null
        val count = words[2].toLongOrNull() ?: return null
        if (first != 2L * WORD_BYTES || count > (words.size - 4) / 2) return null
        val n = count.toInt()
        val second = words[1].toLongOrNull() ?: return null
        if (second != (3L + n) * WORD_BYTES || words.size != 4 + 2 * n || words[3 + n].toLongOrNull() != count) return null
        return words.subList(3, 3 + n) to words.subList(4 + n, 4 + 2 * n)
    }

    /** The precompiles and the system contracts below 0x10000: no contract the user is shown. */
    private fun isPrecompile(address: Address): Boolean = address.hex.startsWith(PRECOMPILE_PREFIX)

    private fun topic(signature: String): String = keccak256(signature.encodeToByteArray()).toHex()

    private const val WORD_BYTES = 32
    private const val WORD_HEX = 64
    private const val MAX_LOG_HEX = 1 shl 20
    private val ADDRESS_PADDING = "0".repeat(24)
    private val PRECOMPILE_PREFIX = "0x" + "0".repeat(36)
    private val MAX_UINT160 = UInt256.fromBigEndian(ByteArray(20) { -1 })
    private val MAX_UINT48 = UInt256.fromBigEndian(ByteArray(6) { -1 })

    private val TRANSFER = topic("Transfer(address,address,uint256)")
    private val APPROVAL = topic("Approval(address,address,uint256)")
    private val APPROVAL_FOR_ALL = topic("ApprovalForAll(address,address,bool)")
    private val TRANSFER_SINGLE = topic("TransferSingle(address,address,address,uint256,uint256)")
    private val TRANSFER_BATCH = topic("TransferBatch(address,address,address,uint256[],uint256[])")
    private val DEPOSIT = topic("Deposit(address,uint256)")
    private val WITHDRAWAL = topic("Withdrawal(address,uint256)")
    private val PERMIT2_APPROVAL = topic("Approval(address,address,address,uint160,uint48)")
    private val PERMIT2_PERMIT = topic("Permit(address,address,address,uint160,uint48,uint48)")
}
