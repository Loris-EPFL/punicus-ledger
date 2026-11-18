package eth.serotine.ui.gnosispay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.DelayTransferPlan
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.DelayQuote
import eth.serotine.domain.usecase.DelayQuoting
import eth.serotine.domain.usecase.DelayStep
import eth.serotine.domain.usecase.DelayTransferState
import eth.serotine.domain.usecase.DelayTransfersReading
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.GnosisPayReading
import eth.serotine.domain.usecase.GnosisPaySending
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveGnosisPay
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.QuoteDelayExecution
import eth.serotine.domain.usecase.QuoteDelayQueue
import eth.serotine.domain.usecase.CallQuote
import eth.serotine.domain.usecase.SendDelaySkip
import eth.serotine.domain.usecase.QuoteDelaySkip
import eth.serotine.domain.usecase.DelaySkipQuoting
import eth.serotine.domain.usecase.DelaySkipQuote
import eth.serotine.domain.usecase.QueuedDelayTransfer
import eth.serotine.domain.usecase.ReadDelayTransfers
import eth.serotine.domain.usecase.SendDelayCall
import eth.serotine.domain.usecase.TokenHoldings
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.ui.components.AddressInput
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.promptDeadline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Gnosis Pay account [safe]'s screen (docs/gnosis-pay.md): what its Delay lets this wallet do,
 * the transfers queued through it from here, read again every [pollInterval] while shown, and a
 * transfer's two steps, queueing and execution, each quoted, reviewed and signed by the member
 * whose key is held here. What is signed is the reviewed quote's transaction; the password leaves
 * the state the moment it is handed to the signer, and whenever the review is left. A send that a
 * peer took ends in [sent] with its transaction hash, which the transfer's status follows.
 *
 * A quote is never signed stale, as on Send: it is made again when the screen is shown after it
 * was not, and when it is older than [QUOTE_MAX_AGE] at the tap that would sign it ([timeSource]
 * tells); the biometric prompt gets what is left of that age as its deadline ([promptDeadline]).
 * Both steps are quoted at [FeeLevel.NORMAL], which the review names.
 */
