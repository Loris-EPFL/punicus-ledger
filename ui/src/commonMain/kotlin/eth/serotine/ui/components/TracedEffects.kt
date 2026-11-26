package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignFormatter
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.ShownText
import eth.serotine.domain.model.TraceDisagreement
import eth.serotine.domain.model.TracedAsset
import eth.serotine.domain.model.TracedRight
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.TraceOutcome

/**
 * What a call does when it runs at the verified head (docs/clear-signing.md, *Phase B*), display-ready: its
 * effects on the account, labelled as proven at [provenAt] and not when it is mined, and loud [warnings] where
 * they disagree with what the call claims; or, while [tracing], that it is being traced; or why it is not
 * ([notTraced]), which *Try again* asks anew when [canRetry].
 */
data class TraceSection(
    val tracing: Boolean = false,
    /** "Proven at block N, at the verified head: what it does now, not when it is mined." */
    val provenAt: String? = null,
    /** The block it was proven at, which a screen holds against its own run's. */
    val block: Long? = null,
    /** [provenAt] in simple mode's block-free words: when it was traced in place of the block. */
    val simpleProvenAt: String? = null,
    /** The account's currency and tokens sent and received, each with its counterparty. */
    val moves: List<String> = emptyList(),
    /** The rights over the account's tokens it grants. */
    val rights: List<String> = emptyList(),
    /** Every contract it reaches, by the wallet's name where it has one. */
    val contracts: List<String> = emptyList(),
    /** It reverts, and why where the contract says. */
    val revert: String? = null,
    /** Logs of a known event that could not be read. */
    val unread: String? = null,
    /** Where the trace says otherwise than what the call claims: loud, the call still signable. */
    val warnings: List<String> = emptyList(),
    val notTraced: String? = null,
    val canRetry: Boolean = false,
) {
    /** Whether it moves or grants nothing it logs. */
    val nothingLogged: Boolean get() = provenAt != null && moves.isEmpty() && rights.isEmpty() && revert == null && unread == null && unread == null
}

/** What the screens say of a verified trace, and how a [TraceOutcome] becomes a [TraceSection]. */
object TraceText {
    const val HEADING = "What it does now"
    const val TRACING = "Tracing it at the verified head…"
    const val DISAGREES = "The trace does not match what this call says it does. Check it carefully: it can " +
        "still be signed."
    const val NOTHING_LOGGED = "It moves nothing of this account's and grants no right over its tokens, as far " +
        "as its contracts log. What a contract does not log is not seen."
    const val LOGS_ONLY = "Read from what the contracts log: a token that moves without logging it is not seen."
    const val NOT_TRACED_HERE = "Not traced: the light client on this device does not trace calls yet."
    const val NOT_IN_TIME = "Not traced within 30 seconds: the call reaches much state. Try again waits for " +
        "the trace still running."
    /** Beside the button that signs, while the trace disagrees: the call stays signable. */
    const val DISAGREES_NEAR_SIGN = "Its trace does not match what it says it does: see \"What it does now\" above " +
        "before you sign."
    const val REVERTS_UNLIKE_RUN = "The trace reverts, while the verified run below did not: the chain moved " +
        "between the two. Quote it again before you send."

    /** The trace reverts at the very block its run did not: not the head moving, and Punicus cannot tell why. */
    const val REVERTS_AT_THE_RUNS_BLOCK = "The trace reverts at the block the verified run below did not: Punicus " +
        "cannot tell why. Do not send it unless you know why."

    /** At most this many lines of each kind are shown; how many more there are is said. */
    const val MAX_LINES = 40

    /** At most this many characters of a contract's revert reason are shown. */
    const val MAX_REASON = 200

