package eth.serotine.ui.gnosispay

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.DelayTransferPlan
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.usecase.CallRefusal
import eth.serotine.domain.usecase.DelayQuote
import eth.serotine.domain.usecase.DelayQuoting
import eth.serotine.domain.usecase.DelayStep
import eth.serotine.domain.usecase.DelayTransferState
import eth.serotine.domain.usecase.DelayTransfersReading
import eth.serotine.domain.usecase.GnosisPayReading
import eth.serotine.domain.usecase.GnosisPaySending
import eth.serotine.domain.usecase.TokenHoldings
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.platform.formatDateTime

/** What the Gnosis Pay screen says, display-ready (docs/gnosis-pay.md). */
internal object GnosisPayTexts {

    fun eip55(address: Address): String = Eip55.format(address)

    /** A period of [seconds] as a sentence says it: "3 min", "30 min", "25 h", "1 h 30 min". */
    fun period(seconds: Long): String {
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600 + 59) / 60
        // Minutes rounded up, carried into the hours when they make a whole one.
        val (h, m) = if (minutes == 60L) hours + 1 to 0L else hours to minutes
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    fun waits(cooldown: Long, expiration: Long): String = if (expiration == 0L) {
        "A transfer waits ${period(cooldown)} in the Delay module, then can be executed at any time."
    } else {
        "A transfer waits ${period(cooldown)} in the Delay module, then can be executed for ${period(expiration)}. " +
            "After that it expires, and nothing moves."
    }

    fun checkedAt(block: Long): String = "Verified by the light client at block ${grouped(block)}"

    /** Why Send cannot be done here, and how to get there; null when it can. */
    fun sendReason(sending: GnosisPaySending): String? = when (sending) {
        GnosisPaySending.AVAILABLE -> null
        GnosisPaySending.RETIRED_MODULE ->
            "This account's Delay module runs the code Gnosis Pay retired after the exploit of June 2026. Punicus " +
                "sends nothing through it. Gnosis Pay's own app can move the account to the current module."
        GnosisPaySending.MEMBER_ON_ANOTHER_NETWORK ->
            "The key that controls this account is on this device, but not as an account on Gnosis Chain. Add it " +
                "on Gnosis Chain from its account's menu, then send from here."
        GnosisPaySending.MEMBER_WATCHED_HERE ->
            "The account that controls this one is watched on Gnosis Chain, while its key is held under another " +
                "network. Remove the watched entry, then add the held account on Gnosis Chain."
        GnosisPaySending.ACCOUNTS_UNREADABLE ->
            "The wallet's list of accounts cannot be read right now, so whether the controlling key is here " +
                "cannot be told. Try again in a moment."
        GnosisPaySending.NO_MEMBER_KEY ->
            "The key of the account that controls this Gnosis Pay account is not on this device. Import its " +
                "recovery phrase to send from here; until then the account is watched."
    }

    const val EXECUTE_NEEDS_MEMBER =
        "This transfer was queued by an account whose key is no longer on this device, so it cannot be " +
            "executed from here."

    fun queuedNote(reading: DelayTransfersReading?): String? = when (reading) {
        null -> null
        DelayTransfersReading.HistoryUnreadable ->
            "The record of what this wallet sent cannot be read right now, so its queued transfers are not listed."
        is DelayTransfersReading.Read -> reading.queueUnread?.let {
            "Where the queued transfers stand cannot be verified right now: ${reasonText(it)}"
        }
    }

    fun transferTitle(transfer: DelayTransfer, network: Network): String =
        "${amount(transfer.amount, transfer.asset, network)} to ${shortAddress(eip55(transfer.recipient))}"

    fun amount(amount: UInt256, asset: TransferAsset, network: Network): String = when (asset) {
        TransferAsset.Native -> "${amount.formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}"
        is TransferAsset.Erc20Token -> "${amount.formatUnits(asset.token.decimals)} ${asset.token.symbol}"
    }

