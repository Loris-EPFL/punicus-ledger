package eth.serotine.ui.postage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SwarmCalls
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.BatchCall
import eth.serotine.domain.usecase.BatchQuote
import eth.serotine.domain.usecase.BatchQuoting
import eth.serotine.domain.usecase.BatchSending
import eth.serotine.domain.usecase.ChunkFunding
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObservePostageBatch
import eth.serotine.domain.usecase.QuoteBatchDilution
import eth.serotine.domain.usecase.QuoteBatchPurchase
import eth.serotine.domain.usecase.QuoteBatchTopUp
import eth.serotine.domain.usecase.SendBatchApproval
import eth.serotine.domain.usecase.SendBatchCall
import eth.serotine.domain.usecase.TokenApprovalQuote
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.swarm.SwarmTexts
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The postage screen's ViewModel (docs/swarm.md, *The four actions*): [action] for the Swarm node
 * [node], of its batch [batchId] for a top-up or a dilution. The form, then each transaction quoted
 * fresh in strict mode ([QuoteBatchPurchase], [QuoteBatchTopUp], [QuoteBatchDilution]) and reviewed:
 * an approval of exactly the total first where the allowance falls short, sent by
 * [SendBatchApproval], the call quoted again once a block has it; then the call, sent by
 * [SendBatchCall]. Each is quoted again when over a minute old, or shown again after the screen was
 * hidden, before anything is signed. The call sent ends in [sent], whose status the screen opens.
 */
