package eth.serotine.domain.usecase

import eth.serotine.domain.repository.DelayReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// A verified read is made again at new heads, and one of them can find no peer serving its proof
// ([ReadUnavailable.UNPROVEN]) while the light client is otherwise fine. Who controls a Gnosis Pay
// account is read from many addresses at once (docs/gnosis-pay.md, *A read that did not answer*):
// were each such miss to reset the answer, it would hardly ever stand.

/** A [value] of a flow that holds its last verified one: [held] when it stands in for a read that did not answer. */
internal data class Held<out T>(val value: T, val held: Boolean)

/**
 * How long a verified answer stands over reads that do not answer (owner's decision, 2026-11-17):
 * an hour from the first of them, as long as simple mode counts a balance read a while ago.
 */
internal val HOLD_LIMIT: Duration = 1.hours

/**
 * This flow, with the last [verified] value standing ([Held.held]) while the values after it are
 * [transient]: a read that did not answer this time never replaces one that did. For [limit] at
 * most, counted from the first such value after the verified one: then the latest value is passed
 * on as it is, and so is every transient one after it, until a verified one comes. Any other value
 * replaces the held one at once and ends the hold too. Before the first verified value everything
 * is passed on. The state is each collector's own: one that starts during a miss holds nothing.
 * Consecutive equal values are not repeated.
 */
internal fun <T : Any> Flow<T>.holdingLastMarked(
    limit: Duration = HOLD_LIMIT,
    verified: (T) -> Boolean,
    transient: (T) -> Boolean,
): Flow<Held<T>> = channelFlow {
    // The upstream's collector and the limit's timer may run on different threads.
    val mutex = Mutex()
    var held: T? = null
    var latest: T? = null
    var timer: Job? = null
    collect { value ->
        mutex.withLock {
            val last = held
            when {
                verified(value) -> {
                    timer?.cancel()
                    timer = null
                    held = value
                    send(Held(value, held = false))
                }
                transient(value) && last != null -> {
                    latest = value
                    send(Held(last, held = true))
                    if (timer == null) {
                        timer = launch {
                            delay(limit)
                            mutex.withLock {
                                held = null
                                timer = null
                                latest?.let { send(Held(it, held = false)) }
                            }
                        }
                    }
                }
                else -> {
                    timer?.cancel()
                    timer = null
                    held = null
                    send(Held(value, held = false))
                }
            }
        }
    }
    // The upstream ended: what is shown stays as it is, and the limit no longer runs.
    mutex.withLock { timer?.cancel() }
}.distinctUntilChanged()

/**
 * Whether a read without an answer for this reason may answer at the next head with nothing done:
 * no proof could be had this time (mostly no peer serving it; an engine whose state cannot be read
 * says the same, which is why a hold has a limit), or the light client is catching up. Every other
 * reason (the network off, no engine, a stale checkpoint, a refusal) stands until something is
 * done about it.
 */
internal val ReadUnavailable.transient: Boolean
    get() = this == ReadUnavailable.UNPROVEN || this == ReadUnavailable.SYNCING

/**
 * An address's kind, its last [KindReading.Verified] answer standing, with the block it was
 * verified at, while the readings after it only did not answer ([transient]), for [HOLD_LIMIT] at
 * most ([holdingLastMarked]). An answer that differs, or a reason that stands, replaces it at once.
 */
internal fun Flow<KindReading>.holdingVerifiedMarked(): Flow<Held<KindReading>> = holdingLastMarked(
    verified = { it is KindReading.Verified },
    transient = { it == KindReading.Syncing || (it as? KindReading.Unavailable)?.reason?.transient == true },
)

/** [holdingVerifiedMarked], for a reader that does not tell a held answer from a fresh one. */
internal fun Flow<KindReading>.holdingVerified(): Flow<KindReading> =
    holdingVerifiedMarked().map { it.value }.distinctUntilChanged()

/** A module's reading, its last [DelayReading.Verified] answer standing the same way ([holdingVerifiedMarked]). */
internal fun Flow<DelayReading>.holdingVerifiedDelay(): Flow<Held<DelayReading>> = holdingLastMarked(
    verified = { it is DelayReading.Verified },
    transient = { it == DelayReading.Syncing || (it as? DelayReading.Unavailable)?.reason?.transient == true },
)