    /** Where a queued transfer stands, after how its queueing ended. */
    fun status(state: DelayTransferState?, outcome: SentOutcome): String = when {
        outcome == SentOutcome.NotSeen && state is DelayTransferState.NotInQueue -> "Queueing: waiting for a block"
        state == null -> "Where it stands cannot be verified right now"
        else -> when (state) {
            is DelayTransferState.Waiting -> if (state.ahead > 0) {
                "Waits until ${formatDateTime(state.readyAt)}, behind ${entries(state.ahead)}"
            } else {
                "Waits until ${formatDateTime(state.readyAt)}"
            }
            is DelayTransferState.Ready ->
                "Ready to execute" + (state.expiresAt?.let { " until ${formatDateTime(it)}" } ?: "")
            is DelayTransferState.Behind -> "Ready, behind ${entries(state.ahead)} queued by someone else"
            is DelayTransferState.Expired -> "Expired at ${formatDateTime(state.expiresAt)}: nothing moved"
            DelayTransferState.NotInQueue -> "No longer in the queue: executed or skipped"
            is DelayTransferState.FurtherBack -> "Further back in the queue than the first ${state.searched} entries"
        }
    }

    /** Why a queued transfer cannot be executed yet. */
    fun whyNotYet(state: DelayTransferState?): String = when (state) {
        is DelayTransferState.Waiting -> "The Delay module holds it until ${formatDateTime(state.readyAt)}. " +
            "Execute is offered then."
        is DelayTransferState.Behind -> "${entries(state.ahead).replaceFirstChar { it.uppercase() }} queued before " +
            "it come first. Whoever queued them executes them, or they expire; Punicus never executes what it did " +
            "not queue."
        is DelayTransferState.FurtherBack -> "It is further back in the queue than Punicus reads."
        else -> "It cannot be executed now."
    }

    private fun entries(count: Int) = if (count == 1) "1 entry" else "$count entries"

    /** The Safe's assets to send: its currency, then each token it holds. */
    fun assets(network: Network, native: BalanceReading, holdings: TokenHoldings): List<AssetItem> {
        val nativeBalance = (native as? BalanceReading.Verified)?.wei?.toUInt256OrNull()
            ?.let { "${it.formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}" } ?: "Checking…"
        val tokens = holdings.holdings.mapNotNull { holding ->
            val verified = holding.reading as? TokenBalanceReading.Verified ?: return@mapNotNull null
            val units = UInt256.parse(verified.units.decimal)?.takeIf { !it.isZero } ?: return@mapNotNull null
            AssetItem(
                Eip55.format(holding.token.address),
                holding.token.symbol,
                "${units.formatUnits(holding.token.decimals)} ${holding.token.symbol}",
            )
        }
        return listOf(AssetItem(null, network.nativeSymbol, nativeBalance)) + tokens
    }

    /** The asset of [contract] (null: the network's currency) among [holdings]; null when it is none of them. */
    fun assetOf(contract: String?, holdings: TokenHoldings?): TransferAsset? {
        if (contract == null) return TransferAsset.Native
        val address = Address.parse(contract) ?: return null
        val holding = holdings?.holdings?.firstOrNull { it.token.address == address } ?: return null
        return TransferAsset.Erc20Token(holding.token)
    }

    const val CHECKING = "Its execution is being checked on chain."
    const val NO_ASSET = "Choose the asset to send."
    const val BAD_ADDRESS = "Enter the recipient's address: 0x and 40 hex digits. Gnosis Chain resolves no names."
    const val BAD_AMOUNT = "Enter an amount, in the asset's units."
    const val QUOTE_FAILED = "The transfer could not be checked on chain. Try again."
    const val SEND_FAILED = "Punicus could not finish sending it. Nothing new was signed; try again."

    fun planRefused(plan: DelayTransferPlan): String = when (plan) {
        is DelayTransferPlan.Planned -> ""
        DelayTransferPlan.ToZeroAddress -> "Nobody holds the zero address's key: what is sent there is lost for good."
        DelayTransferPlan.ToTokenContract -> "Sent to the token's own contract, it can never be moved out again."
        DelayTransferPlan.ToTheAccount ->
            "That is this account itself, its Delay or one of its modules. Only plain transfers go through the " +
                "Delay here: a call of the account's own contracts could change its settings."
        DelayTransferPlan.NothingToSend -> "There is nothing to send, and no fee is worth paying for it."
    }