    /** [outcome] of [account]'s call on [network] as the screen shows it. */
    fun section(outcome: TraceOutcome, account: Address, network: Network, tracedAt: Long? = null): TraceSection = when (outcome) {
        is TraceOutcome.Traced -> traced(outcome, account, network, tracedAt)
        is TraceOutcome.Infeasible -> TraceSection(notTraced = "Not traced: it cannot run (${outcome.detail}).", canRetry = true)
        TraceOutcome.NotTraced -> TraceSection(notTraced = NOT_TRACED_HERE)
        TraceOutcome.NotInTime -> TraceSection(notTraced = NOT_IN_TIME, canRetry = true)
        // Trying again changes nothing while the network has no light client running.
        is TraceOutcome.Unavailable -> TraceSection(
            notTraced = "Not traced: ${why(outcome.reason)}",
            canRetry = outcome.reason != ReadUnavailable.NETWORK_OFF && outcome.reason != ReadUnavailable.NO_ENGINE,
        )
    }

    private fun traced(outcome: TraceOutcome.Traced, account: Address, network: Network, tracedAt: Long?): TraceSection {
        val effects = outcome.effects
        fun named(address: Address): String {
            val checksum = Eip55.format(address)
            return outcome.names[address]?.let { "$it ($checksum)" } ?: checksum
        }
        fun amount(asset: TracedAsset, amount: UInt256): String = when (asset) {
            TracedAsset.Currency ->
                "${amount.formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}" + if (network.testnet) " (testnet)" else ""
            is TracedAsset.Fungible -> outcome.tokens[asset.token]?.let { "${amount.formatUnits(it.decimals)} ${it.symbol}" }
                ?: "${amount.toDecimal()} raw units of the token at ${Eip55.format(asset.token)}, not in the built-in token list"
            is TracedAsset.Nft -> (if (asset.multi) "${amount.toDecimal()} × " else "") + "#${asset.id.toDecimal()} of ${named(asset.collection)}"
        }
        val moves = effects.moves.map { move ->
            if (move.from == account) "Sends ${amount(move.asset, move.amount)} to ${named(move.to)}"
            else "Receives ${amount(move.asset, move.amount)} from ${named(move.from)}"
        }
        val rights = effects.rights.map { right(it, ::named, outcome) }
        val contracts = effects.contracts.map { contract ->
            named(contract.address) + if (outcome.unnamedDelegate(contract)) ", its code run by a delegate call: code Punicus cannot name" else ""
        }
        return TraceSection(
            block = outcome.blockNumber,
            provenAt = "Proven at block ${grouped(outcome.blockNumber)}, at the verified head: what it does now, not when it is mined.",
            moves = capped(moves, "moves"),
            rights = capped(rights, "rights"),
            contracts = capped(contracts, "contracts"),
            revert = effects.revert?.let { revert ->
                // The reason is the called contract's own text: escaped, cut, and said to be its words.
                revert.reason?.let { "It reverts. The contract's reason: \"${shown(it)}\"" } ?: "It reverts (${shown(revert.error)})."
            },
            simpleProvenAt = tracedAt?.let {
                "Proven at the verified head, traced ${ClearSignFormatter.utc(it)}: what it does now, not when it is mined."
            },
            unread = effects.unreadLogs.takeIf { it > 0 }?.let {
                "$it of its logs look like token events but are not their exact encoding, so they are not read."
            },
            warnings = capped(outcome.disagreements.map { disagreement(it, ::named, outcome) }, "warnings"),
            canRetry = true,
        )
    }

    private fun right(right: TracedRight, named: (Address) -> String, outcome: TraceOutcome.Traced): String = when (right) {
        is TracedRight.Allowance -> "Lets ${named(right.spender)} take " + allowance(right.amount, right.contract, outcome, UInt256.MAX)
        is TracedRight.OneNft -> "Lets ${named(right.spender)} take #${right.id.toDecimal()} of ${named(right.contract)}"
        is TracedRight.AllNfts -> "Lets ${named(right.spender)} take every NFT of ${named(right.contract)}"
        is TracedRight.Permit2 -> "Lets ${named(right.spender)} take " + allowance(right.amount, right.token, outcome, MAX_UINT160) +
            " through Permit2, until ${ClearSignFormatter.utc(right.expiration)}"
    }

