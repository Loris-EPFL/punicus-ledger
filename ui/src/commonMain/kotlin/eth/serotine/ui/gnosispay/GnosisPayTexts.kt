package eth.serotine.ui.gnosispay

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.DelayTransferPlan
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.usecase.CallRefusal
import eth.serotine.domain.usecase.ControlNode
import eth.serotine.domain.usecase.DelayControl
import eth.serotine.domain.usecase.DelayQuote
import eth.serotine.domain.usecase.DelayQuoting
import eth.serotine.domain.usecase.DelaySkipQuote
import eth.serotine.domain.usecase.DelaySkipQuoting
import eth.serotine.domain.usecase.DelayStep
import eth.serotine.domain.usecase.DelayTransferState
import eth.serotine.domain.usecase.DelayTransfersReading
import eth.serotine.domain.usecase.GnosisPayReading
import eth.serotine.domain.usecase.GnosisPaySending
import eth.serotine.domain.usecase.HeldController
import eth.serotine.domain.usecase.TokenHoldings
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.displaySymbol
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

    /** Where the account's reading is from; [held]: an earlier one standing over a read that did not answer. */
    fun checkedAt(block: Long, held: Boolean = false): String = if (held) {
        "Last verified by the light client at block ${grouped(block)}. It could not be verified at the latest " +
            "block: it may have changed since."
    } else {
        "Verified by the light client at block ${grouped(block)}"
    }

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
        GnosisPaySending.WINDOW_TOO_SHORT ->
            "This account's Delay module gives a transfer too short a window to be executed in: an execution " +
                "sent once it is ready could not be mined before it expires. Punicus queues nothing through it."
        GnosisPaySending.CONTROL_UNREAD ->
            "Which keys control this account cannot all be verified right now, so whether one is on this device " +
                "cannot be told yet. Punicus keeps checking."
        GnosisPaySending.CONTROL_TOO_WIDE ->
            "This account is controlled through more accounts than Punicus reads for one " +
                "(${DelayControl.MAX_ADDRESSES}), and no key on this device was found among those read."
        GnosisPaySending.CO_SIGNERS_NEEDED ->
            "A key on this device controls this account only together with others: a Safe on its way needs more " +
                "than one signature. Punicus sends through a Delay only with a key that can act alone."
        GnosisPaySending.NO_MEMBER_KEY ->
            "No key that controls this Gnosis Pay account is on this device. Import the recovery phrase of one of " +
                "the keys listed here to send from here; until then the account is watched."
    }

    /**
     * What a member is, when it is more than a key: a Safe and how many of its owners sign, or what
     * was not followed or not verified; null for a key (or a placeholder), which the row says itself.
     */
    fun memberWhat(node: ControlNode?): String? = when (node) {
        is ControlNode.Safe -> "A Safe: ${node.threshold} of its ${node.owners.size} " +
            (if (node.owners.size == 1) "owner" else "owners") + if (node.threshold == 1) " signs" else " sign"
        is ControlNode.OtherContract -> "A contract that is no Safe: not followed"
        // A member has nothing above it: only its version keeps it from being followed.
        is ControlNode.NotFollowed -> "A Safe older than 1.3.0: not followed"
        is ControlNode.OverBudget ->
            "Not read: past the ${DelayControl.MAX_ADDRESSES} accounts Punicus reads for one account"
        // Never called a key before it is proven one (.claude/rules/ui.md).
        is ControlNode.Unread, null -> "Not verified yet"
        is ControlNode.Key, is ControlNode.Placeholder -> null
    }

    /**
     * The Safes a key goes through to the Delay, in the order the call runs: the Safe the key owns
     * first, the member last; null for a member itself.
     */
    fun through(via: List<Address>): String? = when (via.size) {
        0 -> null
        1 -> "Through Safe ${shortAddress(eip55(via.single()))}"
        else -> "Through Safes " + via.reversed().joinToString(" → ") { shortAddress(eip55(it)) }
    }

    /** Who signs a transfer: the key's name, and the Safes it goes through. */
    fun sender(held: HeldController): String =
        listOfNotNull(held.account.label, through(held.via)?.replaceFirstChar { it.lowercase() }).joinToString(", ")

    /** What the list of keys leaves out or holds: what is not read yet, held, or not followed; null when nothing. */
    fun controlNote(control: DelayControl?): String? {
        if (control == null) return null
        val notes = listOfNotNull(
            "Some of the accounts on the way to these keys are not verified yet. Punicus keeps checking."
                .takeIf { !control.complete },
            (
                "Some of the accounts on the way to these keys could not be verified at the latest block: they are " +
                    "shown as last verified, for up to an hour."
                ).takeIf { control.held },
            control.notFollowed.size.takeIf { it > 0 }?.let { count ->
                val what = if (count == 1) "1 signer is" else "$count signers are"
                "$what not followed: a contract that is no Safe (a passkey, for one), a Safe older than 1.3.0, " +
                    "a Safe more than ${DelayControl.MAX_SAFES} levels down, or an account past the " +
                    "${DelayControl.MAX_ADDRESSES} Punicus reads for one."
            },
        )
        return notes.joinToString(" ").ifEmpty { null }
    }

    const val EXECUTE_NEEDS_MEMBER =
        "This transfer was queued by an account whose key is no longer on this device, so it cannot be " +
            "executed from here."

    const val EXECUTE_UNVERIFIED =
        "Where this transfer stands in the Delay module cannot be verified right now, so it is not executed. " +
            "Punicus keeps checking."

    const val EXECUTING = "Its execution was sent from this device and waits for a block. A second one would " +
        "fail once the first is mined, its fee paid."

    const val EXECUTING_STATUS = "Executing: waiting for a block"

    const val EXECUTE_NOT_MINED = "Its queueing is not in a block yet. The cooldown starts once it is."

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
        // Clipped as every screen clips a symbol: the contract's own claim, as long as it likes.
        is TransferAsset.Erc20Token ->
            "${amount.formatUnits(asset.token.decimals)} ${displaySymbol(asset.token.symbol)}"
    }

    /**
     * Where a queued transfer stands, in a line: [state] in its queue, [outcome] its queueing's; [now],
     * the chain's time (unix seconds), gives a cooldown within the hour as the minutes and seconds left.
     */
    fun status(state: DelayTransferState?, outcome: SentOutcome, now: Long? = null): String = when {
        outcome == SentOutcome.NotSeen && state is DelayTransferState.NotInQueue -> "Queueing: waiting for a block"
        state == null -> "Where it stands cannot be verified right now"
        else -> when (state) {
            is DelayTransferState.Waiting -> {
                // Within the hour, on the chain's time: the minutes and seconds left, which the bar under
                // it drains with; past its end, until a verified head says so, that it comes shortly.
                val left = now?.let { state.readyAt - it }
                val waits = when {
                    left == null || left >= 3_600 -> "Waits until ${formatDateTime(state.readyAt)}"
                    left <= 0 -> EXECUTABLE_SHORTLY
                    else -> "Executable in ${left / 60}:${(left % 60).toString().padStart(2, '0')}"
                }
                if (state.ahead > 0) "$waits, behind ${entries(state.ahead)}" else waits
            }
            is DelayTransferState.Ready ->
                "Ready to execute" + (state.expiresAt?.let { " until ${formatDateTime(it)}" } ?: "")
            is DelayTransferState.Behind -> "Ready, behind ${entries(state.ahead)} queued before it"
            // Within the last minutes of its window too: an execution sent now could land too late.
            is DelayTransferState.Expired ->
                "Expired, or about to, at ${formatDateTime(state.expiresAt)}: nothing moved"
            DelayTransferState.NotInQueue -> "No longer in the queue: executed or skipped"
            is DelayTransferState.FurtherBack -> "Further back in the queue than the first ${state.searched} entries"
        }
    }

    /** Why a queued transfer cannot be executed yet. */
    fun whyNotYet(state: DelayTransferState?): String = when (state) {
        is DelayTransferState.Waiting -> "The Delay module holds it until ${formatDateTime(state.readyAt)}. " +
            "Execute is offered then."
        is DelayTransferState.Behind -> "${entries(state.ahead).replaceFirstChar { it.uppercase() }} queued before " +
            "it come first. One queued from this device is listed above, with its own Execute. Any other is " +
            "executed by whoever can, or skipped by anyone once expired (Skip expired entries, offered here " +
            "then); Punicus never executes an entry it did not queue here."
        is DelayTransferState.Expired -> "Its window to execute closes at ${formatDateTime(state.expiresAt)}, too " +
            "soon for a transaction sent now to be mined in time."
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
            val symbol = displaySymbol(holding.token.symbol)
            AssetItem(
                Eip55.format(holding.token.address),
                symbol,
                "${units.formatUnits(holding.token.decimals)} $symbol",
                warning = notBuiltIn(symbol).takeIf { holding.sharesBuiltInSymbol },
            )
        }
        return listOf(AssetItem(null, network.nativeSymbol, nativeBalance)) + tokens
    }

    /** What a token the user added says when it takes a built-in token's symbol. */
    fun notBuiltIn(symbol: String): String = "Not the built-in $symbol: another contract"

    /** The asset of [contract] (null: the network's currency) among [holdings]; null when it is none of them. */
    fun assetOf(contract: String?, holdings: TokenHoldings?): TransferAsset? {
        if (contract == null) return TransferAsset.Native
        val address = Address.parse(contract) ?: return null
        val holding = holdings?.holdings?.firstOrNull { it.token.address == address } ?: return null
        return TransferAsset.Erc20Token(holding.token)
    }

    const val CHECKING = "Its execution is being checked on chain."
    const val SKIP_CHECKING = "A skip of the expired entries is being checked on chain. Wait for it."
    const val OTHER_CHECKING = "Another transfer's execution is being checked on chain. Wait for it."
    const val NO_ASSET = "Choose the asset to send."
    const val BAD_ADDRESS = "Enter the recipient's address: 0x and 40 hex digits. Gnosis Chain resolves no names."
    const val BAD_CHECKSUM = "That address's capital letters do not match its checksum (EIP-55): a character may " +
        "be mistyped. Copy the address again, or enter it all in lower case only if you are sure of it."
    const val BAD_AMOUNT = "Enter an amount, in the asset's units."
    const val QUOTE_FAILED = "The transfer could not be checked on chain. Try again."

    /** The account's reading is an earlier one standing over a read that did not answer: nothing is planned on it. */
    const val NOT_VERIFIED_NOW = "This account could not be verified at the latest block, so nothing is sent on " +
        "what was verified before. Punicus keeps trying: try again in a moment."
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
        is DelayQuoting.SameCallExpired -> if (quoting.skippable) {
            "The same transfer is still in the Delay module's queue, expired. It stays there until it is skipped, " +
                "and it holds back every transfer queued after it. Skip expired entries, then review again."
        } else {
            "The same transfer is still in the Delay module's queue, too close to its expiration to be executed " +
                "in time. Once it has expired it can be skipped; review again then."
        }
        DelayQuoting.HeadExpired -> "The next entry in the Delay module's queue has expired. Until it is skipped, " +
            "nothing behind it can be executed: a transfer queued now would wait, expire and move nothing, its fees " +
            "paid. Skip expired entries, then review again."
        is DelayQuoting.WindowTooShort -> sendReason(GnosisPaySending.WINDOW_TOO_SHORT).orEmpty()
        is DelayQuoting.SafeCallReverts -> "The account's own transfer reverts, run as a verified call" +
            (quoting.blockNumber?.let { " at block ${grouped(it)}" } ?: "") + ": the recipient does not accept it. " +
            "It would be queued, wait, and then fail. Nothing was signed."
        is DelayQuoting.QueueTooLong -> "The Delay module's queue already holds ${quoting.searched} entries or more, " +
            "as deep as Punicus reads it: one queued now could not be found again to execute. Nothing was queued."
        is DelayQuoting.ExecutionOnItsWay -> EXECUTING
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
        is CallRefusal.Reverts -> "It reverts, run as a verified call: the Delay module refuses it, or a Safe on the " +
            "way to it does (an owner removed, a threshold raised, a guard). Nothing was signed."
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

    /** What the biometric prompt says: the step and the recipient in full, the last thing read before signing. */
    fun biometricReason(quote: DelayQuote, network: Network): String {
        val transfer = quote.transfer
        val what = "${amount(transfer.amount, transfer.asset, network)} to ${eip55(transfer.recipient)}"
        return when (quote.step) {
            DelayStep.QUEUE -> "Queue $what"
            DelayStep.EXECUTE -> "Execute $what"
        }
    }

    /** What a skip refused says, on the form it was offered on. */
    fun skipRefused(quoting: DelaySkipQuoting): String = when (quoting) {
        is DelaySkipQuoting.Quoted -> ""
        DelaySkipQuoting.NothingExpired -> "The Delay module's next entry has not expired, or was skipped meanwhile: " +
            "nothing to skip."
        is DelaySkipQuoting.NotTheSafesDelay -> quoteRefused(DelayQuoting.NotTheSafesDelay(quoting.generation))
        is DelaySkipQuoting.SkipOnItsWay -> "A skip of this Delay module's expired entries is on its way, not in a " +
            "block yet."
        DelaySkipQuoting.HistoryUnreadable -> quoteRefused(DelayQuoting.HistoryUnreadable)
        is DelaySkipQuoting.NotQuoted -> quoteRefused(DelayQuoting.NotQuoted(quoting.refusal))
    }

    const val BLOCKED_BY_EXPIRED = "An entry ahead of it in the Delay module's queue has expired and blocks it. " +
        "Skip expired entries, then execute it before it expires too."

    const val SKIP_NEEDS_KEY = "Skipping expired entries is a transaction of an account whose key is on this " +
        "device, on Gnosis Chain, which pays its fee. Add or import one."

    const val EXECUTABLE_SHORTLY = "Executable shortly: waiting for a block past its cooldown"

    const val SKIP_SENT = "The skip of the expired entries was sent. Once it is in a block, Review again."

    const val SKIP_TITLE = "Skip expired entries of the Delay module"

    const val SKIP_BIOMETRIC_REASON = "Skip the expired entries of this Gnosis Pay account's Delay module"

    /** The review of a skip of expired entries: what it does, and what it moves (nothing). */
    fun skipLines(quote: DelaySkipQuote, member: Address, network: Network): List<Pair<String, String>> {
        val symbol = network.nativeSymbol
        val call = quote.call
        val maxFee = "${call.maxFee.formatUnits(NATIVE_DECIMALS)} $symbol at most, ${feeLevel(call.level)} " +
            "(fees at block ${grouped(call.feeBlock)})"
        val entries = if (quote.waiting == 1L) "1 entry waits" else "${quote.waiting} entries wait"
        return listOf(
            "Account" to eip55(quote.safe),
            "Delay module" to eip55(quote.delay),
            "Queue" to "$entries; the next one expired. Every expired entry from the next on is skipped: it can " +
                "never be executed afterwards.",
            "What happens" to "The Delay module's skipExpired. It moves nothing: no funds leave the account.",
            "Signed by" to eip55(member),
            "Fee" to maxFee,
            "Signer's $symbol" to "${call.nativeBefore.formatUnits(NATIVE_DECIMALS)} $symbol now, at least " +
                "${call.nativeAfterAtLeast.formatUnits(NATIVE_DECIMALS)} $symbol after" +
                (call.balanceBlock?.let { " (block ${grouped(it)})" } ?: ""),
        ) + listOfNotNull(
            call.simulationBlock?.let { "Simulated" to "Run as a verified call at block ${grouped(it)}" },
        )
    }

    fun reviewLines(
        quote: DelayQuote,
        found: GnosisPayReading.Found,
        member: Address,
        network: Network,
        sharesBuiltInSymbol: Boolean = false,
    ): List<Pair<String, String>> {
        val transfer = quote.transfer
        val symbol = network.nativeSymbol
        val call = quote.call
        val maxFee = "${call.maxFee.formatUnits(NATIVE_DECIMALS)} $symbol at most, ${feeLevel(call.level)} " +
            "(fees at block ${grouped(call.feeBlock)})"
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
        ) + tokenLines(transfer.asset, sharesBuiltInSymbol) + listOf(
            "Through" to "Delay module ${eip55(transfer.delay)}",
        ) + listOfNotNull(
            // In the order the call runs: the Safe the signer owns first, the Delay's member last.
            quote.via.takeIf { it.isNotEmpty() }?.let { via ->
                "Through ${if (via.size == 1) "Safe" else "Safes"}" to via.reversed().joinToString(" → ") { eip55(it) } +
                    ": ${if (via.size == 1) "it executes" else "each executes"} the call as its one owner's transaction"
            },
        ) + listOf(
            "Signed by" to eip55(member),
            "Fee" to maxFee,
            "Signer's $symbol" to "${call.nativeBefore.formatUnits(NATIVE_DECIMALS)} $symbol now, at least " +
                "${call.nativeAfterAtLeast.formatUnits(NATIVE_DECIMALS)} $symbol after" +
                (call.balanceBlock?.let { " (block ${grouped(it)})" } ?: ""),
            "What happens" to step,
            "Account balance" to amount(quote.safeBalance, transfer.asset, network) +
                (quote.safeBalanceBlock?.let { " (block ${grouped(it)})" } ?: ""),
        ) + listOfNotNull(
            call.simulationBlock?.let { "Simulated" to "Run as a verified call at block ${grouped(it)}" },
        )
    }

    /** A token transfer's contract, and the warning when it shares a built-in token's symbol; none for xDAI. */
    private fun tokenLines(asset: TransferAsset, sharesBuiltInSymbol: Boolean): List<Pair<String, String>> {
        val token = (asset as? TransferAsset.Erc20Token)?.token ?: return emptyList()
        val contract = "Token contract" to eip55(token.address)
        return if (sharesBuiltInSymbol) {
            listOf(contract, "Warning" to notBuiltIn(displaySymbol(token.symbol)))
        } else {
            listOf(contract)
        }
    }

    private fun feeLevel(level: FeeLevel): String = when (level) {
        FeeLevel.SLOW -> "slow"
        FeeLevel.NORMAL -> "normal"
        FeeLevel.FAST -> "fast"
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