class GnosisPayViewModel(
    private val safe: AccountId,
    observeAccount: ObserveAccount,
    observeGnosisPay: ObserveGnosisPay,
    observeNativeBalance: ObserveNativeBalance,
    observeTokenHoldings: ObserveTokenHoldings,
    private val readDelayTransfers: ReadDelayTransfers,
    private val quoteDelayQueue: QuoteDelayQueue,
    private val quoteDelayExecution: QuoteDelayExecution,
    private val sendDelayCall: SendDelayCall,
    private val quoteDelaySkip: QuoteDelaySkip,
    private val sendDelaySkip: SendDelaySkip,
    private val describeKeyStore: DescribeKeyStore,
    private val pollInterval: Duration = 15.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    /** Where the user is in a transfer, beside what the chain says. */
    private sealed interface Flow {
        /** The overview; [busy] the queued transfer whose execution is quoted, [error] why the last one was not. */
        data class Overview(val busy: String? = null, val error: String? = null) : Flow
        data class Compose(
            val asset: String?,
            val to: String,
            val amount: String,
            val error: String? = null,
            val quoting: Boolean = false,
            val skip: Boolean = false,
        ) : Flow

        /**
         * [quote], made at [quotedAt] for [member] (the key that sends it), under review; [form] the form it came from (a
         * queueing's), [hidden] once the screen stopped being shown after it was made, [requoting]
         * while it is made again, and [note] why it was.
         */
        data class Review(
            val quote: Reviewed,
            val member: Address,
            val unlock: UnlockStep,
            val quotedAt: TimeMark,
            val form: Compose? = null,
            val signing: Boolean = false,
            val error: String? = null,
            val hidden: Boolean = false,
            val requoting: Boolean = false,
            val note: String? = null,
        ) : Flow
    }

    /** What a review signs: a transfer's queueing or execution, or a skip of the Delay's expired entries. */
    private sealed interface Reviewed {
        val call: CallQuote

        data class Transfer(val quote: DelayQuote) : Reviewed {
            override val call: CallQuote get() = quote.call
        }

        data class Skip(val quote: DelaySkipQuote) : Reviewed {
            override val call: CallQuote get() = quote.call
        }
    }

    private val flowState = MutableStateFlow<Flow>(Flow.Overview())

    /**
     * Whether the screen is shown, and how many times it was hidden: a quote or a signature that
     * began before a hide it did not see lands as made while hidden, and is made again before
     * anything is signed (as on Send: the one-minute check alone would miss a device asleep).
     */
    private var shown = true
    private var hides = 0

    /**
     * The request the screen waits on: each quote takes the next number, and Back, Send or a new
     * quote moves it on, so that an answer arriving late for a step left is no one's.
     */
    private var request = 0L

    /** The transaction a send ended in, which the screen hands to the transfer's status; null until then. */
    private val sentHash = MutableStateFlow<String?>(null)

    /** The hash of the transaction just sent, for the status screen; null until one is. */
    val sent: StateFlow<String?> = sentHash

    /** Moved on by every send, so that the queued transfers are read again at once. */
    private val sends = MutableStateFlow(0)

    private var latest: GnosisPayReading.Found? = null
    private var latestHoldings: TokenHoldings? = null
    private var latestQueued: List<QueuedDelayTransfer> = emptyList()

    /** The account as a Gnosis Pay account, read once for everything this screen shows. */
    private val reading = observeGnosisPay(safe).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

    /** The transfers queued from here, read again every [pollInterval] for the Delay the account has now. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val queued = reading
        .map { (it as? GnosisPayReading.Found)?.delay?.address }
        .distinctUntilChanged()
        .flatMapLatest { module ->
            if (module == null) {
                flowOf(null)
            } else {
                flow<DelayTransfersReading?> {
                    while (true) {
                        val seen = sends.value
                        // A read that throws is no answer this time; the next poll asks again.
                        safely { readDelayTransfers(safe, module) }?.let { emit(it) }
                        // The next poll, or a send since this read, whichever comes first; the list
                        // stays as read meanwhile.
                        withTimeoutOrNull(pollInterval) { sends.first { it != seen } }
                    }
                }.onStart { emit(null) }
            }
        }

    private val chain = combine(
        reading,
        observeNativeBalance(safe.network, safe.address),
        observeTokenHoldings(safe.network, safe.address),
        queued,
    ) { reading, native, holdings, transfers -> Chain(reading, native, holdings, transfers) }

    private data class Chain(
        val reading: GnosisPayReading,
        val native: BalanceReading,
        val holdings: TokenHoldings,
        val transfers: DelayTransfersReading?,
    )

    val uiState: StateFlow<GnosisPayUiState> = combine(observeAccount(safe), chain, flowState) { lookup, chain, step ->
        // What the intents act on is this reading's, or nothing: never one an earlier reading left.
        latest = null
        latestHoldings = null
        latestQueued = emptyList()
        val account = when (lookup) {
            AccountLookup.NotInList -> return@combine GnosisPayUiState.Gone
            AccountLookup.ListUnavailable -> return@combine GnosisPayUiState.Unavailable(LIST_UNREADABLE)
            is AccountLookup.Found -> lookup.account
        }
        val found = when (val reading = chain.reading) {
            GnosisPayReading.Checking -> return@combine GnosisPayUiState.Loading
            GnosisPayReading.NotGnosisPay -> return@combine GnosisPayUiState.Unavailable(NOT_GNOSIS_PAY)
            is GnosisPayReading.Unavailable -> return@combine GnosisPayUiState.Unavailable(unavailable(reading.reason))
            is GnosisPayReading.Found -> reading
        }
        latest = found
        latestHoldings = chain.holdings
        // Names in the wallet, for the members and keys shown: any network's entry names the address.
        val labels = (found.controllersHeld.map { it.account } + found.keysHeldElsewhere + found.keysCoSigning)
            .associate { it.address to it.label }
        latestQueued = (chain.transfers as? DelayTransfersReading.Read)?.transfers.orEmpty()
        GnosisPayUiState.Ready(
            label = account.label,
            address = GnosisPayTexts.eip55(safe.address),
            delay = GnosisPayTexts.eip55(found.delay.address),
            waits = GnosisPayTexts.waits(found.delay.cooldownSeconds, found.delay.expirationSeconds),
            checkedAt = GnosisPayTexts.checkedAt(found.blockNumber, found.held),
            members = found.delay.members.map { member ->
                val node = found.control?.members?.firstOrNull { it.address == member }
                MemberRow(
                    GnosisPayTexts.eip55(member),
                    labels[member],
                    found.controllersHeld.any { it.account.address == member || it.via.firstOrNull() == member },
                    GnosisPayTexts.memberWhat(node),
                )
            },
            keys = found.control?.controllers.orEmpty().map { controller ->
                KeyRow(
                    GnosisPayTexts.eip55(controller.key),
                    labels[controller.key],
                    // Held here under any network's entry, as Send's reason says of one held elsewhere.
                    heldHere = found.controllersHeld.any { it.account.address == controller.key } ||
                        found.keysCoSigning.any { it.address == controller.key } ||
                        found.keysHeldElsewhere.any { it.address == controller.key },
                    through = GnosisPayTexts.through(controller.via),
                    alone = controller.alone,
                )
            },
            controlNote = GnosisPayTexts.controlNote(found.control),
            send = ActionItem(found.sending == GnosisPaySending.AVAILABLE, GnosisPayTexts.sendReason(found.sending)),
            queued = latestQueued.map { queuedRow(it, found, (step as? Flow.Overview)?.busy) },
            queuedNote = GnosisPayTexts.queuedNote(chain.transfers),
            step = stepOf(step, found, chain),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GnosisPayUiState.Loading)

    private fun queuedRow(queued: QueuedDelayTransfer, found: GnosisPayReading.Found, busy: String?): QueuedRow {
        // Anyone may execute an entry: the key that queued it, held here, needs no control now.
        val held = found.keysHere.any { it.address == queued.member }
        val ready = queued.state is DelayTransferState.Ready
        // Shown greyed out whenever it cannot be done, saying why (owner ruling, 2026-10-18, #55).
        val execute = when {
            queued.executing != null -> ActionItem(false, GnosisPayTexts.EXECUTING)
            queued.state == null -> ActionItem(false, GnosisPayTexts.EXECUTE_UNVERIFIED)
            queued.state is DelayTransferState.NotInQueue -> ActionItem(false, GnosisPayTexts.EXECUTE_NOT_MINED)
            queued.state is DelayTransferState.Expired -> ActionItem(false, GnosisPayTexts.whyNotYet(queued.state))
            !held -> ActionItem(false, GnosisPayTexts.EXECUTE_NEEDS_MEMBER)
            found.sending == GnosisPaySending.RETIRED_MODULE ->
                ActionItem(false, GnosisPayTexts.sendReason(found.sending))
            !ready -> ActionItem(false, GnosisPayTexts.whyNotYet(queued.state))
            else -> ActionItem(true, null)
        }.let { action ->
            // While its execution is being quoted: greyed out, saying so.
            val checking = action.enabled && busy == queued.queuedBy
            if (checking) ActionItem(false, GnosisPayTexts.CHECKING) else action
        }
        // The Delay's next entry expired, this one or one ahead: its skip is offered here, by any key
        // held on the network. One ahead blocks this one, which says so; this one expired says that.
        val skippable = queued.expiredHead && queued.executing == null
        val blocked = skippable && queued.state !is DelayTransferState.Expired
        val skip = when {
            !skippable -> null
            found.keysHere.isEmpty() -> ActionItem(false, GnosisPayTexts.SKIP_NEEDS_KEY)
            // Anything quoted from the overview, this skip or an Execute, holds it back meanwhile.
            busy != null -> ActionItem(false, GnosisPayTexts.CHECKING)
            else -> ActionItem(true, null)
        }
        return QueuedRow(
            id = queued.queuedBy,
            title = GnosisPayTexts.transferTitle(queued.transfer, safe.network),
            status = when {
                queued.executing != null -> GnosisPayTexts.EXECUTING_STATUS
                blocked -> GnosisPayTexts.BLOCKED_BY_EXPIRED
                else -> GnosisPayTexts.status(queued.state, queued.queueOutcome)
            },
            execute = if (blocked) ActionItem(false, GnosisPayTexts.BLOCKED_BY_EXPIRED) else execute,
            skip = skip,
        )
    }

    private fun stepOf(step: Flow, found: GnosisPayReading.Found, chain: Chain): GnosisPayStep = when (step) {
        is Flow.Overview -> GnosisPayStep.Overview(error = step.error)
        is Flow.Compose -> GnosisPayStep.Compose(
            assets = GnosisPayTexts.assets(safe.network, chain.native, chain.holdings),
            asset = step.asset,
            to = step.to,
            amount = step.amount,
            sender = found.controllersHeld.firstOrNull()?.let { GnosisPayTexts.sender(it) } ?: "",
            error = step.error,
            quoting = step.quoting,
            skip = step.skip,
        )
        is Flow.Review -> GnosisPayStep.Review(
            title = when (val reviewed = step.quote) {
                is Reviewed.Transfer -> GnosisPayTexts.reviewTitle(reviewed.quote, safe.network)
                is Reviewed.Skip -> GnosisPayTexts.SKIP_TITLE
            },
            lines = when (val reviewed = step.quote) {
                is Reviewed.Transfer -> GnosisPayTexts.reviewLines(
                    reviewed.quote,
                    found,
                    step.member,
                    safe.network,
                    sharesBuiltInSymbol = sharesBuiltInSymbol(reviewed.quote.transfer.asset, chain.holdings),
                )
                is Reviewed.Skip -> GnosisPayTexts.skipLines(reviewed.quote, step.member, safe.network)
            },
            fee = step.quote.call.level,
            unlock = step.unlock,
            signing = step.signing || step.requoting,
            error = step.error,
            note = step.note,
            checking = step.requoting,
        )
    }

    /** Whether [asset] is a token the user added that takes a built-in token's symbol, among [holdings]. */
    private fun sharesBuiltInSymbol(asset: TransferAsset, holdings: TokenHoldings): Boolean {
        val token = (asset as? TransferAsset.Erc20Token)?.token ?: return false
        return holdings.holdings.any { it.token.address == token.address && it.sharesBuiltInSymbol }
    }

    /** Send: the transfer form, when a member's key is held here. */
    fun startSend() {
        val found = latest ?: return
        if (found.sending != GnosisPaySending.AVAILABLE) return
        request++
        flowState.value = Flow.Compose(asset = null, to = "", amount = "")
    }

    fun onAssetChange(contract: String?) = editCompose { it.copy(asset = contract, error = null) }

    fun onToChange(text: String) = editCompose { it.copy(to = text.trim(), error = null) }

    fun onAmountChange(text: String) = editCompose { it.copy(amount = text.trim(), error = null) }

    private fun editCompose(edit: (Flow.Compose) -> Flow.Compose) {
        val current = flowState.value as? Flow.Compose ?: return
        if (current.quoting) return
        flowState.value = edit(current)
    }

    /** Review: the transfer planned from the form and its queueing quoted on chain. */
    fun review(fee: FeeLevel = FeeLevel.NORMAL) {
        val compose = flowState.value as? Flow.Compose ?: return
        val found = latest ?: return
        if (compose.quoting) return
        // The Safe's modules, which no transfer may go to, and the Delay's periods are an earlier
        // answer's while a read of them did not answer: nothing is planned on them.
        if (found.held) return showCompose(GnosisPayTexts.NOT_VERIFIED_NOW)
        // Control can change while the form is open: said on the form, never a tap that does nothing.
        val sender = found.controllersHeld.firstOrNull()
            ?: return showCompose(GnosisPayTexts.sendReason(found.sending) ?: GnosisPayTexts.QUOTE_FAILED)
        val member = sender.account.address
        val asset = GnosisPayTexts.assetOf(compose.asset, latestHoldings) ?: return showCompose(GnosisPayTexts.NO_ASSET)
        val to = when (val input = Eip55.parse(compose.to)) {
            is AddressInput.Valid -> input.address
            AddressInput.NotAnAddress -> return showCompose(GnosisPayTexts.BAD_ADDRESS)
            // A typo in a mixed-case address: never sent to on the chance it was meant.
            AddressInput.ChecksumMismatch -> return showCompose(GnosisPayTexts.BAD_CHECKSUM)
        }
        val decimals = (asset as? TransferAsset.Erc20Token)?.token?.decimals ?: NATIVE_DECIMALS
        val amount = UInt256.parseUnits(compose.amount, decimals) ?: return showCompose(GnosisPayTexts.BAD_AMOUNT)
        val modules = found.safeModules
        val plan = DelayTransfer.of(safe.address, found.delay.address, modules, asset, to, amount)
        val transfer = when (plan) {
            is DelayTransferPlan.Planned -> plan.transfer
            else -> return showCompose(GnosisPayTexts.planRefused(plan))
        }
        flowState.value = compose.copy(quoting = true, error = null)
        val mine = ++request
        viewModelScope.launch {
            val quotedAt = timeSource.markNow()
            val hidesAtQuote = hides
            val quoting = safely { quoteDelayQueue(member, transfer, fee, via = sender.via) }
            val unlock = if (quoting is DelayQuoting.Quoted) unlockStep() else UnlockStep()
            // Left meanwhile (Back, a new Send), the unlock's check included: the answer is no one's.
            if (request != mine || flowState.value !is Flow.Compose) return@launch
            if (quoting is DelayQuoting.Quoted) {
                val hidden = hides != hidesAtQuote || !shown
                val reviewed = Reviewed.Transfer(quoting.quote)
                val review = Flow.Review(reviewed, member, unlock, quotedAt, form = compose, hidden = hidden)
                return@launch landed(review)
            }
            flowState.value = when (quoting) {
                null -> compose.copy(quoting = false, error = GnosisPayTexts.QUOTE_FAILED)
                // An expired entry ahead blocks every transfer behind it: its skip is offered on the form.
                DelayQuoting.HeadExpired ->
                    compose.copy(quoting = false, error = GnosisPayTexts.quoteRefused(quoting), skip = true)
                is DelayQuoting.SameCallExpired ->
                    compose.copy(
                        quoting = false,
                        error = GnosisPayTexts.quoteRefused(quoting),
                        skip = quoting.skippable,
                    )
                else -> compose.copy(quoting = false, error = GnosisPayTexts.quoteRefused(quoting))
            }
        }
    }

    /**
     * Skip expired entries, offered on the form when an expired entry blocks the Delay's queue:
     * `skipExpired()` quoted from a key held here on the account's network (it moves nothing, so any
     * key may send it; a controlling key first), then reviewed and signed like a queueing.
     */
    fun skipExpired(fee: FeeLevel = FeeLevel.NORMAL) {
        val found = latest ?: return
        val from = flowState.value
        val compose = from as? Flow.Compose
        when (from) {
            is Flow.Compose -> if (from.quoting || !from.skip) return
            // From a queued transfer's row, while an expired entry blocks it.
            is Flow.Overview -> if (from.busy != null || latestQueued.none { it.expiredHead }) return
            is Flow.Review -> return
        }
        val sender = (found.controllersHeld.map { it.account } + found.keysHere).firstOrNull()?.address
        if (sender == null) {
            if (compose != null) showCompose(GnosisPayTexts.SKIP_NEEDS_KEY)
            return
        }
        flowState.value = compose?.copy(quoting = true, error = null) ?: Flow.Overview(busy = SKIP_BUSY)
        val mine = ++request
        viewModelScope.launch {
            val quotedAt = timeSource.markNow()
            val hidesAtQuote = hides
            val quoting = safely { quoteDelaySkip(sender, safe.address, found.delay.address, fee) }
            val unlock = if (quoting is DelaySkipQuoting.Quoted) unlockStep() else UnlockStep()
            if (request != mine || flowState.value::class != from::class) return@launch
            if (quoting is DelaySkipQuoting.Quoted) {
                val hidden = hides != hidesAtQuote || !shown
                val form = compose?.copy(error = null, skip = false)
                return@launch landed(
                    Flow.Review(Reviewed.Skip(quoting.quote), sender, unlock, quotedAt, form = form, hidden = hidden),
                )
            }
            val why = if (quoting == null) GnosisPayTexts.QUOTE_FAILED else GnosisPayTexts.skipRefused(quoting)
            flowState.value = when {
                compose == null -> Flow.Overview(error = why)
                quoting == DelaySkipQuoting.NothingExpired -> compose.copy(quoting = false, error = why, skip = false)
                else -> compose.copy(quoting = false, error = why)
            }
        }
    }

    /**
     * Execute the queued transfer [id] (its queueing's hash): its execution quoted and shown for
     * review, once its row offers it (a member's key held here, the current module, ready); a quote
     * refused says why on the overview.
     */
    fun execute(id: String, fee: FeeLevel = FeeLevel.NORMAL) {
        val found = latest ?: return
        val overview = flowState.value as? Flow.Overview ?: return
        if (overview.busy != null) return
        val queued = latestQueued.firstOrNull { it.queuedBy == id } ?: return
        if (found.keysHere.none { it.address == queued.member }) return
        if (found.sending == GnosisPaySending.RETIRED_MODULE || queued.state !is DelayTransferState.Ready) return
        flowState.value = Flow.Overview(busy = id)
        val mine = ++request
        viewModelScope.launch {
            val quotedAt = timeSource.markNow()
            val hidesAtQuote = hides
            val quoting = safely { quoteDelayExecution(queued.member, queued.transfer, fee) }
            val unlock = if (quoting is DelayQuoting.Quoted) unlockStep() else UnlockStep()
            if (request != mine || flowState.value !is Flow.Overview) return@launch
            if (quoting is DelayQuoting.Quoted) {
                val hidden = hides != hidesAtQuote || !shown
                return@launch landed(
                    Flow.Review(Reviewed.Transfer(quoting.quote), queued.member, unlock, quotedAt, hidden = hidden),
                )
            }
            flowState.value = when (quoting) {
                null -> Flow.Overview(error = GnosisPayTexts.QUOTE_FAILED)
                else -> Flow.Overview(error = GnosisPayTexts.quoteRefused(quoting))
            }
        }
    }

    fun onPasswordChange(text: String) {
        val review = flowState.value as? Flow.Review ?: return
        if (review.signing || review.requoting) return
        flowState.value = review.copy(unlock = review.unlock.copy(password = WalletPassword(text), error = null))
    }

    fun sign() {
        val review = flowState.value as? Flow.Review ?: return
        if (review.signing || review.requoting || review.unlock.password.text.isEmpty() || requoted(review)) return
        send(review, Unlock.WithPassword(review.unlock.password))
    }

    fun signWithBiometrics() {
        val review = flowState.value as? Flow.Review ?: return
        if (review.signing || review.requoting || !review.unlock.biometrics || requoted(review)) return
        // The prompt gets what is left of the quote's age: past it, nothing is signed, and it is made again.
        val notAfter = promptDeadline(review.quotedAt) ?: return requote(review, PROMPT_TOO_LATE)
        // The recipient in full: the prompt is the last thing read before the key signs.
        val reason = when (val reviewed = review.quote) {
            is Reviewed.Transfer -> GnosisPayTexts.biometricReason(reviewed.quote, safe.network)
            is Reviewed.Skip -> GnosisPayTexts.SKIP_BIOMETRIC_REASON
        }
        send(review, Unlock.WithBiometrics(reason, notAfter))
    }

    /** Whether [review]'s quote is too old to sign, and so is being made again instead. */
    private fun requoted(review: Flow.Review): Boolean {
        // Quoted before the screen was last hidden: never signed as it was.
        if (review.hidden) {
            requote(review, note = null)
            return true
        }
        if (review.quotedAt.elapsedNow() <= QUOTE_MAX_AGE) return false
        requote(review, STALE_QUOTE)
        return true
    }

    /**
     * [review]'s transfer quoted again, its step and fee level as they were, the password typed
     * kept; [note] what the review then says. A refusal ends the review where it came from, saying why.
     */
    private fun requote(review: Flow.Review, note: String?) {
        flowState.value = review.copy(requoting = true, error = null, note = null)
        val mine = ++request
        viewModelScope.launch {
            val quotedAt = timeSource.markNow()
            val hidesAtQuote = hides
            val level = review.quote.call.level
            // The same call quoted again: a transfer's step, or the skip; null when it was not.
            val quoted: Reviewed?
            val why: String?
            when (val reviewed = review.quote) {
                is Reviewed.Transfer -> {
                    val quote = reviewed.quote
                    val quoting = safely {
                        when (quote.step) {
                            DelayStep.QUEUE -> quoteDelayQueue(review.member, quote.transfer, level, via = quote.via)
                            DelayStep.EXECUTE -> quoteDelayExecution(review.member, quote.transfer, level)
                        }
                    }
                    quoted = (quoting as? DelayQuoting.Quoted)?.let { Reviewed.Transfer(it.quote) }
                    why = when (quoting) {
                        is DelayQuoting.Quoted -> null
                        null -> GnosisPayTexts.QUOTE_FAILED
                        else -> GnosisPayTexts.quoteRefused(quoting)
                    }
                }
                is Reviewed.Skip -> {
                    val quote = reviewed.quote
                    val quoting = safely { quoteDelaySkip(review.member, quote.safe, quote.delay, level) }
                    quoted = (quoting as? DelaySkipQuoting.Quoted)?.let { Reviewed.Skip(it.quote) }
                    why = when (quoting) {
                        is DelaySkipQuoting.Quoted -> null
                        null -> GnosisPayTexts.QUOTE_FAILED
                        else -> GnosisPayTexts.skipRefused(quoting)
                    }
                }
            }
            val current = flowState.value as? Flow.Review ?: return@launch
            if (request != mine) return@launch
            if (quoted != null) {
                return@launch landed(
                    current.copy(
                        quote = quoted,
                        quotedAt = quotedAt,
                        hidden = hides != hidesAtQuote || !shown,
                        requoting = false,
                        note = note,
                    ),
                )
            }
            // A skip refused other than for nothing left to skip keeps its button on the form.
            val nothingLeft = GnosisPayTexts.skipRefused(DelaySkipQuoting.NothingExpired)
            val keepSkip = current.quote is Reviewed.Skip && why != nothingLeft
            flowState.value = current.form?.copy(quoting = false, error = why, skip = keepSkip)
                ?: Flow.Overview(error = why)
        }
    }

    private fun send(review: Flow.Review, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val signing = review.copy(signing = true, unlock = review.unlock.copy(password = WalletPassword.EMPTY))
        flowState.value = signing
        val hidesAtSign = hides
        viewModelScope.launch {
            val sending = safely {
                when (val reviewed = review.quote) {
                    is Reviewed.Transfer -> sendDelayCall(reviewed.quote, unlock)
                    is Reviewed.Skip -> sendDelaySkip(reviewed.quote, unlock)
                }
            }
            // Hidden while it signed: a review it leaves open is made again before the next signature.
            val hiddenMeanwhile = hides != hidesAtSign || !shown
            // Whatever it became, the queued transfers are read again now rather than at the next poll.
            sends.value++
            val current = flowState.value as? Flow.Review ?: return@launch
            when (sending) {
                // A skip goes back to the form it was offered on, to review the transfer again once it is mined.
                is TransferSending.Sent -> if (review.quote is Reviewed.Skip && review.form != null) {
                    flowState.value = review.form.copy(quoting = false, error = GnosisPayTexts.SKIP_SENT)
                } else {
                    flowState.value = Flow.Overview()
                    sentHash.value = sending.hash
                }
                is TransferSending.NotSigned -> landed(
                    current.copy(
                        signing = false,
                        hidden = current.hidden || hiddenMeanwhile,
                        unlock = current.unlock.copy(
                            error = notSignedText(sending.signing, GnosisPayTexts.SEND_FAILED),
                            biometrics = current.unlock.biometrics && biometricsStillOffered(sending.signing),
                        ),
                    ),
                )
                // Maybe taken: its record is what its status retries from, byte for byte.
                is TransferSending.NotSent -> if (sending.transfer.maybeSent) {
                    flowState.value = Flow.Overview()
                    sentHash.value = sending.transfer.hash
                } else {
                    val what = when (val reviewed = review.quote) {
                        is Reviewed.Skip -> "skip"
                        is Reviewed.Transfer -> if (reviewed.quote.step == DelayStep.QUEUE) "queueing" else "execution"
                    }
                    val why = notSentItem(sending.broadcast, false, what).message
                    landed(current.copy(signing = false, error = why, hidden = current.hidden || hiddenMeanwhile))
                }
                is TransferSending.RejectionUnverified -> {
                    flowState.value = Flow.Overview()
                    sentHash.value = sending.transfer.hash
                }
                null -> landed(
                    current.copy(
                        signing = false,
                        error = GnosisPayTexts.SEND_FAILED,
                        hidden = current.hidden || hiddenMeanwhile,
                    ),
                )
            }
        }
    }

    /** The status was opened for the transaction sent: nothing more to hand over. */
    fun onSentShown() {
        sentHash.value = null
    }

    /** Back from a step: the review to the overview, the form to the overview; the password dropped. */
    fun back(): Boolean {
        val current = flowState.value
        if (current is Flow.Review && current.signing) return true
        if (current is Flow.Overview) return false
        request++
        flowState.value = Flow.Overview()
        return true
    }

    /**
     * The screen is no longer shown: the password typed leaves the state, and the quote under review
     * is made again before anything is signed.
     */
    fun onHidden() {
        shown = false
        hides++
        val review = flowState.value as? Flow.Review ?: return
        if (review.signing) return
        flowState.value = review.copy(unlock = review.unlock.copy(password = WalletPassword.EMPTY), hidden = true)
    }

    /** The screen is shown again: a quote made before it was hidden is made again now. */
    fun onShown() {
        shown = true
        val review = flowState.value as? Flow.Review ?: return
        if (review.hidden && !review.signing && !review.requoting) requote(review, note = null)
    }

    /**
     * [review] as the state; one made while the screen was hidden, with the screen shown again
     * meanwhile, is made again at once, as [onShown] would have.
     */
    private fun landed(review: Flow.Review) {
        flowState.value = review
        if (review.hidden && shown && !review.signing && !review.requoting) requote(review, note = null)
    }

    private fun showCompose(error: String) {
        val compose = flowState.value as? Flow.Compose ?: return
        flowState.value = compose.copy(error = error, quoting = false)
    }

    private suspend fun unlockStep(): UnlockStep =
        UnlockStep(biometrics = safely { biometricsOn(describeKeyStore()) } == true)

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun unavailable(reason: ReadUnavailable): String = GnosisPayTexts.unavailable(reason)

    private companion object {
        const val NATIVE_DECIMALS = 18

        /** The overview's busy mark while a skip of expired entries is quoted from a queued transfer's row. */
        const val SKIP_BUSY = "skip-expired"
        const val LIST_UNREADABLE = "The wallet's list of accounts cannot be read right now."
        const val NOT_GNOSIS_PAY =
            "This Safe has no Delay module of its own, so it is no Gnosis Pay account this wallet can send from."
    }
}