    fun quoteRefused(quoting: DelayQuoting): String = when (quoting) {
        is DelayQuoting.Quoted -> ""
        is DelayQuoting.NotTheSafesDelay -> when (quoting.generation) {
            DelayGeneration.RETIRED ->
                "The account's Delay module now runs the retired code: nothing is sent through it."
            else -> "The Delay module is no longer this account's own, as verified now: nothing is sent through it."
        }
        is DelayQuoting.SafeInsufficient -> if (quoting.alreadyQueued.isZero) {
            "The account holds less than that. The transfer would be queued, wait, and then fail, its fees paid."
        } else {
            "The account holds less than that beside what transfers queued from here will move. It would be " +
                "queued, wait, and then fail, its fees paid."
        }
        is DelayQuoting.AlreadyQueued -> "The same transfer is queued already and waits in the Delay module. " +
            "Execute it, or let it expire, before queuing it again."
        DelayQuoting.TokenRefuses -> "The token refuses this transfer from the account: it answers false. The " +
            "Delay would spend its entry and move nothing."
        DelayQuoting.HistoryUnreadable ->
            "The record of what this wallet sent cannot be read right now, so what is already queued cannot be told."
        is DelayQuoting.NotQuoted -> refusalText(quoting.refusal)
        is DelayQuoting.NotReady -> whyNotYet(quoting.state)
    }

    private fun refusalText(refusal: CallRefusal): String = when (refusal) {
        CallRefusal.InsufficientFunds -> "The controlling account holds too little xDAI for the fee. It pays the " +
            "fees of both the queueing and the execution."
        is CallRefusal.Infeasible -> "It cannot succeed: ${refusal.detail}"
        is CallRefusal.Reverts -> "It reverts, run as a verified call: the Delay module refuses it. Nothing was signed."
        CallRefusal.Refused -> "The light client on this device cannot run it."
        CallRefusal.Syncing -> "The light client is still syncing. Try again once it has caught up."
        is CallRefusal.Unavailable -> "A figure it needs cannot be verified right now: ${reasonText(refusal.reason)}"
        is CallRefusal.EarlierMaybeSent ->
            "An earlier transaction from the controlling account may still be on its way. It is retried from its " +
                "status, never replaced."
    }

    fun reviewTitle(quote: DelayQuote, network: Network): String {
        val what = transferTitle(quote.transfer, network)
        return when (quote.step) {
            DelayStep.QUEUE -> "Queue $what"
            DelayStep.EXECUTE -> "Execute $what"
        }
    }

    fun reviewLines(
        quote: DelayQuote,
        found: GnosisPayReading.Found,
        member: Address,
        network: Network,
    ): List<Pair<String, String>> {
        val transfer = quote.transfer
        val maxFee = "${quote.call.maxFee.formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol} at most"
        val step = when (quote.step) {
            DelayStep.QUEUE -> "Queued now. It waits ${period(found.delay.cooldownSeconds)}, then needs a second " +
                "transaction to execute it" + if (found.delay.expirationSeconds == 0L) {
                    "."
                } else {
                    ", within ${period(found.delay.expirationSeconds)}."
                }
            DelayStep.EXECUTE -> "Executes the transfer: the account's funds move now."
        }
        return listOf(
            "From" to eip55(transfer.safe),
            "To" to eip55(transfer.recipient),
            "Amount" to amount(transfer.amount, transfer.asset, network),
            "Through" to "Delay module ${eip55(transfer.delay)}",
            "Signed by" to eip55(member),
            "Fee" to maxFee,
            "What happens" to step,
            "Account balance" to amount(quote.safeBalance, transfer.asset, network),
        )
    }

    fun unavailable(reason: ReadUnavailable): String =
        "Whether this is a Gnosis Pay account cannot be verified right now: ${reasonText(reason)}"

    private fun reasonText(reason: ReadUnavailable): String = when (reason) {
        ReadUnavailable.NO_ENGINE -> "there is no light client for Gnosis Chain on this device."
        ReadUnavailable.NETWORK_OFF -> "Gnosis Chain is off in this wallet."
        ReadUnavailable.STALE_ANCHOR -> "the light client refuses to sync until you allow it to catch up."
        ReadUnavailable.SYNCING -> "the light client is still syncing."
        ReadUnavailable.UNPROVEN -> "no peer served the proof this time. Punicus keeps checking."
        ReadUnavailable.REFUSED -> "the light client on this device cannot read it."
    }

    private const val NATIVE_DECIMALS = 18
}