    /** [asset] as a sentence starts with it: our own words capitalised, a symbol as it is spelled. */
    private fun amountless(asset: TracedAsset, named: (Address) -> String, outcome: TraceOutcome.Traced): String = when (asset) {
        TracedAsset.Currency -> "The network's currency"
        is TracedAsset.Fungible -> outcome.tokens[asset.token]?.symbol ?: "The token at ${Eip55.format(asset.token)}"
        is TracedAsset.Nft -> "NFT #${asset.id.toDecimal()} of ${named(asset.collection)}"
    }

    /** An allowance of [amount] of [token]: its most ([max]) said in words, as the descriptions and decoders do. */
    private fun allowance(amount: UInt256, token: Address, outcome: TraceOutcome.Traced, max: UInt256): String {
        val listed = outcome.tokens[token]
        val what = listed?.symbol ?: "the token at ${Eip55.format(token)}"
        return when {
            amount == max -> "an unlimited amount of $what"
            listed != null -> "up to ${amount.formatUnits(listed.decimals)} ${listed.symbol}"
            else -> "up to ${amount.toDecimal()} raw units of $what"
        }
    }

    /** [lines], at most [MAX_LINES] of them, and how many more there are where there are more. */
    private fun capped(lines: List<String>, what: String): List<String> =
        if (lines.size <= MAX_LINES) lines else lines.take(MAX_LINES) + "…and ${lines.size - MAX_LINES} more $what, not shown here."

    /** Text a contract chose, on one line: what draws nothing spelled out, cut to [MAX_REASON] characters. */
    private fun shown(text: String): String {
        val escaped = ShownText.escaped(text)
        return if (escaped.length <= MAX_REASON) escaped else escaped.take(MAX_REASON) + "…(cut)"
    }

    private val MAX_UINT160 = UInt256.fromBigEndian(ByteArray(20) { -1 })

    private fun disagreement(
        disagreement: TraceDisagreement,
        named: (Address) -> String,
        outcome: TraceOutcome.Traced,
    ): String = when (disagreement) {
        is TraceDisagreement.TokenNotNamed ->
            "It sends ${outcome.tokens[disagreement.token]?.symbol ?: named(disagreement.token)}, which this call does not name."
        is TraceDisagreement.OtherRecipient ->
            "${amountless(disagreement.asset, named, outcome)} ends up with " +
                "${named(disagreement.holder)}, an address this call does not show."
        is TraceDisagreement.RightNotFlagged ->
            "${right(disagreement.right, named, outcome)}: a right this call does not say it grants."
    }

    private fun why(reason: ReadUnavailable): String = when (reason) {
        ReadUnavailable.NO_ENGINE -> "no light client runs for this network on this device."
        ReadUnavailable.STALE_ANCHOR -> "the light client's checkpoint is too old to sync from."
        ReadUnavailable.UNPROVEN -> "no verified answer could be had this time."
        ReadUnavailable.SYNCING -> "the light client is still syncing."
        ReadUnavailable.REFUSED -> "the light client would not trace it at this block."
        ReadUnavailable.NETWORK_OFF -> "this network is turned off."
    }

    private const val NATIVE_DECIMALS = 18
}

/** A [TraceSection] under its heading: the effects, the warnings first and loud, or why it is not traced. */
@Composable
fun TracedEffects(section: TraceSection, onRetry: () -> Unit, modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
    Column(modifier) {
        Text(
            TraceText.HEADING,
            Modifier.padding(top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (section.warnings.isNotEmpty()) {
            Text(TraceText.DISAGREES, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            section.warnings.forEach { Text("• $it", Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.error) }
        }
        when {
            section.tracing -> Row(
                Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(TraceText.TRACING)
            }
            section.notTraced != null -> {
                Text(section.notTraced, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (section.canRetry) TextButton(onClick = onRetry) { Text("Try again") }
            }
            else -> SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    section.provenAt?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    section.revert?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    section.moves.forEach { Text(it) }
                    section.rights.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (section.nothingLogged) Text(TraceText.NOTHING_LOGGED)
                    section.unread?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (section.contracts.isNotEmpty()) {
                        Text(
                            "Contracts it reaches",
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        section.contracts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text(TraceText.LOGS_ONLY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
