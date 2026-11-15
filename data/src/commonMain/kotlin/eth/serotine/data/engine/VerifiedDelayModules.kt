package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.safe.DelayDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.DelayEntry
import eth.serotine.domain.repository.DelayFinding
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.DelayModule
import eth.serotine.domain.repository.DelayModules
import eth.serotine.domain.repository.DelayQueue
import eth.serotine.domain.repository.DelayQueueReading
import eth.serotine.domain.repository.DelayReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [DelayModules] over a verified light client (docs/gnosis-pay.md, *Detection*). A module is a
 * Delay when its code, proven by [VerifiedChain.proveCode], is byte for byte a minimal proxy of a
 * mastercopy pinned for [served] ([mastercopies], [DelayDeployments]); any other code is
 * [DelayFinding.NotDelay]. A Delay's settings are its own getters, run as verified calls
 * ([VerifiedChain.call]): `avatar()`, `target()`, `owner()`, `txCooldown()`, `txExpiration()`,
 * `txNonce()`, `queueNonce()` and its members, one page of `getModulesPaginated` from the sentinel
 * `0x1`. Every call must answer at one block, the same number and the same hash where the engine
 * names one: a set that straddles two is made again, as is one a call gave no answer in, after a
 * pause, up to [READ_TRIES] reads in all, and is otherwise unproven for that poll. It is
 * [DelayFinding.Wired] only when its avatar, target and owner are the Safe asked about, the one
 * argument that decides whose Delay it is; otherwise [DelayFinding.NotWired].
 *
 * A call the engine refuses is [ReadUnavailable.REFUSED]; one that reverts, answers something
 * that is not what the getter returns, or lists more than [MAX_MEMBERS] members, is unproven: a
 * pinned Delay's getters do neither. A Delay that is another's is told by its first three getters
 * alone. Polled, cached and refused as [ProvenReads] describes, keyed
 * by the Safe and the module: the Safe's own Delay's reading stands for [DELAY_KEPT_FOR_HEADS]
 * heads, any other for [NOT_DELAY_KEPT_FOR_HEADS].
 */
