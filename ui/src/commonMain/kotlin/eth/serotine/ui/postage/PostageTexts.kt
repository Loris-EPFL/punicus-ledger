package eth.serotine.ui.postage

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.usecase.ApprovalQuoting
import eth.serotine.domain.usecase.BatchCall
import eth.serotine.domain.usecase.BatchQuote
import eth.serotine.domain.usecase.BatchRefusal
import eth.serotine.domain.usecase.TokenApprovalQuote
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.callRefusalText
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.swarm.SwarmTexts

/** The words of the postage screen (docs/swarm.md, *The four actions*; docs/wallet-ux.md, *Swarm nodes*). */
internal object PostageTexts {

    private const val BZZ_DECIMALS = 16
    private const val NATIVE_DECIMALS = 18
    private val network = Network.GNOSIS

    fun title(action: PostageAction, batch: String?): String = when (action) {
        PostageAction.BUY -> "Buy a postage batch"
        PostageAction.TOP_UP -> "Top up batch ${batch.orEmpty()}"
        PostageAction.DILUTE -> "Dilute batch ${batch.orEmpty()}"
    }

    fun node(label: String, address: Address) = "For $label, ${shortAddress(Eip55.format(address))}"

    fun explains(action: PostageAction): String = when (action) {
        PostageAction.BUY ->
            "The node owns the batch; the account chosen pays for it in xBZZ, and the fees in xDAI. The " +
                "amount per chunk is worked out from the time to live at the price verified when you review."
        PostageAction.TOP_UP ->
            "Anyone may top up a batch: the account chosen pays in xBZZ, worked out from the time to add at " +
                "the price verified when you review."
        PostageAction.DILUTE ->
            "Each step of depth doubles what the batch holds and halves its time left. No xBZZ moves."
    }

    const val TIME_TO_LIVE = "Time to live, in days"
    const val TIME_TO_ADD = "Days to add"
    const val NODE_SIGNS = "Signed by the node's own key, which pays the fee: only a batch's owner may dilute it."

    const val NO_PAYER = SwarmTexts.NO_PAYER
    const val NODE_KEY_NOT_HELD = SwarmTexts.NODE_KEY_NOT_HELD
    const val NOT_SWARM = "Only an account on Gnosis Chain that is no Safe can be a Swarm node."
    const val LIST_UNAVAILABLE = "The wallet's account list cannot be read right now."
    const val DEPTH_GONE = "The batch's depth changed since it was read: choose the depth again."
    const val BATCH_NOT_READ = "The batch is not verified yet: its depth is needed to dilute it."

    fun notTheNodes(owner: Address?) = if (owner == null) {
        SwarmTexts.GONE
    } else {
        "This batch is owned by ${Eip55.format(owner)}, not by this node."
    }

    const val EXPIRED = SwarmTexts.EXPIRED_ACTION

    fun daysError(text: String): String? {
        val days = text.trim().toLongOrNull()
        return when {
            text.isBlank() -> "Say for how many days."
            days == null || days <= 0 -> "A whole number of days, 1 or more."
            days > MAX_DAYS -> "At most ${grouped(MAX_DAYS)} days."
            else -> null
        }
    }

    /** The most days the form takes: ten years. */
    const val MAX_DAYS = 3_650L

    /** About how long [seconds] is, in days or hours. */
    fun about(seconds: Long?): String {
        if (seconds == null) return "Not known"
        val days = seconds / DAY
        return when {
            days >= 2 -> "About ${grouped(days)} days"
            seconds >= 2 * HOUR -> "About ${seconds / HOUR} hours"
            else -> "Less than two hours"
        }
    }

    private fun bzz(plur: UInt256) = exactAmount(plur, BZZ_DECIMALS, "BZZ")

    private fun fee(maxFee: UInt256, level: FeeLevel) =
        "${exactAmount(maxFee, NATIVE_DECIMALS, network.nativeSymbol)} at most, ${feeLevel(level)} fees"

    private fun feeLevel(level: FeeLevel): String = when (level) {
        FeeLevel.SLOW -> "slow"
        FeeLevel.NORMAL -> "normal"
        FeeLevel.FAST -> "fast"
    }

    private fun account(label: String?, address: Address): String =
        listOfNotNull(label, Eip55.format(address)).joinToString(", ")

    /** What the node's own key signing warns of (docs/swarm.md, *A key that Bee also uses*). */
    const val NODE_KEY_WARNING =
        "This is the node's own key. If Bee is running, stop it before you send: Bee signs with the same key, and " +
            "one of the two transactions can replace the other."

