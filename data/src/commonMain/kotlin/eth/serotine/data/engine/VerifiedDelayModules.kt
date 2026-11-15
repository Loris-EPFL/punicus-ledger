package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.safe.DelayDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.DelayFinding
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.DelayModule
import eth.serotine.domain.repository.DelayModules
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
 * pinned Delay's getters do neither. Polled, cached and refused as [ProvenReads] describes, keyed
 * by the Safe and the module: the Safe's own Delay's reading stands for [DELAY_KEPT_FOR_HEADS]
 * heads, any other for [NOT_DELAY_KEPT_FOR_HEADS].
 */
class VerifiedDelayModules internal constructor(
    private val served: Network,
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
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
            when (val read = Getters(chain, module).read()) {
                Read.Straddled -> Unit
                Read.NoAnswer -> if (attempt < READ_TRIES) delay(RETRY_PAUSE)
                Read.NotADelaysAnswer -> return null
                Read.Refused -> return REFUSED
                is Read.At ->
                    return EngineReading.Proven(finding(read, safe, module, generation), read.block, read.hash)
            }
        }
        return null
    }

    /** What [read] makes of the Delay at [module]: [safe]'s own when its avatar, target and owner are [safe]. */
    private fun finding(read: Read.At, safe: Address, module: Address, generation: DelayGeneration): DelayFinding {
        if (read.avatar != safe || read.target != safe || read.owner != safe) return DelayFinding.NotWired(generation)
        return DelayFinding.Wired(
            DelayModule(
                module,
                generation,
                read.cooldown,
                read.expiration,
                read.txNonce,
                read.queueNonce,
                read.members,
            ),
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

        /** How the read ended early; set once, with a null from the step that ended it. */
        var ended: Read = Read.NoAnswer
            private set

        private fun <T> end(read: Read): T? {
            ended = read
            return null
        }

        /** What [data] returned, at the one block of this read. */
        private fun answer(data: ByteArray): String? {
            val returned = when (val outcome = chain.call(module, data)) {
                null -> return end(Read.NoAnswer)
                is CallOutcome.Returned -> outcome
                // A pinned Delay's getters never revert.
                is CallOutcome.Reverted -> return end(Read.NotADelaysAnswer)
                CallOutcome.Refused -> return end(Read.Refused)
            }
            val at = returned.blockNumber ?: return end(Read.NoAnswer)
            if (first) {
                first = false
                block = at
                hash = returned.blockHash
            } else if (block != at || hash != returned.blockHash) {
                return end(Read.Straddled)
            }
            return returned.hex
        }

        private fun address(data: ByteArray): Address? {
            val hex = answer(data) ?: return null
            return Abi.address(hex) ?: end(Read.NotADelaysAnswer)
        }

        private fun number(data: ByteArray): Long? {
            val hex = answer(data) ?: return null
            return Abi.uint256(hex)?.toLongOrNull() ?: end(Read.NotADelaysAnswer)
        }

        /**
         * The modules enabled on the Delay, in its own order: one page from the sentinel, one entry
         * longer than [MAX_MEMBERS] allows, so that a full page means a list too long to read here.
         * One page reads the same under every Zodiac release, whatever `next` means in it.
         */
        private fun members(): List<Address>? {
            val hex = answer(getModulesPaginated(SENTINEL, MAX_MEMBERS + 1)) ?: return null
            val page = Abi.addressPage(hex) ?: return end(Read.NotADelaysAnswer)
            val members = page.addresses
            if (members.size > MAX_MEMBERS || members.toSet().size != members.size || page.next != SENTINEL) {
                return end(Read.NotADelaysAnswer)
            }
            return members
        }

        fun read(): Read {
            val avatar = address(AVATAR) ?: return ended
            val target = address(TARGET) ?: return ended
            val owner = address(OWNER) ?: return ended
            val cooldown = number(TX_COOLDOWN) ?: return ended
            val expiration = number(TX_EXPIRATION) ?: return ended
            val txNonce = number(TX_NONCE) ?: return ended
            val queueNonce = number(QUEUE_NONCE) ?: return ended
            // A pinned Delay keeps its next entry within its queue.
            if (txNonce > queueNonce) return Read.NotADelaysAnswer
            val members = members() ?: return ended
            return Read.At(
                avatar,
                target,
                owner,
                cooldown,
                expiration,
                txNonce,
                queueNonce,
                members,
                checkNotNull(block),
                hash,
            )
        }
    }

    /** How one read of the getters ended. */
    private sealed interface Read {
        data class At(
            val avatar: Address,
            val target: Address,
            val owner: Address,
            val cooldown: Long,
            val expiration: Long,
            val txNonce: Long,
            val queueNonce: Long,
            val members: List<Address>,
            val block: Long,
            val hash: String?,
        ) : Read

        /** Two calls answered at two blocks: read again. */
        data object Straddled : Read

        /** A call gave no verified answer this time. */
        data object NoAnswer : Read

        /** A call answered what no pinned Delay's getter answers: unproven, not read again. */
        data object NotADelaysAnswer : Read

        /** The engine refuses the calls on this build. */
        data object Refused : Read
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
         * Heads a Delay's reading stands, about a minute of Gnosis Chain's: what a screen shows. A
         * quote, a review and a check before sending subscribe anew and read it fresh ([ProvenReads]).
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
