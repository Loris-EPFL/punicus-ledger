package eth.serotine.ui.withdraw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveChequebook
import eth.serotine.domain.usecase.QuoteChequebookWithdrawal
import eth.serotine.domain.usecase.SendChequebookWithdrawal
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.domain.usecase.WithdrawalQuote
import eth.serotine.domain.usecase.WithdrawalQuoting
import eth.serotine.domain.usecase.WithdrawalRefusal
import eth.serotine.domain.usecase.WithdrawalSending
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
import eth.serotine.ui.postage.PostageTexts
import eth.serotine.ui.swarm.SwarmTexts
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The withdrawal screen's ViewModel (docs/swarm.md, *Withdraw from the chequebook*): the Swarm node
 * [node]'s xBZZ withdrawn from its chequebook at [chequebook], signed by the node's own key. The
 * amount, then the quote made fresh ([QuoteChequebookWithdrawal]) and reviewed with its warnings,
 * then the signature ([SendChequebookWithdrawal]). A quote is made again when over a minute old from
 * its first read, or when its reads or the review spanned a hiding of the screen; the password
 * leaves the state whenever the screen is hidden. The withdrawal sent ends in [sent].
 */
class WithdrawViewModel(
    private val node: AccountId,
    private val chequebook: Address,
    observeAccount: ObserveAccount,
    observeChequebook: ObserveChequebook,
    private val quoteWithdrawal: QuoteChequebookWithdrawal,
    private val sendWithdrawal: SendChequebookWithdrawal,
    private val describeKeyStore: DescribeKeyStore,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    private sealed interface Step {
        data class Composing(
            val amount: String = "",
            val error: String? = null,
            val quoting: Boolean = false,
            val pending: String? = null,
        ) : Step

        data class Reviewing(
            val amount: String,
            val quote: WithdrawalQuote,
            val quotedAt: TimeMark,
            val unlock: UnlockStep,
            val signing: Boolean = false,
            val requoting: Boolean = false,
            val error: String? = null,
            val note: String? = null,
            val hidden: Boolean = false,
        ) : Step
    }

    private val step = MutableStateFlow<Step>(Step.Composing())
    private var request = 0L
    private var shown = true

    /** Counts each time the screen was hidden: a quote or a signature that spans one is made again. */
    private var hides = 0L

    private val sentHash = MutableStateFlow<String?>(null)

    /** The withdrawal just sent, for its status, which takes this screen's place; null until one is. */
    val sent: StateFlow<String?> = sentHash

    private val lookup = observeAccount(node)

    /** Null until the first reading: not read yet, which is no syncing. */
    private val reading: Flow<SwarmReading<ChequebookFinding>?> = run {
        val read: Flow<SwarmReading<ChequebookFinding>?> = observeChequebook(node.address, chequebook)
        read.onStart { emit(null) }
    }

    /** The screen's state. */
    val uiState: StateFlow<WithdrawUiState> = combine(lookup, reading, step) { found, reading, at ->
        when (found) {
            AccountLookup.ListUnavailable -> WithdrawUiState.Unavailable(WithdrawTexts.LIST_UNAVAILABLE)
            AccountLookup.NotInList -> WithdrawUiState.Gone
            is AccountLookup.Found -> ready(found.account, reading, at)
        }
    }.stateIn(
        viewModelScope,
        // A screen back after the timeout must not show an old reading as current: no replay then.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        WithdrawUiState.Loading,
    )

    private fun ready(account: Account, reading: SwarmReading<ChequebookFinding>?, step: Step): WithdrawUiState {
        if (account.network != Network.GNOSIS || account.kind != AccountKind.EOA) {
            return WithdrawUiState.Unavailable(WithdrawTexts.NOT_SWARM)
        }
        if (account.custody != AccountCustody.InKeyStore) {
            return WithdrawUiState.Unavailable(WithdrawTexts.NODE_KEY_NOT_HELD)
        }
        val book = ((reading as? SwarmReading.Verified)?.finding as? ChequebookFinding.Node)?.chequebook
        val lines = when (reading) {
            is SwarmReading.Verified -> when (val finding = reading.finding) {
                is ChequebookFinding.Node -> listOf(
                    "Balance ${SwarmTexts.bzz(finding.chequebook.balance)}",
                    "Free to withdraw ${SwarmTexts.bzz(finding.chequebook.liquidBalance)}",
                    SwarmTexts.verified(reading.blockNumber, held = false),
                )
                is ChequebookFinding.OthersIssuer -> listOf(SwarmTexts.othersChequebook(Eip55.format(finding.issuer)))
                ChequebookFinding.NotAChequebook -> listOf(SwarmTexts.NO_LONGER_A_CHEQUEBOOK)
            }
            null -> listOf(SwarmTexts.NOT_READ)
            SwarmReading.Syncing -> listOf(SwarmTexts.SYNCING)
            SwarmReading.NotPinned -> listOf(WithdrawTexts.NOT_SWARM)
            is SwarmReading.Unavailable -> listOf(SwarmTexts.unavailable(reading.reason))
        }
        return WithdrawUiState.Ready(
            node = PostageTexts.node(account.label, account.address),
            chequebook = Eip55.format(chequebook),
            lines = lines,
            step = when (step) {
                is Step.Composing -> WithdrawStep.Compose(
                    amount = step.amount,
                    // Nothing free: nothing offered to fill in.
                    all = book?.liquidBalance?.takeUnless { it.isZero }?.formatUnits(WithdrawTexts.BZZ_DECIMALS),
                    error = step.error,
                    quoting = step.quoting,
                    pending = step.pending,
                )
                is Step.Reviewing -> WithdrawStep.Review(
                    lines = WithdrawTexts.lines(step.quote, account.label),
                    warnings = listOf(WithdrawTexts.CHEQUES_WARNING, WithdrawTexts.NODE_KEY_WARNING),
                    unlock = step.unlock,
                    signing = step.signing || step.requoting,
                    checking = step.requoting,
                    error = step.error,
                    note = step.note,
                )
            },
        )
    }

    /** The amount typed, in BZZ. */
    fun onAmount(text: String) {
        val current = step.value as? Step.Composing ?: return
        if (current.quoting) return
        step.value = Step.Composing(text.trim())
    }

    /** Quotes the withdrawal of what the form says, fresh: its review, or why there is none. */
    fun review() {
        val current = step.value as? Step.Composing ?: return
        if (current.quoting) return
        WithdrawTexts.amountError(current.amount)?.let {
            step.value = current.copy(error = it)
            return
        }
        val amount = checkNotNull(UInt256.parseUnits(current.amount, WithdrawTexts.BZZ_DECIMALS))
        step.value = current.copy(quoting = true, error = null)
        val mine = ++request
        viewModelScope.launch {
            val at = timeSource.markNow() to hides
            val quoting = safely { quoteWithdrawal(node.address, chequebook, amount, FeeLevel.NORMAL) }
            if (request != mine) return@launch
            landed(after(current.amount, quoting, at, unlock = null, note = null))
        }
    }

    /** What [quoting], whose reads began at [at] (its time, and the hidings then), leads to. */
    private suspend fun after(
        amount: String,
        quoting: WithdrawalQuoting?,
        at: Pair<TimeMark, Long>,
        unlock: UnlockStep?,
        note: String?,
    ): Step = when (quoting) {
        is WithdrawalQuoting.Quoted -> Step.Reviewing(
            amount,
            quoting.quote,
            at.first,
            unlock ?: unlockStep(),
            note = note,
            hidden = hides != at.second || !shown,
        )
        is WithdrawalQuoting.Refused ->
            Step.Composing(amount, WithdrawTexts.refused(quoting.refusal), pending = pendingOf(quoting.refusal))
        null -> Step.Composing(amount, WithdrawTexts.QUOTE_FAILED)
    }

    /** The earlier withdrawal on its way that [refusal] names, for its status. */
    private fun pendingOf(refusal: WithdrawalRefusal): String? = (refusal as? WithdrawalRefusal.MoreThanFree)?.pending

    /** What a review made again because the screen was hidden says: why the last try sent nothing, first. */
    private fun carried(review: Step.Reviewing): String? = review.error ?: review.unlock.error ?: review.note

    private fun landed(next: Step) {
        step.value = next
        // Why the last try sent nothing outlives the quote made again: it is said as its note.
        if (next is Step.Reviewing && next.hidden && shown) requote(next, carried(next))
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
        val notAfter = promptDeadline(review.quotedAt) ?: return requote(review, PROMPT_TOO_LATE)
        send(review, Unlock.WithBiometrics(WithdrawTexts.biometricReason(review.quote), notAfter))
    }

    private fun requoted(review: Step.Reviewing): Boolean {
        if (review.hidden) {
            requote(review, carried(review))
            return true
        }
        if (review.quotedAt.elapsedNow() <= QUOTE_MAX_AGE) return false
        requote(review, STALE_QUOTE)
        return true
    }

    /** [review] quoted again; the password as the state holds it at the answer (gone, if hidden meanwhile). */
    private fun requote(review: Step.Reviewing, note: String?) {
        step.value = review.copy(requoting = true, error = null, note = null)
        val mine = ++request
        viewModelScope.launch {
            val at = timeSource.markNow() to hides
            val quoting = safely { quoteWithdrawal(node.address, chequebook, review.quote.amount, FeeLevel.NORMAL) }
            val current = step.value as? Step.Reviewing ?: return@launch
            if (request != mine) return@launch
            landed(after(review.amount, quoting, at, current.unlock.copy(error = null), note))
        }
    }

    private fun send(review: Step.Reviewing, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = review.unlock.copy(password = WalletPassword.EMPTY)
        val signing = review.copy(signing = true, error = null, unlock = unlocked)
        step.value = signing
        val mine = ++request
        val hidesAtSign = hides
        viewModelScope.launch {
            val sending = safely { sendWithdrawal(review.quote, unlock) }
            if (request != mine) return@launch
            val signed = signing.copy(signing = false, hidden = signing.hidden || hides != hidesAtSign || !shown)
            when (sending) {
                is WithdrawalSending.Sending -> sent(signed, sending.sending)
                WithdrawalSending.NonceTaken -> requote(signed, WithdrawTexts.NONCE_TAKEN)
                WithdrawalSending.Changed -> requote(signed, WithdrawTexts.CHANGED)
                is WithdrawalSending.NotChecked -> landed(signed.copy(error = WithdrawTexts.notChecked(sending.reason)))
                null -> landed(signed.copy(error = WithdrawTexts.SEND_FAILED))
            }
        }
    }

    private fun sent(review: Step.Reviewing, sending: TransferSending) {
        val hash = when (sending) {
            is TransferSending.Sent -> sending.hash
            is TransferSending.NotSent -> if (sending.transfer.maybeSent) sending.transfer.hash else null
            is TransferSending.RejectionUnverified -> sending.transfer.hash
            is TransferSending.NotSigned -> return landed(
                review.copy(
                    unlock = review.unlock.copy(
                        error = notSignedText(sending.signing, WithdrawTexts.SEND_FAILED),
                        biometrics = review.unlock.biometrics && biometricsStillOffered(sending.signing),
                    ),
                ),
            )
        }
        if (hash == null) {
            val why = notSentItem((sending as TransferSending.NotSent).broadcast, false, "withdrawal").message
            return landed(review.copy(error = why))
        }
        step.value = Step.Composing(review.amount)
        sentHash.value = hash
    }

    private val openedHash = MutableStateFlow<String?>(null)

    /** An earlier withdrawal on its way whose status is opened over this screen; null otherwise. */
    val opened: StateFlow<String?> = openedHash

    /** Opens the status of the earlier withdrawal [hash] on its way, over this screen. */
    fun openPending(hash: String) {
        openedHash.value = hash
    }

    /** The status of an earlier withdrawal was opened. */
    fun onOpenedShown() {
        openedHash.value = null
    }

    /** The status of the withdrawal sent was opened. */
    fun onSentShown() {
        sentHash.value = null
    }

    /** Back from the review to the form, the password dropped; false from the form, which leaves. */
    fun back(): Boolean {
        val current = step.value
        if (current is Step.Reviewing && current.signing) return true
        if (current is Step.Composing) return false
        request++
        step.value = Step.Composing((current as Step.Reviewing).amount)
        return true
    }

    /** The screen is no longer shown: the password leaves the state, and the quote is made again. */
    fun onHidden() {
        shown = false
        hides++
        val review = step.value as? Step.Reviewing ?: return
        if (review.signing) return
        step.value = review.copy(unlock = review.unlock.copy(password = WalletPassword.EMPTY), hidden = true)
    }

    /** The screen is shown again: a quote made before it was hidden is made again now. */
    fun onShown() {
        shown = true
        val review = step.value as? Step.Reviewing ?: return
        if (review.hidden && !review.signing && !review.requoting) requote(review, carried(review))
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
}