    /** What an immutable batch is, said when one is diluted. */
    const val IMMUTABLE_WARNING =
        "This batch is immutable: once full, it takes no more chunks, where a mutable one overwrites its oldest. " +
            "Diluting it gives it room for more, for half the time left with each step."

    const val APPROVAL_TITLE = "Approve xBZZ for the postage contract"

    /** The approval's review, step 1 of 2: what it lets the PostageStamp take, exactly. */
    fun approvalLines(quote: TokenApprovalQuote, total: UInt256, payer: String?): List<Pair<String, String>> =
        listOfNotNull(
            "Lets the postage contract take" to "${bzz(total)}, exactly what the batch costs; never more",
            "Postage contract" to Eip55.format(quote.spender),
            "From" to account(payer, quote.from),
            ("Resets first" to "The token needs its allowance set to zero first").takeIf { quote.resetsFirst },
            "Fee" to fee(quote.maxFee, quote.level),
            "Read at" to "Fees at block ${grouped(quote.feeBlock)}",
        )

    const val APPROVAL_NOTE =
        "The batch itself is step 2: it is quoted and shown once a block has this approval."

    fun position(step: Int) = "Step $step of 2: " + if (step == 1) "the approval" else "the batch"

    /** The call's review title. */
    fun callTitle(quote: BatchQuote): String = when (val call = quote.call) {
        is BatchCall.Purchase -> "Buy a postage batch"
        is BatchCall.TopUp -> "Top up batch ${short(call.id.hex)}"
        is BatchCall.Dilution -> "Dilute batch ${short(call.id.hex)} to depth ${call.depth}"
    }

    /** The call's review: what it does, what it costs, and the blocks its figures were read at. */
    fun callLines(quote: BatchQuote, payer: String?, node: String): List<Pair<String, String>> {
        val call = quote.call
        val fee = "Fee" to fee(quote.quote.maxFee, quote.quote.level)
        val read = "Read at" to "Price and batch at block ${grouped(quote.postageBlock)}, fees at block " +
            grouped(quote.quote.feeBlock)
        return when (call) {
            is BatchCall.Purchase -> listOf(
                "Owned by" to account(node, call.owner),
                "Paid by" to account(payer, call.buyer),
                "Holds" to SwarmTexts.capacity(call.depth),
                "Time to live" to "${about(quote.secondsBought)} at the current price",
                "Immutable" to if (call.immutable) {
                    "Yes: once full, it takes no more chunks"
                } else {
                    "No: once full, its oldest chunks are overwritten"
                },
                "Pays" to bzz(quote.total),
                "Batch id" to "0x" + call.id.hex,
                "Postage contract" to Eip55.format(call.stamp),
                fee,
                read,
            )
            is BatchCall.TopUp -> listOf(
                "Batch" to "0x" + call.id.hex,
                "Paid by" to account(payer, quote.quote.from),
                "Adds" to "${about(quote.secondsBought)} at the current price",
                "Time left after" to about(quote.secondsLeftAfter),
                "Pays" to bzz(quote.total),
                "Postage contract" to Eip55.format(call.stamp),
                fee,
                read,
            )
            is BatchCall.Dilution -> listOf(
                "Batch" to "0x" + call.id.hex,
                "Holds" to "${SwarmTexts.capacity(call.from)} → ${SwarmTexts.capacity(call.depth)}",
                "Time left" to "${about(quote.batch?.secondsLeft)} → ${about(quote.secondsLeftAfter)}",
                "Moves" to "No xBZZ",
                "Signed by" to account(node, quote.quote.from),
                fee,
                read,
            )
        }
    }