class PostageViewModel(
    private val node: AccountId,
    private val action: PostageAction,
    private val batchId: BatchId?,
    observeAccount: ObserveAccount,
    observeAccounts: ObserveAccounts,
    observePostageBatch: ObservePostageBatch,
    private val quoteBatchPurchase: QuoteBatchPurchase,
    private val quoteBatchTopUp: QuoteBatchTopUp,
    private val quoteBatchDilution: QuoteBatchDilution,
    private val sendBatchApproval: SendBatchApproval,
    private val sendBatchCall: SendBatchCall,
    private val describeKeyStore: DescribeKeyStore,
    private val awaitPoll: Duration = 5.seconds,
    private val awaitMax: Duration = 10.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    init {
        require((action == PostageAction.BUY) == (batchId == null)) { "a top-up or a dilution names its batch" }
    }

    /** The form as typed; null fields take their defaults ([payerOf], [depthOf]). */
    private data class Form(
        val payer: Address? = null,
        val depth: Int? = null,
        val days: String = DEFAULT_DAYS,
        val immutable: Boolean = false,
        val error: String? = null,
        val quoting: Boolean = false,
        val note: String? = null,
        val pending: String? = null,
    )

    /**
     * What is quoted: the form resolved. [perChunk], once an approval was quoted for it, is what every
     * later quote of the call puts in, in place of [seconds] worked out again at a price moved since.
     */
    private data class Request(
        val payer: Address,
        val depth: Int?,
        val seconds: Long?,
        val immutable: Boolean,
        val perChunk: UInt256? = null,
    ) {
        val funding: ChunkFunding
            get() = perChunk?.let(ChunkFunding::PerChunk) ?: ChunkFunding.Lasting(checkNotNull(seconds))
    }

    private sealed interface Reviewed {
        data class Approval(val quote: TokenApprovalQuote, val total: UInt256) : Reviewed
        data class Call(val quote: BatchQuote) : Reviewed
    }

    private sealed interface Step {
        val form: Form

        data class Composing(override val form: Form) : Step

        data class Reviewing(
            override val form: Form,
            val request: Request,
            val reviewed: Reviewed,
            val quotedAt: TimeMark,
            val unlock: UnlockStep,
            /** 1 for the approval, 2 for the call after it; null for a call alone. */
            val position: Int?,
            val signing: Boolean = false,
            val requoting: Boolean = false,
            val error: String? = null,
            val note: String? = null,
            /** Quoted before the screen was last hidden: quoted again before it is signed. */
            val hidden: Boolean = false,
        ) : Step

        data class Awaiting(override val form: Form, val request: Request, val hash: String, val since: TimeMark) :
            Step
    }

    private val step = MutableStateFlow<Step>(Step.Composing(Form()))

    /** Each quote takes the next number; Back and a new quote move it on, so a late answer is no one's. */
    private var request = 0L

    private var waiting: Job? = null
    private var shown = true

    private val sentHash = MutableStateFlow<String?>(null)

    /** The batch call just sent, for its status, which takes this screen's place; null until one is. */
    val sent: StateFlow<String?> = sentHash

    private val openedHash = MutableStateFlow<String?>(null)

    /** A transaction from here whose status is opened over this screen (an approval on its way); null otherwise. */
    val opened: StateFlow<String?> = openedHash

    private val lookup = observeAccount(node)

    /** The accounts that may pay: an EOA on Gnosis Chain whose key is held here; null when the list cannot be read. */
    private val payers: Flow<List<Account>?> = observeAccounts().map { list ->
        (list as? AccountList.Loaded)?.accounts?.filter { canPay(it) }
    }

    private val batchReading: Flow<SwarmReading<BatchFinding>?> =
        if (batchId == null) {
            flowOf(null)
        } else {
            val read: Flow<SwarmReading<BatchFinding>?> = observePostageBatch(node.address, batchId)
            read.onStart { emit(null) }
        }

    /** The screen's state. */
    val uiState: StateFlow<PostageUiState> = combine(lookup, payers, batchReading, step) { found, payers, batch, at ->
        when (val lookup = found) {
            AccountLookup.ListUnavailable -> PostageUiState.Unavailable(PostageTexts.LIST_UNAVAILABLE)
            AccountLookup.NotInList -> PostageUiState.Gone
            is AccountLookup.Found -> ready(lookup.account, payers, batch, at)
        }
    }.stateIn(
        viewModelScope,
        // A screen back after the timeout must not show an old reading as current: no replay then.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        PostageUiState.Loading,
    )

    private fun ready(
        account: Account,
        payers: List<Account>?,
        batch: SwarmReading<BatchFinding>?,
        step: Step,
    ): PostageUiState {
        if (account.network != Network.GNOSIS || account.kind != AccountKind.EOA) {
            return PostageUiState.Unavailable(PostageTexts.NOT_SWARM)
        }
        payers ?: return PostageUiState.Unavailable(PostageTexts.LIST_UNAVAILABLE)
        when (action) {
            PostageAction.BUY, PostageAction.TOP_UP ->
                if (payers.isEmpty()) return PostageUiState.Unavailable(PostageTexts.NO_PAYER)
            PostageAction.DILUTE ->
                if (!canPay(account)) return PostageUiState.Unavailable(PostageTexts.NODE_KEY_NOT_HELD)
        }
        val shortId = batchId?.let { PostageTexts.shortBatch(it.hex) }
        return PostageUiState.Ready(
            title = PostageTexts.title(action, shortId),
            node = PostageTexts.node(account.label, account.address),
            batch = batchLines(batch),
            step = stepOf(step, account, payers, batch),
        )
    }

    private fun stepOf(step: Step, account: Account, payers: List<Account>, batch: SwarmReading<BatchFinding>?) =
        when (step) {
            is Step.Composing -> compose(step.form, payers, batch)
            is Step.Reviewing -> review(step, account, payers, batch)
            is Step.Awaiting -> PostageStep.Awaiting(step.hash, PostageTexts.AWAITING)
        }

    private fun compose(form: Form, payers: List<Account>, batch: SwarmReading<BatchFinding>?): PostageStep.Compose {
        val dilutes = action == PostageAction.DILUTE
        return PostageStep.Compose(
            payers = if (dilutes) emptyList() else payers.map { PayerItem(Eip55.format(it.address), it.label) },
            payer = if (dilutes) null else payerOf(form, payers)?.let(Eip55::format),
            signer = PostageTexts.NODE_SIGNS.takeIf { dilutes },
            depths = depthsOffered(batch).map { DepthItem(it, SwarmTexts.capacity(it)) },
            depth = depthOf(form, batch),
            days = form.days.takeUnless { dilutes },
            daysLabel = when (action) {
                PostageAction.BUY -> PostageTexts.TIME_TO_LIVE
                PostageAction.TOP_UP -> PostageTexts.TIME_TO_ADD
                PostageAction.DILUTE -> null
            },
            immutable = form.immutable.takeIf { action == PostageAction.BUY },
            explains = PostageTexts.explains(action),
            error = form.error,
            quoting = form.quoting,
            note = form.note,
            pending = form.pending,
        )
    }

    private fun review(
        step: Step.Reviewing,
        account: Account,
        payers: List<Account>,
        batch: SwarmReading<BatchFinding>?,
    ): PostageStep.Review {
        val payerLabel = payers.firstOrNull { it.address == step.request.payer }?.label
        val signer = when (val reviewed = step.reviewed) {
            is Reviewed.Approval -> reviewed.quote.from
            is Reviewed.Call -> reviewed.quote.quote.from
        }
        val (title, lines) = when (val reviewed = step.reviewed) {
            is Reviewed.Approval -> PostageTexts.APPROVAL_TITLE to
                PostageTexts.approvalLines(reviewed.quote, reviewed.total, payerLabel) +
                ("Then" to PostageTexts.APPROVAL_NOTE)
            is Reviewed.Call -> PostageTexts.callTitle(reviewed.quote) to
                PostageTexts.callLines(reviewed.quote, payerLabel, account.label)
        }
        val diluted = (step.reviewed as? Reviewed.Call)?.quote?.takeIf { it.call is BatchCall.Dilution }
        val immutable = diluted?.batch?.immutable
        val warnings = listOfNotNull(
            PostageTexts.NODE_KEY_WARNING.takeIf { signer == node.address },
            PostageTexts.IMMUTABLE_WARNING.takeIf { immutable == true },
        )
        return PostageStep.Review(
            title = title,
            position = step.position?.let(PostageTexts::position),
            lines = lines,
            warnings = warnings,
            unlock = step.unlock,
            signing = step.signing || step.requoting,
            checking = step.requoting,
            error = step.error,
            note = step.note,
        )
    }

    private fun batchLines(batch: SwarmReading<BatchFinding>?): List<String> {
        if (batchId == null) return emptyList()
        return when (batch) {
            null -> listOf(SwarmTexts.NOT_READ)
            is SwarmReading.Verified -> when (val finding = batch.finding) {
                is BatchFinding.Node -> listOf(
                    SwarmTexts.capacity(finding.batch.depth),
                    SwarmTexts.timeLeft(finding.batch),
                    SwarmTexts.verified(batch.blockNumber, held = false),
                )
                BatchFinding.Gone -> listOf(PostageTexts.notTheNodes(null))
                is BatchFinding.OthersOwner -> listOf(PostageTexts.notTheNodes(finding.owner))
            }
            SwarmReading.Syncing -> listOf(SwarmTexts.SYNCING)
            SwarmReading.NotPinned -> listOf(PostageTexts.NOT_SWARM)
            is SwarmReading.Unavailable -> listOf(SwarmTexts.unavailable(batch.reason))
        }
    }

    /** The payer chosen, or by default the node itself where it may pay, else the first that may. */
    private fun payerOf(form: Form, payers: List<Account>): Address? =
        form.payer?.takeIf { chosen -> payers.any { it.address == chosen } }
            ?: payers.firstOrNull { it.address == node.address }?.address
            ?: payers.firstOrNull()?.address

    private fun verifiedBatch(batch: SwarmReading<BatchFinding>?) =
        ((batch as? SwarmReading.Verified)?.finding as? BatchFinding.Node)?.batch

    private fun depthsOffered(batch: SwarmReading<BatchFinding>?): List<Int> = when (action) {
        PostageAction.BUY -> (SwarmCalls.MIN_DEPTH..SwarmCalls.MAX_DEPTH).toList()
        PostageAction.TOP_UP -> emptyList()
        PostageAction.DILUTE -> verifiedBatch(batch)?.depth?.let { from ->
            ((from + 1)..maxOf(MAX_DILUTED_DEPTH, from + 1)).toList()
        } ?: emptyList()
    }

    private fun depthOf(form: Form, batch: SwarmReading<BatchFinding>?): Int? {
        val offered = depthsOffered(batch)
        return form.depth?.takeIf { it in offered } ?: when (action) {
            PostageAction.BUY -> DEFAULT_DEPTH
            PostageAction.TOP_UP -> null
            PostageAction.DILUTE -> offered.firstOrNull()
        }
    }

    /** The account that pays for a purchase or a top-up, by its address. */
    fun onPayer(address: String) = edit { it.copy(payer = Address.parse(address)) }

    /** The depth of the batch bought, or diluted to. */
    fun onDepth(depth: Int) = edit { it.copy(depth = depth) }

    /** The days typed: a purchase's time to live, or what a top-up adds. */
    fun onDays(text: String) = edit { it.copy(days = text.filter(Char::isDigit).take(MAX_DIGITS)) }

    /** Whether the batch bought is immutable. */
    fun onImmutable(immutable: Boolean) = edit { it.copy(immutable = immutable) }

    private fun edit(change: (Form) -> Form) {
        val current = step.value as? Step.Composing ?: return
        if (current.form.quoting) return
        step.value = Step.Composing(change(current.form).copy(error = null, note = null))
    }

    /** Quotes what the form says, fresh: its review, or why there is none. */
    fun review() {
        val current = step.value as? Step.Composing ?: return
        if (current.form.quoting) return
        val form = current.form
        if (action != PostageAction.DILUTE) {
            PostageTexts.daysError(form.days)?.let { error ->
                step.value = Step.Composing(form.copy(error = error))
                return
            }
        }
        val quoting = form.copy(quoting = true, error = null, note = null, pending = null)
        step.value = Step.Composing(quoting)
        val mine = ++request
        viewModelScope.launch {
            val resolved = resolve(form)
            if (request != mine) return@launch
            if (resolved is Resolved.Refused) {
                step.value = Step.Composing(form.copy(error = resolved.message))
                return@launch
            }
            val asked = (resolved as Resolved.Ready).request
            val quoted = safely { quote(asked) }
            if (request != mine) return@launch
            landed(after(form, asked, quoted, position = null, unlock = null, note = null))
        }
    }

    private sealed interface Resolved {
        data class Ready(val request: Request) : Resolved
        data class Refused(val message: String) : Resolved
    }

    private suspend fun resolve(form: Form): Resolved {
        val payers = payers.first() ?: return Resolved.Refused(PostageTexts.LIST_UNAVAILABLE)
        // Only a dilution's depths come from the batch; a quote reads it fresh anyway.
        val batch = if (action == PostageAction.DILUTE) batchReading.first { it != null } else null
        val payer = when (action) {
            PostageAction.DILUTE -> node.address
            else -> payerOf(form, payers) ?: return Resolved.Refused(PostageTexts.NO_PAYER)
        }
        val depth = depthOf(form, batch)
        if (action == PostageAction.DILUTE && depth == null) return Resolved.Refused(PostageTexts.BATCH_NOT_READ)
        // A depth chosen that the batch no longer offers (Bee diluted it meanwhile) is never swapped for another.
        if (form.depth != null && depth != form.depth) return Resolved.Refused(PostageTexts.DEPTH_GONE)
        val seconds = if (action == PostageAction.DILUTE) null else form.days.trim().toLong() * DAY_SECONDS
        return Resolved.Ready(Request(payer, depth, seconds, form.immutable))
    }

    private suspend fun quote(request: Request): BatchQuoting = when (action) {
        PostageAction.BUY -> quoteBatchPurchase(
            request.payer,
            node.address,
            checkNotNull(request.depth),
            request.funding,
            request.immutable,
            FeeLevel.NORMAL,
        )
        PostageAction.TOP_UP -> quoteBatchTopUp(
            request.payer,
            node.address,
            checkNotNull(batchId),
            request.funding,
            FeeLevel.NORMAL,
        )
        PostageAction.DILUTE ->
            quoteBatchDilution(node.address, checkNotNull(batchId), checkNotNull(request.depth), FeeLevel.NORMAL)
    }

    /**
     * The step [quoting] leads to from [form]: a review ([position] its step of two, [unlock] kept
     * from the review it replaces, [note] why it was made again), the wait for an approval, or the
     * form saying why nothing was quoted.
     */
    private suspend fun after(
        form: Form,
        request: Request,
        quoting: BatchQuoting?,
        position: Int?,
        unlock: UnlockStep?,
        note: String?,
    ): Step {
        val quotedAt = timeSource.markNow()
        return when (quoting) {
            is BatchQuoting.Quoted -> Step.Reviewing(
                form.copy(quoting = false),
                request,
                Reviewed.Call(quoting.quote),
                quotedAt,
                unlock ?: unlockStep(),
                position,
                note = note,
                hidden = !shown,
            )
            is BatchQuoting.NeedsApproval -> Step.Reviewing(
                form.copy(quoting = false),
                // The call is quoted from now on for what the approval covers.
                request.copy(perChunk = quoting.perChunk),
                Reviewed.Approval(quoting.approval, quoting.total),
                quotedAt,
                unlock ?: unlockStep(),
                position = 1,
                note = note,
                hidden = !shown,
            )
            is BatchQuoting.ApprovalPending ->
                Step.Awaiting(form.copy(quoting = false), request, quoting.hash, quotedAt)
            is BatchQuoting.EarlierPullPending ->
                Step.Composing(form.copy(quoting = false, error = PostageTexts.PULL_PENDING, pending = quoting.hash))
            is BatchQuoting.Refused ->
                Step.Composing(form.copy(quoting = false, error = PostageTexts.refused(quoting.refusal)))
            null -> Step.Composing(form.copy(quoting = false, error = PostageTexts.QUOTE_FAILED))
        }
    }

    /** [next] as the state: a wait starts its polling; a review made while hidden is made again once shown. */
    private fun landed(next: Step) {
        step.value = next
        when {
            next is Step.Awaiting -> awaitApproval(next)
            next is Step.Reviewing && next.hidden && shown -> requote(next, note = null)
        }
    }

    fun onPasswordChange(text: String) {
        val review = step.value as? Step.Reviewing ?: return
        if (review.signing || review.requoting) return
        step.value = review.copy(unlock = review.unlock.copy(password = WalletPassword(text), error = null))
    }

    fun sign() {
        val review = step.value as? Step.Reviewing ?: return
        if (review.signing || review.requoting || review.unlock.password.text.isEmpty() || requoted(review)) return
        send(review, Unlock.WithPassword(review.unlock.password))
    }

    fun signWithBiometrics() {
        val review = step.value as? Step.Reviewing ?: return
        if (review.signing || review.requoting || !review.unlock.biometrics || requoted(review)) return
        // The prompt gets what is left of the quote's age: past it, nothing is signed, and it is made again.
        val notAfter = promptDeadline(review.quotedAt) ?: return requote(review, PROMPT_TOO_LATE)
        val title = when (val reviewed = review.reviewed) {
            is Reviewed.Approval -> PostageTexts.APPROVAL_TITLE
            is Reviewed.Call -> PostageTexts.callTitle(reviewed.quote)
        }
        send(review, Unlock.WithBiometrics(PostageTexts.biometricReason(title), notAfter))
    }

    /** Whether [review]'s quote is too old to sign, or was made before the screen was hidden: made again instead. */
    private fun requoted(review: Step.Reviewing): Boolean {
        if (review.hidden) {
            requote(review, note = null)
            return true
        }
        if (review.quotedAt.elapsedNow() <= QUOTE_MAX_AGE) return false
        requote(review, STALE_QUOTE)
        return true
    }

    /** [review] quoted again from its request, the password typed kept; [note] what the review then says. */
    private fun requote(review: Step.Reviewing, note: String?) {
        step.value = review.copy(requoting = true, error = null, note = null)
        val mine = ++request
        viewModelScope.launch {
            val quoting = safely { quote(review.request) }
            if (request != mine || step.value !is Step.Reviewing) return@launch
            val position = if (review.position == 2) 2 else null
            landed(after(review.form, review.request, quoting, position, review.unlock.copy(error = null), note))
        }
    }

    private fun send(review: Step.Reviewing, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = review.unlock.copy(password = WalletPassword.EMPTY)
        val signing = review.copy(signing = true, error = null, unlock = unlocked)
        step.value = signing
        val mine = ++request
        viewModelScope.launch {
            val sending = safely {
                when (val reviewed = review.reviewed) {
                    is Reviewed.Approval -> sendBatchApproval(reviewed.quote, unlock)
                    is Reviewed.Call -> sendBatchCall(reviewed.quote, unlock)
                }
            }
            if (request != mine) return@launch
            val approval = review.reviewed is Reviewed.Approval
            when (sending) {
                is BatchSending.Sending -> sent(signing, sending.sending, approval)
                BatchSending.NonceTaken -> requote(signing.copy(signing = false), PostageTexts.NONCE_TAKEN)
                BatchSending.BatchChanged -> requote(signing.copy(signing = false), PostageTexts.BATCH_CHANGED)
                is BatchSending.NotChecked ->
                    landed(signing.copy(signing = false, error = PostageTexts.notChecked(sending.reason)))
                null -> landed(signing.copy(signing = false, error = PostageTexts.SEND_FAILED))
            }
        }
    }

    private fun sent(review: Step.Reviewing, sending: TransferSending, approval: Boolean) {
        val hash = when (sending) {
            is TransferSending.Sent -> sending.hash
            // Maybe taken: its record is what its status retries from, byte for byte.
            is TransferSending.NotSent -> if (sending.transfer.maybeSent) sending.transfer.hash else null
            is TransferSending.RejectionUnverified -> sending.transfer.hash
            is TransferSending.NotSigned -> {
                landed(
                    review.copy(
                        signing = false,
                        unlock = review.unlock.copy(
                            error = notSignedText(sending.signing, PostageTexts.SEND_FAILED),
                            biometrics = review.unlock.biometrics && biometricsStillOffered(sending.signing),
                        ),
                    ),
                )
                return
            }
        }
        if (hash == null) {
            val what = if (approval) "approval" else "transaction"
            val why = notSentItem((sending as TransferSending.NotSent).broadcast, false, what).message
            landed(review.copy(signing = false, error = why))
            return
        }
        if (approval) {
            // Step 1 of 2: the call waits for a block to have the approval, then is reviewed on its own.
            landed(Step.Awaiting(review.form, review.request, hash, timeSource.markNow()))
        } else {
            step.value = Step.Composing(review.form)
            sentHash.value = hash
        }
    }

    /**
     * While the approval is on its way: the call quoted again every [awaitPoll] until a block has the
     * approval, then shown for review, step 2 of 2: nothing is signed for it before that. A call that
     * can no longer be quoted goes back to the form, which says why; so does one whose approval no
     * block has after [awaitMax], which offers the approval's status.
     */
    private fun awaitApproval(awaiting: Step.Awaiting) {
        waiting?.cancel()
        waiting = viewModelScope.launch {
            var failed = 0
            while (awaiting.since.elapsedNow() < awaitMax) {
                delay(awaitPoll)
                val quoting = safely { quote(awaiting.request) }
                if (step.value != awaiting) return@launch
                val (form, asked) = awaiting.form to awaiting.request
                when {
                    quoting is BatchQuoting.ApprovalPending -> failed = 0
                    quoting is BatchQuoting.Quoted -> {
                        landed(after(form, asked, quoting, position = 2, unlock = null, note = null))
                        return@launch
                    }
                    quoting is BatchQuoting.NeedsApproval -> {
                        val note = PostageTexts.APPROVAL_FAILED_MAYBE
                        landed(after(form, asked, quoting, position = 1, unlock = null, note = note))
                        return@launch
                    }
                    // A read that failed for a moment (a peer, the light client between heads): waited out.
                    (quoting == null || quoting is BatchQuoting.Refused) && ++failed < AWAIT_FAILURES -> Unit
                    else -> {
                        val next = after(form, asked, quoting, position = 2, unlock = null, note = null)
                        // The form keeps the approval's status: what it was waiting for.
                        val kept = (next as? Step.Composing)?.let { Step.Composing(it.form.copy(pending = awaiting.hash)) }
                        landed(kept ?: next)
                        return@launch
                    }
                }
            }
            if (step.value == awaiting) {
                val timedOut = awaiting.form.copy(note = PostageTexts.AWAIT_TIMED_OUT, pending = awaiting.hash)
                step.value = Step.Composing(timedOut)
            }
        }
    }

    /** Opens the status of the transaction [hash] from here that holds this one back, over this screen. */
    fun openPending(hash: String) {
        openedHash.value = hash
    }

    /** The status of the call sent was opened. */
    fun onSentShown() {
        sentHash.value = null
    }

    /** The status of a pending transaction was opened. */
    fun onOpenedShown() {
        openedHash.value = null
    }

    /**
     * Back from a review or the wait, to the form, the password dropped; false from the form, which
     * leaves the screen. Nothing while a signature runs.
     */
    fun back(): Boolean {
        val current = step.value
        if (current is Step.Reviewing && current.signing) return true
        if (current is Step.Composing) return false
        request++
        waiting?.cancel()
        step.value = Step.Composing(current.form.copy(quoting = false))
        return true
    }

    /** The screen is no longer shown: the password leaves the state, and the quote under review is made again. */
    fun onHidden() {
        shown = false
        val review = step.value as? Step.Reviewing ?: return
        if (review.signing) return
        step.value = review.copy(unlock = review.unlock.copy(password = WalletPassword.EMPTY), hidden = true)
    }

    /** The screen is shown again: a quote made before it was hidden is made again now. */
    fun onShown() {
        shown = true
        val review = step.value as? Step.Reviewing ?: return
        if (review.hidden && !review.signing && !review.requoting) requote(review, note = null)
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

    private companion object {
        const val DEFAULT_DAYS = "30"
        const val DEFAULT_DEPTH = 20
        const val MAX_DIGITS = 5
        const val DAY_SECONDS = 86_400L

        /** The deepest dilution offered: the deepest row of Swarm's capacity table. */
        const val MAX_DILUTED_DEPTH = 41

        /** How many failed reads in a row the wait for an approval sits out. */
        const val AWAIT_FAILURES = 3

        /** Whether [account] may pay or sign here: an EOA on Gnosis Chain whose key is on this device. */
        fun canPay(account: Account): Boolean = account.network == Network.GNOSIS &&
            account.kind == AccountKind.EOA && account.custody == AccountCustody.InKeyStore
    }
}