class VerifiedDelayModules internal constructor(
    private val served: Network,
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    private val mastercopies: Map<Address, DelayGeneration> = DelayDeployments.of(served),
) : DelayModules {

    private val proxies: Map<String, DelayGeneration> =
        mastercopies.mapKeys { (mastercopy, _) -> DelayDeployments.proxyCode(mastercopy) }

    private val reads = ProvenReads<Pair<Address, Address>, DelayFinding>(
        chain,
        dispatcher,
        pollInterval,
        keptForHeads = { if (it is DelayFinding.Wired) DELAY_KEPT_FOR_HEADS else NOT_DELAY_KEPT_FOR_HEADS },
    ) { verified, (safe, module) ->
        val code = verified.proveCode(module) ?: return@ProvenReads null
        val generation = proxies[code.codeHex.lowercase()]
            ?: return@ProvenReads EngineReading.Proven(DelayFinding.NotDelay, code.blockNumber)
        readDelay(verified, safe, module, generation)
    }

    /**
     * The Delay at [module], a proxy of a [generation] mastercopy, as its getters answer at one
     * block: read again at once when they straddle two (the head moved), after [RETRY_PAUSE] when
     * a call gave no answer, [READ_TRIES] reads in all; null when unproven.
     */
    private suspend fun readDelay(
        chain: VerifiedChain,
        safe: Address,
        module: Address,
        generation: DelayGeneration,
    ): EngineReading<DelayFinding>? {
        for (attempt in 1..READ_TRIES) {
            currentCoroutineContext().ensureActive()
            when (val read = Getters(chain, module).settings(safe)) {
                is Read.Done ->
                    return EngineReading.Proven(finding(read.value, module, generation), read.block, read.hash)
                is Read.Stopped -> when (read.ended) {
                    Ended.STRADDLED -> Unit
                    Ended.NO_ANSWER -> if (attempt < READ_TRIES) delay(RETRY_PAUSE)
                    Ended.NOT_A_DELAYS_ANSWER -> return null
                    Ended.REFUSED -> return REFUSED
                }
            }
        }
        return null
    }

    /** What [settings] make of the Delay at [module], a proxy of a [generation] mastercopy. */
    private fun finding(settings: Settings, module: Address, generation: DelayGeneration): DelayFinding {
        val own = settings as? Settings.Own ?: return DelayFinding.NotWired(generation)
        return DelayFinding.Wired(
            DelayModule(module, generation, own.cooldown, own.expiration, own.txNonce, own.queueNonce, own.members),
        )
    }

    /**
     * One read of a Delay's getters, every call checked against the block of the first (its number,
     * and its hash where the engine names one). Each step returns null once the read has ended,
     * with [ended] saying how.
     */
    private class Getters(private val chain: VerifiedChain, private val module: Address) {
        private var block: Long? = null
        private var hash: String? = null
        private var first = true

        /** How the read ended early; set with the null of the step that ended it. */
        private var ended: Ended = Ended.NO_ANSWER

        private fun <T> end(why: Ended): T? {
            ended = why
            return null
        }

        private fun <T> done(value: T): Read<T> = Read.Done(value, checkNotNull(block), hash)

        private val stopped: Read<Nothing> get() = Read.Stopped(ended)

        /** What [data] returned, at the one block of this read. */
        private fun answer(data: ByteArray): String? {
            val returned = when (val outcome = chain.call(module, data)) {
                null -> return end(Ended.NO_ANSWER)
                is CallOutcome.Returned -> outcome
                // A pinned Delay's getters never revert.
                is CallOutcome.Reverted -> return end(Ended.NOT_A_DELAYS_ANSWER)
                CallOutcome.Refused -> return end(Ended.REFUSED)
            }
            val at = returned.blockNumber ?: return end(Ended.NO_ANSWER)
            if (first) {
                first = false
                block = at
                hash = returned.blockHash
            } else if (block != at || hash != returned.blockHash) {
                return end(Ended.STRADDLED)
            }
            return returned.hex
        }

        private fun address(data: ByteArray): Address? {
            val hex = answer(data) ?: return null
            return Abi.address(hex) ?: end(Ended.NOT_A_DELAYS_ANSWER)
        }

        private fun number(data: ByteArray): Long? {
            val hex = answer(data) ?: return null
            return Abi.uint256(hex)?.toLongOrNull() ?: end(Ended.NOT_A_DELAYS_ANSWER)
        }

        /**
         * The modules enabled on the Delay, in its own order: one page from the sentinel, one entry
         * longer than [MAX_MEMBERS] allows, so that a full page means a list too long to read here.
         * One page reads the same under every Zodiac release, whatever `next` means in it.
         */
        private fun members(): List<Address>? {
            val hex = answer(getModulesPaginated(SENTINEL, MAX_MEMBERS + 1)) ?: return null
            val page = Abi.addressPage(hex) ?: return end(Ended.NOT_A_DELAYS_ANSWER)
            val members = page.addresses
            if (members.size > MAX_MEMBERS || members.toSet().size != members.size || page.next != SENTINEL) {
                return end(Ended.NOT_A_DELAYS_ANSWER)
            }
            return members
        }

        /**
         * Where the call of hash [wanted] stands, at one block: avatar, target and owner first, which
         * must be [safe] (null otherwise); the nonces and periods, then `txHash(n)` from the
         * next entry on until the first of that hash, at most [DelayModules.MAX_QUEUE_READ] of them,
         * and that one's `txCreatedAt(n)`, all at the one block.
         */
        fun queue(safe: Address, wanted: Digest): Read<DelayQueue?> {
            val avatar = address(AVATAR) ?: return stopped
            val target = address(TARGET) ?: return stopped
            val owner = address(OWNER) ?: return stopped
            // Another's Delay, at the block the queue would be read at: no queue of the Safe's.
            if (avatar != safe || target != safe || owner != safe) return done(null)
            val txNonce = number(TX_NONCE) ?: return stopped
            val queueNonce = number(QUEUE_NONCE) ?: return stopped
            // A pinned Delay keeps its next entry within its queue.
            if (txNonce > queueNonce) return Read.Stopped(Ended.NOT_A_DELAYS_ANSWER)
            val cooldown = number(TX_COOLDOWN) ?: return stopped
            val expiration = number(TX_EXPIRATION) ?: return stopped
            val last = minOf(queueNonce, txNonce + DelayModules.MAX_QUEUE_READ)
            var nonce = txNonce
            while (nonce < last) {
                val hex = answer(txHash(nonce)) ?: return stopped
                val entryHash = Digest.parse(hex) ?: return Read.Stopped(Ended.NOT_A_DELAYS_ANSWER)
                if (entryHash == wanted) {
                    val createdAt = number(txCreatedAt(nonce)) ?: return stopped
                    val entry = DelayEntry(nonce, entryHash, createdAt)
                    val searched = (nonce - txNonce + 1).toInt()
                    return done(DelayQueue(txNonce, queueNonce, cooldown, expiration, entry, searched))
                }
                nonce++
            }
            return done(DelayQueue(txNonce, queueNonce, cooldown, expiration, null, (last - txNonce).toInt()))
        }

        /**
         * The Delay's settings and members; or, once its avatar, target or owner is not [safe],
         * [Settings.Foreign] at once, at the block of those three: nothing more of another's Delay
         * is read.
         */
        fun settings(safe: Address): Read<Settings> {
            val avatar = address(AVATAR) ?: return stopped
            val target = address(TARGET) ?: return stopped
            val owner = address(OWNER) ?: return stopped
            if (avatar != safe || target != safe || owner != safe) return done(Settings.Foreign)
            val cooldown = number(TX_COOLDOWN) ?: return stopped
            val expiration = number(TX_EXPIRATION) ?: return stopped
            val txNonce = number(TX_NONCE) ?: return stopped
            val queueNonce = number(QUEUE_NONCE) ?: return stopped
            // A pinned Delay keeps its next entry within its queue.
            if (txNonce > queueNonce) return Read.Stopped(Ended.NOT_A_DELAYS_ANSWER)
            val members = members() ?: return stopped
            return done(Settings.Own(cooldown, expiration, txNonce, queueNonce, members))
        }
    }

    /** A Delay's settings, as its getters answer them at one block. */
    private sealed interface Settings {
        /** The Safe's own Delay: avatar, target and owner the Safe, with its periods, queue and members. */
        data class Own(
            val cooldown: Long,
            val expiration: Long,
            val txNonce: Long,
            val queueNonce: Long,
            val members: List<Address>,
        ) : Settings

        /** A Delay whose avatar, target or owner is not the Safe asked about. */
        data object Foreign : Settings
    }

    /** How one read of the getters ended: [Done] with what it read at [Done.block], or [Stopped]. */
    private sealed interface Read<out T> {
        data class Done<T>(val value: T, val block: Long, val hash: String?) : Read<T>
        data class Stopped(val ended: Ended) : Read<Nothing>
    }

    /** Why a read of the getters stopped short. */
    private enum class Ended {
        /** Two calls answered at two blocks: read again. */
        STRADDLED,

        /** A call gave no verified answer this time. */
        NO_ANSWER,

        /** A call answered what no pinned Delay's getter answers: unproven, not read again. */
        NOT_A_DELAYS_ANSWER,

        /** The engine refuses the calls on this build. */
        REFUSED,
    }

    /**
     * Where the call of hash [hash] stands in the queue of the Delay at [delay], read fresh: only a
     * proxy of the current mastercopy, its code proven first, is read (owner's decisions,
     * 2026-11-15: nothing is sent through another), and only as [safe]'s own, its avatar, target
     * and owner read at the queue's block; the getters at one block, read again at once
     * while they straddle two or a call gives no answer, [READ_TRIES] reads in all; the head's time
     * read before them, so that a cooldown is never judged over before it is, and an expiration
     * judged with the margin the use case keeps.
     */
    override suspend fun queue(network: Network, safe: Address, delay: Address, hash: Digest): DelayQueueReading {
        if (network != served) return DelayQueueReading.Unavailable(ReadUnavailable.NO_ENGINE)
        return chain.askVerified(dispatcher, DelayQueueReading::Unavailable) { verified ->
            val unproven = DelayQueueReading.Unavailable(ReadUnavailable.UNPROVEN)
            val headTime = (verified.snapshot() as? EngineSnapshot.Running)?.headTimestamp
                ?: return@askVerified unproven
            val code = verified.proveCode(delay) ?: return@askVerified unproven
            val generation = proxies[code.codeHex.lowercase()]
            if (generation != DelayGeneration.CURRENT) {
                return@askVerified DelayQueueReading.NotTheSafes(generation, code.blockNumber)
            }
            repeat(READ_TRIES) {
                when (val read = Getters(verified, delay).queue(safe, hash)) {
                    is Read.Done -> return@askVerified read.value
                        ?.let { DelayQueueReading.Read(it, read.block, headTime) }
                        ?: DelayQueueReading.NotTheSafes(DelayGeneration.CURRENT, read.block)
                    is Read.Stopped -> when (read.ended) {
                        Ended.STRADDLED, Ended.NO_ANSWER -> Unit
                        Ended.NOT_A_DELAYS_ANSWER -> return@askVerified unproven
                        Ended.REFUSED -> return@askVerified DelayQueueReading.Unavailable(ReadUnavailable.REFUSED)
                    }
                }
            }
            unproven
        }
    }

    override fun observeDelay(network: Network, safe: Address, module: Address): Flow<DelayReading> {
        if (network != served) return flowOf(DelayReading.Unavailable(ReadUnavailable.NO_ENGINE))
        return reads.observe(safe to module).map { reading ->
            when (reading) {
                EngineReading.Syncing, is EngineReading.LastKnown -> DelayReading.Syncing
                is EngineReading.Proven -> DelayReading.Verified(reading.value, reading.blockNumber)
                is EngineReading.Unavailable -> DelayReading.Unavailable(reading.reason)
            }
        }
    }

    internal companion object {
        // The Delay's getters (zodiac-modifier-delay's `Delay`, `Modifier`, `Module`, `OwnableUpgradeable`).
        val AVATAR: ByteArray = "5aef7de6".fromHex()
        val TARGET: ByteArray = "d4b83992".fromHex()
        val OWNER: ByteArray = "8da5cb5b".fromHex()
        val TX_COOLDOWN: ByteArray = "dcafac09".fromHex()
        val TX_EXPIRATION: ByteArray = "605df59c".fromHex()
        val TX_NONCE: ByteArray = "c66323e5".fromHex()
        val QUEUE_NONCE: ByteArray = "de8dd91d".fromHex()

        /** `txHash(uint256 nonce)`: the hash of queue entry [nonce]. */
        fun txHash(nonce: Long): ByteArray = ("427e232f" + nonce.toString(16).padStart(64, '0')).fromHex()

        /** `txCreatedAt(uint256 nonce)`: when queue entry [nonce] was queued. */
        fun txCreatedAt(nonce: Long): ByteArray = ("258148cc" + nonce.toString(16).padStart(64, '0')).fromHex()

        /** `getModulesPaginated(address start, uint256 pageSize)`. */
        fun getModulesPaginated(start: Address, pageSize: Int): ByteArray =
            ("cc2f8452" + "0".repeat(24) + start.hex.removePrefix("0x").lowercase() +
                pageSize.toString(16).padStart(64, '0')).fromHex()

        /** The modules' sentinel, `0x…01`, where the list starts and ends. */
        val SENTINEL: Address = Address.of("0x" + "0".repeat(39) + "1")

        /** Far more members than a Delay has (Gnosis Pay enables one): a longer list is no list read here. */
        const val MAX_MEMBERS = 50

        /**
         * Reads of the getters made while their calls straddle two blocks or one gives no answer, the
         * first included: eight calls against Gnosis Chain's five-second heads straddle now and then.
         */
        const val READ_TRIES = 5

        /** The pause before the getters are read again after a call gave no answer: no tight loop on a lost peer. */
        val RETRY_PAUSE = 1.seconds

        /**
         * Heads the Safe's own Delay's reading stands, about a minute of Gnosis Chain's. A kept reading
         * is served to every subscriber within them, a new one included ([ProvenReads]): a change the
         * Safe's own transactions make to the Delay (its owner, its members) shows up to that late, and
         * its nonces are a screen's, never what an execution is checked against. That check reads the
         * queue with calls of its own, at one block, when it is made (docs/gnosis-pay.md, *Sending*).
         */
        const val DELAY_KEPT_FOR_HEADS = 12L

        /**
         * Heads another module's reading, or a Delay not the Safe's, stands: about ten minutes. Code
         * cannot change, and a Delay's owner changes only by its owner's own transaction.
         */
        const val NOT_DELAY_KEPT_FOR_HEADS = 120L

        private val REFUSED = EngineReading.Unavailable(ReadUnavailable.REFUSED)
    }
}
