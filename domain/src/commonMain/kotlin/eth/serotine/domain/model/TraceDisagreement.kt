package eth.serotine.domain.model

/**
 * Where a verified trace says otherwise than a call's description (docs/clear-signing.md, *Phase B*, decision
 * 2): warned about loudly, the call still signable.
 */
sealed interface TraceDisagreement {
    /** The account sends [token] (a token, wrapped currency or an NFT collection) the description does not name. */
    data class TokenNotNamed(val token: Address) : TraceDisagreement

    /**
     * Some of [asset] ends up with [holder], which is neither the account, nor a recipient the description
     * shows, nor the zero address (a burn), nor, for an asset the account sends, a counterparty that pays
     * on toward the account or a recipient in the trace (docs/clear-signing.md, *The disagreements*).
     */
    data class OtherRecipient(val asset: TracedAsset, val holder: Address) : TraceDisagreement

    /** The trace grants [right] while the call carries no approval flag (A3). */
    data class RightNotFlagged(val right: TracedRight) : TraceDisagreement
}

/**
 * Holds a [TraceReading] of [account]'s call against its [ClearSignResult.ClearSigned] description
 * (docs/clear-signing.md, *Phase B*, *The disagreements*). A call that is not described, or that reverts, has
 * none: there is no claim to hold it against, or nothing it does stays.
 */
object TraceDisagreements {

    /** Every disagreement, each once, in the order: tokens not named, other recipients, rights not flagged. */
    fun check(account: Address, reading: TraceReading, description: ClearSignResult): List<TraceDisagreement> {
        val described = description as? ClearSignResult.ClearSigned ?: return emptyList()
        val effects = reading.effects
        if (effects.revert != null) return emptyList()
        val named = mutableSetOf<Address>()
        val recipients = mutableSetOf<Address>()
        val namesCurrency = collect(described, named, recipients)

        val sent = effects.moves.filter { it.from == account }.mapNotNull { it.asset.contract }
        val notNamed = sent.distinct().filter { it !in named }.map { TraceDisagreement.TokenNotNamed(it) }

        val others = if (recipients.isEmpty()) emptyList() else otherRecipients(account, reading, named, recipients, namesCurrency)

        val unflagged = if (described.grantsApproval) emptyList() else effects.rights.map { TraceDisagreement.RightNotFlagged(it) }
        return notNamed + others + unflagged
    }

    /**
     * Where the account's assets, and those the description names, end up, against where the description says.
     * The assets held: each the account sends or receives, each the description names, and the network's
     * currency where the account moves it or the description shows an amount of it. An end holder ([endHolders])
     * is fine when it is the account, a recipient or the zero address; for an asset the account sends, also
     * when it pays on: a move of anything leads from it, directly or through others, to the account or a
     * recipient (a pool that is paid and pays back). The currency and its wrapped token count as one here. An asset the account does not send has no such exception:
     * a pool keeps what it is paid, never what it pays out.
     */
    private fun otherRecipients(
        account: Address,
        reading: TraceReading,
        named: Set<Address>,
        recipients: Set<Address>,
        namesCurrency: Boolean,
    ): List<TraceDisagreement.OtherRecipient> {
        val mine = reading.effects.moves
        // The currency and its wrapped token are one to the account: it pays in either, wrapped or unwrapped on the way.
        val wrapped = reading.wrappedNative
        val paid = mine.filter { it.from == account }.map { key(it.asset) }.toSet()
        val sent = if (wrapped != null && (CURRENCY in paid || wrapped in paid)) paid + CURRENCY + wrapped else paid
        val considered = mine.map { key(it.asset) }.toSet() + named + listOfNotNull(CURRENCY.takeIf { namesCurrency })
        val ends = setOf(account, Address.ZERO) + recipients
        val paysOn = payers(reading.allMoves, recipients + account)
        return endHolders(reading.allMoves.filter { key(it.asset) in considered })
            .filter { (asset, holder) -> holder !in ends && !(key(asset) in sent && holder in paysOn) }
            .map { (asset, holder) -> TraceDisagreement.OtherRecipient(asset, holder) }
    }

    /** Every address from which a chain of [moves] leads to one of [targets]. */
    private fun payers(moves: List<TracedMove>, targets: Set<Address>): Set<Address> {
        val into = moves.groupBy({ it.to }, { it.from })
        val reached = targets.toMutableSet()
        val queue = ArrayDeque(targets)
        while (queue.isNotEmpty()) {
            for (from in into[queue.removeFirst()].orEmpty()) if (reached.add(from)) queue.addLast(from)
        }
        return reached - targets
    }

    /** The contract an asset is told by: its issuer, or [CURRENCY] for the network's own. */
    private fun key(asset: TracedAsset): Address = asset.contract ?: CURRENCY

    /** Stands for the network's currency among contracts: no contract is at it ([Address.ZERO] is a burn). */
    private val CURRENCY = Address.of("0x" + "e".repeat(40))

    /** The tokens and the recipients a description names, its nested calls' included; true when it shows currency. */
    private fun collect(described: ClearSignResult.ClearSigned, named: MutableSet<Address>, recipients: MutableSet<Address>): Boolean {
        var currency = false
        for (field in described.fields) when (field) {
            is ClearSignedField.Value -> {
                field.token?.let(named::add)
                field.recipient?.let(recipients::add)
                if (field.nativeCurrency) currency = true
            }
            is ClearSignedField.Nested ->
                if ((field.result as? ClearSignResult.ClearSigned)?.let { collect(it, named, recipients) } == true) currency = true
        }
        return currency
    }

    /**
     * Each asset with each address that ends up holding more of it than it started with, in the order first
     * credited: where the tokens end up, past those that only pass them on.
     */
    private fun endHolders(moves: List<TracedMove>): List<Pair<TracedAsset, Address>> {
        val credits = linkedMapOf<Pair<TracedAsset, Address>, UInt256>()
        val debits = mutableMapOf<Pair<TracedAsset, Address>, UInt256>()
        for (move in moves) {
            if (move.from == move.to) continue
            val asset = move.asset
            credits[asset to move.to] = (credits[asset to move.to] ?: UInt256.ZERO).plusOrNull(move.amount) ?: UInt256.MAX
            debits[asset to move.from] = (debits[asset to move.from] ?: UInt256.ZERO).plusOrNull(move.amount) ?: UInt256.MAX
        }
        return credits.filter { (key, credit) -> credit > (debits[key] ?: UInt256.ZERO) }.keys.toList()
    }
}
