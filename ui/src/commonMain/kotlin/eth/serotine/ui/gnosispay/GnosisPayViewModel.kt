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
import eth.serotine.domain.usecase.QueuedDelayTransfer
import eth.serotine.domain.usecase.ReadDelayTransfers
import eth.serotine.domain.usecase.SendDelayCall
import eth.serotine.domain.usecase.TokenHoldings
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.notSentItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * The Gnosis Pay account [safe]'s screen (docs/gnosis-pay.md): what its Delay lets this wallet do,
 * the transfers queued through it from here, read again every [pollInterval] while shown, and a
 * transfer's two steps, queueing and execution, each quoted, reviewed and signed by the member
 * whose key is held here. What is signed is the reviewed quote's transaction; the password leaves
 * the state the moment it is handed to the signer, and whenever the review is left. A send that a
 * peer took ends in [sent] with its transaction hash, which the transfer's status follows.
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
    private val describeKeyStore: DescribeKeyStore,
    private val pollInterval: Duration = 15.seconds,
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
        ) : Flow

        data class Review(
            val quote: DelayQuote,
            val member: Address,
            val unlock: UnlockStep,
            val signing: Boolean = false,
            val error: String? = null,
        ) : Flow
    }

    private val flowState = MutableStateFlow<Flow>(Flow.Overview())

    /**
     * The request the screen waits on: each quote takes the next number, and Back, Send or a new
     * quote moves it on, so that an answer arriving late for a step left is no one's.
     */
    private var request = 0L

    /** The transaction a send ended in, which the screen hands to the transfer's status; null until then. */
    private val sentHash = MutableStateFlow<String?>(null)

    /** The hash of the transaction just sent, for the status screen; null until one is. */
    val sent: StateFlow<String?> = sentHash

    private var latest: GnosisPayReading.Found? = null
    private var latestHoldings: TokenHoldings? = null
    private var latestNative: BalanceReading? = null
    private var latestQueued: List<QueuedDelayTransfer> = emptyList()

    /** The account as a Gnosis Pay account, read once for everything this screen shows. */
    private val reading = observeGnosisPay(safe).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

    /** The transfers queued from here, read again every [pollInterval] for the Delay the account has now. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val queued = reading
        .map { (it as? GnosisPayReading.Found)?.delay?.address }
        .distinctUntilChanged()
        .flatMapLatest { delay ->
            if (delay == null) {
                flowOf(null)
            } else {
                flow<DelayTransfersReading?> {
                    while (true) {
                        // A read that throws is no answer this time; the next poll asks again.
                        safely { readDelayTransfers(safe, delay) }?.let { emit(it) }
                        delay(pollInterval)
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
        latestNative = chain.native
        latestHoldings = chain.holdings
        latestQueued = (chain.transfers as? DelayTransfersReading.Read)?.transfers.orEmpty()
        GnosisPayUiState.Ready(
            label = account.label,
            address = GnosisPayTexts.eip55(safe.address),
            delay = GnosisPayTexts.eip55(found.delay.address),
            waits = GnosisPayTexts.waits(found.delay.cooldownSeconds, found.delay.expirationSeconds),
            checkedAt = GnosisPayTexts.checkedAt(found.blockNumber),
            members = found.delay.members.map { member ->
                val inWallet = found.membersHeld.firstOrNull { it.address == member }
                    ?: found.membersHeldElsewhere.firstOrNull { it.address == member }
                MemberRow(GnosisPayTexts.eip55(member), inWallet?.label, found.membersHeld.any { it.address == member })
            },
            send = ActionItem(found.sending == GnosisPaySending.AVAILABLE, GnosisPayTexts.sendReason(found.sending)),
            queued = latestQueued.map { queuedRow(it, found, (step as? Flow.Overview)?.busy) },
            queuedNote = GnosisPayTexts.queuedNote(chain.transfers),
            step = stepOf(step, found, chain),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GnosisPayUiState.Loading)

    private fun queuedRow(queued: QueuedDelayTransfer, found: GnosisPayReading.Found, busy: String?): QueuedRow {
        val held = found.membersHeld.any { it.address == queued.member }
        val ready = queued.state is DelayTransferState.Ready
        val execute = when {
            queued.state == null || queued.state is DelayTransferState.NotInQueue ||
                queued.state is DelayTransferState.Expired -> null
            !held -> ActionItem(false, GnosisPayTexts.EXECUTE_NEEDS_MEMBER)
            found.sending == GnosisPaySending.RETIRED_MODULE ->
                ActionItem(false, GnosisPayTexts.sendReason(found.sending))
            !ready -> ActionItem(false, GnosisPayTexts.whyNotYet(queued.state))
            else -> ActionItem(true, null)
        }.let { action ->
            // While its execution is being quoted: greyed out, saying so.
            val checking = action?.enabled == true && busy == queued.queuedBy
            if (checking) ActionItem(false, GnosisPayTexts.CHECKING) else action
        }
        return QueuedRow(
            id = queued.queuedBy,
            title = GnosisPayTexts.transferTitle(queued.transfer, safe.network),
            status = GnosisPayTexts.status(queued.state, queued.queueOutcome),
            execute = execute,
        )
    }

    private fun stepOf(step: Flow, found: GnosisPayReading.Found, chain: Chain): GnosisPayStep = when (step) {
        is Flow.Overview -> GnosisPayStep.Overview(error = step.error)
        is Flow.Compose -> GnosisPayStep.Compose(
            assets = GnosisPayTexts.assets(safe.network, chain.native, chain.holdings),
            asset = step.asset,
            to = step.to,
            amount = step.amount,
            sender = found.membersHeld.firstOrNull()?.label ?: "",
            error = step.error,
            quoting = step.quoting,
        )
        is Flow.Review -> GnosisPayStep.Review(
            title = GnosisPayTexts.reviewTitle(step.quote, safe.network),
            lines = GnosisPayTexts.reviewLines(step.quote, found, step.member, safe.network),
            fee = step.quote.call.level,
            unlock = step.unlock,
            signing = step.signing,
            error = step.error,
        )
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
    fun review(fee: FeeLevel = FeeLevel.entries.first()) {
        val compose = flowState.value as? Flow.Compose ?: return
        val found = latest ?: return
        if (compose.quoting) return
        val member = found.membersHeld.firstOrNull()?.address ?: return
        val asset = GnosisPayTexts.assetOf(compose.asset, latestHoldings) ?: return showCompose(GnosisPayTexts.NO_ASSET)
        val to = Address.parse(compose.to) ?: return showCompose(GnosisPayTexts.BAD_ADDRESS)
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
            val quoting = safely { quoteDelayQueue(member, transfer, fee) }
            // Left meanwhile (Back, a new Send): the answer is no one's.
            if (request != mine || flowState.value !is Flow.Compose) return@launch
            flowState.value = when (quoting) {
                is DelayQuoting.Quoted -> Flow.Review(quoting.quote, member, unlockStep())
                null -> compose.copy(quoting = false, error = GnosisPayTexts.QUOTE_FAILED)
                else -> compose.copy(quoting = false, error = GnosisPayTexts.quoteRefused(quoting))
            }
        }
    }

    /**
     * Execute the queued transfer [id] (its queueing's hash): its execution quoted and shown for
     * review, once its row offers it (a member's key held here, the current module, ready); a quote
     * refused says why on the overview.
     */
    fun execute(id: String, fee: FeeLevel = FeeLevel.entries.first()) {
        val found = latest ?: return
        val overview = flowState.value as? Flow.Overview ?: return
        if (overview.busy != null) return
        val queued = latestQueued.firstOrNull { it.queuedBy == id } ?: return
        if (found.membersHeld.none { it.address == queued.member }) return
        if (found.sending == GnosisPaySending.RETIRED_MODULE || queued.state !is DelayTransferState.Ready) return
        flowState.value = Flow.Overview(busy = id)
        val mine = ++request
        viewModelScope.launch {
            val quoting = safely { quoteDelayExecution(queued.member, queued.transfer, fee) }
            if (request != mine || flowState.value !is Flow.Overview) return@launch
            flowState.value = when (quoting) {
                is DelayQuoting.Quoted -> Flow.Review(quoting.quote, queued.member, unlockStep())
                null -> Flow.Overview(error = GnosisPayTexts.QUOTE_FAILED)
                else -> Flow.Overview(error = GnosisPayTexts.quoteRefused(quoting))
            }
        }
    }

    fun onPasswordChange(text: String) {
        val review = flowState.value as? Flow.Review ?: return
        if (review.signing) return
        flowState.value = review.copy(unlock = review.unlock.copy(password = WalletPassword(text), error = null))
    }

    fun sign() {
        val review = flowState.value as? Flow.Review ?: return
        if (review.signing || review.unlock.password.text.isEmpty()) return
        send(review, Unlock.WithPassword(review.unlock.password))
    }

    fun signWithBiometrics() {
        val review = flowState.value as? Flow.Review ?: return
        if (review.signing || !review.unlock.biometrics) return
        send(review, Unlock.WithBiometrics(GnosisPayTexts.reviewTitle(review.quote, safe.network)))
    }

    private fun send(review: Flow.Review, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val signing = review.copy(signing = true, unlock = review.unlock.copy(password = WalletPassword.EMPTY))
        flowState.value = signing
        viewModelScope.launch {
            val sending = safely { sendDelayCall(review.quote, unlock) }
            val current = flowState.value as? Flow.Review ?: return@launch
            when (sending) {
                is TransferSending.Sent -> {
                    flowState.value = Flow.Overview()
                    sentHash.value = sending.hash
                }
                is TransferSending.NotSigned -> flowState.value = current.copy(
                    signing = false,
                    unlock = current.unlock.copy(
                        error = notSignedText(sending.signing, GnosisPayTexts.SEND_FAILED),
                        biometrics = current.unlock.biometrics && biometricsStillOffered(sending.signing),
                    ),
                )
                // Maybe taken: its record is what its status retries from, byte for byte.
                is TransferSending.NotSent -> if (sending.transfer.maybeSent) {
                    flowState.value = Flow.Overview()
                    sentHash.value = sending.transfer.hash
                } else {
                    val what = if (review.quote.step == DelayStep.QUEUE) "queueing" else "execution"
                    val why = notSentItem(sending.broadcast, false, what).message
                    flowState.value = current.copy(signing = false, error = why)
                }
                is TransferSending.RejectionUnverified -> {
                    flowState.value = Flow.Overview()
                    sentHash.value = sending.transfer.hash
                }
                null -> flowState.value = current.copy(signing = false, error = GnosisPayTexts.SEND_FAILED)
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

    /** The screen is no longer shown: the password typed leaves the state. */
    fun onHidden() {
        val review = flowState.value as? Flow.Review ?: return
        if (!review.signing) flowState.value = review.copy(unlock = review.unlock.copy(password = WalletPassword.EMPTY))
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
        const val LIST_UNREADABLE = "The wallet's list of accounts cannot be read right now."
        const val NOT_GNOSIS_PAY =
            "This Safe has no Delay module of its own, so it is no Gnosis Pay account this wallet can send from."
    }
}
