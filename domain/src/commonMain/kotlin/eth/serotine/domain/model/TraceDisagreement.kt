package eth.serotine.domain.model

/**
 * Where a verified trace says otherwise than a call's description (docs/clear-signing.md, *Phase B*, decision
 * 2): warned about loudly, the call still signable.
 */
sealed interface TraceDisagreement {
    /** The account sends [token] (a token, wrapped currency or an NFT collection) the description does not name. */
    data class TokenNotNamed(val token: Address) : TraceDisagreement

    /**
     * Some of [asset], which the account sends or receives or the description names, ends up with [holder]:
     * neither the account, nor a recipient the description shows, nor a contract the call goes through (one
     * whose code ran: [TraceReading.ranCode]), nor the zero address (a burn).
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
        collect(described, named, recipients)

        val sent = effects.moves.filter { it.from == account }.mapNotNull { it.asset.contract }
        val notNamed = sent.distinct().filter { it !in named }.map { TraceDisagreement.TokenNotNamed(it) }

        val others = if (recipients.isEmpty()) {
            emptyList()
        } else {
            val considered = effects.moves.mapNotNull { it.asset.contract }.toSet() + named
            val allowed = recipients + reading.ranCode + account + Address.ZERO
            endHolders(reading.allMoves.filter { it.asset.contract in considered })
                .filter { (_, holder) -> holder !in allowed }
                .map { (asset, holder) -> TraceDisagreement.OtherRecipient(asset, holder) }
        }

        val unflagged = if (described.grantsApproval) emptyList() else effects.rights.map { TraceDisagreement.RightNotFlagged(it) }
        return notNamed + others + unflagged
    }

    /** The tokens and the recipients a description names, its nested calls' included. */
    private fun collect(described: ClearSignResult.ClearSigned, named: MutableSet<Address>, recipients: MutableSet<Address>) {
        for (field in described.fields) when (field) {
            is ClearSignedField.Value -> {
                field.token?.let(named::add)
                field.recipient?.let(recipients::add)
            }
            is ClearSignedField.Nested -> (field.result as? ClearSignResult.ClearSigned)?.let { collect(it, named, recipients) }
        }
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