    /** Why nothing was quoted, for [refusal]. */
    fun refused(refusal: BatchRefusal): String = when (refusal) {
        BatchRefusal.NotPinned -> "No postage contract is pinned on Gnosis Chain in this release."
        BatchRefusal.Paused -> "The postage contract is paused: it takes no purchase, top-up or dilution for now."
        BatchRefusal.BzzChanged ->
            "xBZZ's contract was upgraded since this release pinned it, so nothing is approved for it until an " +
                "update pins the new one."
        BatchRefusal.DepthNotOffered -> "That depth is not offered."
        is BatchRefusal.BelowMinimum -> "The postage contract takes no batch with less than " +
            (refusal.minimumSeconds?.let { about(it).replaceFirstChar(Char::lowercase) + " left" } ?: "a day left") +
            " at the current price."
        is BatchRefusal.NotTheNodes -> when (val finding = refusal.finding) {
            is eth.serotine.domain.repository.BatchFinding.OthersOwner -> notTheNodes(finding.owner)
            else -> notTheNodes(null)
        }
        BatchRefusal.Expired -> EXPIRED
        BatchRefusal.TooMuch -> "That is more than the postage contract can count."
        BatchRefusal.NothingToAdd -> "A top-up adds something: say how many days."
        BatchRefusal.NoPrice -> "The postage contract's price is zero, so no time can be worked out from it."
        is BatchRefusal.InsufficientBzz -> "The account chosen does not hold ${bzz(refusal.total)} to pay with, " +
            "beside what its transactions not mined yet still move and its CoW orders may still take."
        is BatchRefusal.Unavailable -> "Not quoted: " + SwarmTexts.unavailable(refusal.reason)
        is BatchRefusal.ApprovalNotQuoted -> approvalRefused(refusal.quoting)
        is BatchRefusal.NotQuoted -> callRefusalText(refusal.refusal, network)
    }

    private fun approvalRefused(quoting: ApprovalQuoting): String = when (quoting) {
        ApprovalQuoting.InsufficientFunds -> "Not enough xDAI for the approval's fee."
        is ApprovalQuoting.Infeasible -> "The approval cannot be sent: ${quoting.detail}."
        is ApprovalQuoting.Reverts -> "The approval would fail on chain, so it was not quoted."
        ApprovalQuoting.NotAToken -> "xBZZ's contract does not answer as a token: nothing is approved."
        ApprovalQuoting.Refused -> "The light client cannot quote the approval here."
        ApprovalQuoting.Syncing -> "Waiting for the light client to sync before the approval can be quoted."
        is ApprovalQuoting.Unavailable -> "The approval is not quoted: " + SwarmTexts.unavailable(quoting.reason)
        is ApprovalQuoting.EarlierMaybeSent -> EARLIER_MAYBE_SENT
        is ApprovalQuoting.EarlierSwapPending -> SWAP_PENDING
        is ApprovalQuoting.ApprovalPending -> APPROVAL_ON_ITS_WAY
        is ApprovalQuoting.Quoted, is ApprovalQuoting.AlreadyAllowed -> QUOTE_FAILED
    }

    const val EARLIER_MAYBE_SENT =
        "An earlier transaction from this account may still be on its way: nothing new is sent from it until a " +
            "block has it, or three hours have passed."
    const val PULL_PENDING =
        "An earlier purchase or top-up from this account is on its way and would take the allowance this one " +
            "counts on. Wait until a block has it."
    const val SWAP_PENDING =
        "An earlier swap from this account that sells xBZZ is on its way. Wait until a block has it."
    const val APPROVAL_ON_ITS_WAY = "An approval of xBZZ from this account is on its way."
    const val AWAITING = "Approval sent. Waiting for a block to have it, then the batch is shown for you to sign."
    const val AWAIT_TIMED_OUT =
        "No block has the approval yet. It may still be on its way: open its status, or review again later."
    const val APPROVAL_FAILED_MAYBE =
        "A block has the approval, but the allowance still falls short: it may have failed. Check it, then sign again."
    const val QUOTE_FAILED = "Punicus could not quote this right now. Try again."
    const val SEND_FAILED = "Punicus could not finish this. Check the account's activity before you try again."
    const val NONCE_TAKEN =
        "Another transaction from this key was mined since the quote, not one sent from here: Bee may be running " +
            "with it. Nothing was signed; the quote was made again. Stop Bee, then sign."
    const val BATCH_CHANGED =
        "The batch or the postage contract changed since the quote (the node's Bee may have diluted it). Nothing " +
            "was signed; the quote was made again: check it, then sign."

    fun notChecked(reason: eth.serotine.domain.repository.ReadUnavailable) =
        "Nothing was signed: whether another transaction took this one's place cannot be verified right now. " +
            SwarmTexts.unavailable(reason)

    fun biometricReason(title: String) = "$title on Gnosis Chain"

    private fun short(hex: String): String = "0x" + hex.take(8) + "…" + hex.takeLast(4)

    fun shortBatch(hex: String): String = short(hex)

    private const val HOUR = 3_600L
    private const val DAY = 24 * HOUR
}
