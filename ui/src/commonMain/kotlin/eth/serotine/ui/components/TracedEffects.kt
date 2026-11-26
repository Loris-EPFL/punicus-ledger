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
    val nothingLogged: Boolean get() = provenAt != null && moves.isEmpty() && rights.isEmpty() && revert == null
}

/** What the screens say of a verified trace, and how a [TraceOutcome] becomes a [TraceSection]. */
object TraceText {
    const val HEADING = "What it does now"
    const val TRACING = "Tracing it at the verified head…"
    const val DISAGREES = "The trace does not match what this call says it does. Check it carefully: it can " +
        "still be signed."
    const val NOTHING_LOGGED = "It moves and grants nothing its contracts log. What a contract does not log is " +
        "not seen."
    const val LOGS_ONLY = "Read from what the contracts log: a token that moves without logging it is not seen."
    const val NOT_TRACED_HERE = "Not traced: the light client on this device does not trace calls yet."
    const val NOT_IN_TIME = "Not traced within 30 seconds: the call reaches much state. Try again waits for " +
        "the trace still running."
    const val REVERTS_UNLIKE_RUN = "The trace reverts, while the verified run above did not: the chain moved " +
        "between the two. Quote it again before you send."

    /** [outcome] of [account]'s call on [network] as the screen shows it. */
    fun section(outcome: TraceOutcome, account: Address, network: Network): TraceSection = when (outcome) {
        is TraceOutcome.Traced -> traced(outcome, account, network)
        is TraceOutcome.Infeasible -> TraceSection(notTraced = "Not traced: it cannot run (${outcome.detail}).", canRetry = true)
        TraceOutcome.NotTraced -> TraceSection(notTraced = NOT_TRACED_HERE)
        TraceOutcome.NotInTime -> TraceSection(notTraced = NOT_IN_TIME, canRetry = true)
        is TraceOutcome.Unavailable -> TraceSection(notTraced = "Not traced: ${why(outcome.reason)}", canRetry = true)
    }

    private fun traced(outcome: TraceOutcome.Traced, account: Address, network: Network): TraceSection {
        val effects = outcome.effects
        fun named(address: Address): String {
            val checksum = Eip55.format(address)
            return outcome.names[address]?.let { "$it ($checksum)" } ?: checksum
        }
        fun amount(asset: TracedAsset, amount: UInt256): String = when (asset) {
            TracedAsset.Currency -> "${amount.formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}"
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
            provenAt = "Proven at block ${outcome.blockNumber}, at the verified head: what it does now, not when it is mined.",
            moves = moves,
            rights = rights,
            contracts = contracts,
            revert = effects.revert?.let { revert -> "It reverts" + (revert.reason?.let { ": \"$it\"" } ?: " (${revert.error})") },
            unread = effects.unreadLogs.takeIf { it > 0 }?.let {
                "$it of its logs look like token events but are not their exact encoding, so they are not read."
            },
            warnings = outcome.disagreements.map { disagreement(it, ::named, outcome) },
            canRetry = true,
        )
    }

    private fun right(right: TracedRight, named: (Address) -> String, outcome: TraceOutcome.Traced): String = when (right) {
        is TracedRight.Allowance -> "Lets ${named(right.spender)} take up to " +
            (outcome.tokens[right.contract]?.let { "${right.amount.formatUnits(it.decimals)} ${it.symbol}" }
                ?: "${right.amount.toDecimal()} raw units of the token at ${Eip55.format(right.contract)}")
        is TracedRight.OneNft -> "Lets ${named(right.spender)} take #${right.id.toDecimal()} of ${named(right.contract)}"
        is TracedRight.AllNfts -> "Lets ${named(right.spender)} take every NFT of ${named(right.contract)}"
        is TracedRight.Permit2 -> "Lets ${named(right.spender)} take up to " +
            (outcome.tokens[right.token]?.let { "${right.amount.formatUnits(it.decimals)} ${it.symbol}" }
                ?: "${right.amount.toDecimal()} raw units of the token at ${Eip55.format(right.token)}") +
            " through Permit2, until ${ClearSignFormatter.utc(right.expiration)}"
    }

    private fun amountless(asset: TracedAsset, named: (Address) -> String, outcome: TraceOutcome.Traced): String = when (asset) {
        TracedAsset.Currency -> "the network's currency"
        is TracedAsset.Fungible -> outcome.tokens[asset.token]?.symbol ?: "the token at ${Eip55.format(asset.token)}"
        is TracedAsset.Nft -> "#${asset.id.toDecimal()} of ${named(asset.collection)}"
    }

    private fun disagreement(
        disagreement: TraceDisagreement,
        named: (Address) -> String,
        outcome: TraceOutcome.Traced,
    ): String = when (disagreement) {
        is TraceDisagreement.TokenNotNamed ->
            "It sends ${outcome.tokens[disagreement.token]?.symbol ?: named(disagreement.token)}, which this call does not name."
        is TraceDisagreement.OtherRecipient ->
            "${amountless(disagreement.asset, named, outcome).replaceFirstChar { it.uppercase() }} ends up with " +
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
fun TracedEffects(section: TraceSection, onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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
