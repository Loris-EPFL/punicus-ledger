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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
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
 * `txNonce()`, `queueNonce()` and its members, `getModulesPaginated` walked from the sentinel
 * `0x1` page by page. Every call must answer at one block, the same number and the same hash: a
 * set that straddles two is made again, up to [READ_TRIES] times in all, and is otherwise
 * unproven for that poll. It is [DelayFinding.Wired] only when its avatar, target and owner are
 * the Safe asked about, the one argument that decides whose Delay it is; otherwise
 * [DelayFinding.NotWired].
 *
 * A call the engine refuses is [ReadUnavailable.REFUSED]; one that reverts, answers something
 * that is not what the getter returns, or lists more than [MAX_MEMBERS] members, is unproven: a
 * pinned Delay's getters do neither. Polled, cached and refused as [ProvenReads] describes, keyed
 * by the Safe and the module: a Delay's reading stands for one head (its queue moves), another
 * module's for [NOT_DELAY_KEPT_FOR_HEADS], since its code cannot become a Delay's.
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
        keptForHeads = { finding -> if (finding == DelayFinding.NotDelay) NOT_DELAY_KEPT_FOR_HEADS else 1 },
    ) { verified, (safe, module) ->
        val code = verified.proveCode(module) ?: return@ProvenReads null
        val generation = proxies[code.codeHex.lowercase()]
            ?: return@ProvenReads EngineReading.Proven(DelayFinding.NotDelay, code.blockNumber)
        readDelay(verified, safe, module, generation)
    }

    /**
     * The Delay at [module], a proxy of a [generation] mastercopy, as its getters answer at one
     * block, read again while they straddle two; null when unproven.
     */
    private suspend fun readDelay(
        chain: VerifiedChain,
        safe: Address,
        module: Address,
        generation: DelayGeneration,
    ): EngineReading<DelayFinding>? {
        repeat(READ_TRIES) {
            currentCoroutineContext().ensureActive()
            when (val read = Getters(chain, module).read()) {
                Read.Straddled -> Unit
                Read.Unproven -> return null
                Read.Refused -> return REFUSED
                is Read.At -> {
                    val wired = read.avatar == safe && read.target == safe && read.owner == safe
                    val finding = if (!wired) {
                        DelayFinding.NotWired(generation)
                    } else {
                        // A pinned Delay keeps txNonce <= queueNonce and both far below this; anything
                        // else is no answer of its getters.
                        if (read.txNonce > read.queueNonce) return null
                        DelayFinding.Wired(
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
                    return EngineReading.Proven(finding, read.block, read.hash)
                }
            }
        }
        return null
    }

    /** One read of a Delay's getters, every call checked against the block of the first. */
    private class Getters(private val chain: VerifiedChain, private val module: Address) {
        private var block: Long? = null
        private var hash: String? = null

        /** What [data] returned, at the one block of this read; [Read.Straddled] etc. thrown through [Stop]. */
        private fun answer(data: ByteArray): String {
            val outcome = chain.call(module, data) ?: throw Stop(Read.Unproven)
            val returned = when (outcome) {
                is CallOutcome.Returned -> outcome
                is CallOutcome.Reverted -> throw Stop(Read.Unproven)
                CallOutcome.Refused -> throw Stop(Read.Refused)
            }
            val at = returned.blockNumber ?: throw Stop(Read.Unproven)
            val atHash = returned.blockHash ?: throw Stop(Read.Unproven)
            if (block == null) {
                block = at
                hash = atHash
            } else if (block != at || hash != atHash) {
                throw Stop(Read.Straddled)
            }
            return returned.hex
        }

        private fun address(data: ByteArray): Address = Abi.address(answer(data)) ?: throw Stop(Read.Unproven)

        private fun number(data: ByteArray): Long =
            Abi.uint256(answer(data))?.toLongOrNull() ?: throw Stop(Read.Unproven)

        fun read(): Read = try {
            val avatar = address(AVATAR)
            val target = address(TARGET)
            val owner = address(OWNER)
            val cooldown = number(TX_COOLDOWN)
            val expiration = number(TX_EXPIRATION)
            val txNonce = number(TX_NONCE)
            val queueNonce = number(QUEUE_NONCE)
            val members = members()
            Read.At(
                avatar,
                target,
                owner,
                cooldown,
                expiration,
                txNonce,
                queueNonce,
                members,
                checkNotNull(block),
                checkNotNull(hash),
            )
        } catch (stop: Stop) {
            stop.read
        }

        /** The modules enabled on the Delay, walked page by page from the sentinel, in its own order. */
        private fun members(): List<Address> {
            val found = mutableListOf<Address>()
            var start = SENTINEL
            while (true) {
                val page = Abi.addressPage(answer(getModulesPaginated(start, PAGE_SIZE)))
                    ?: throw Stop(Read.Unproven)
                found += page.addresses
                if (found.size > MAX_MEMBERS || found.toSet().size != found.size) throw Stop(Read.Unproven)
                if (page.next == SENTINEL) return found
                // A page that is not full ends the list; a next that is not its last entry is no page.
                if (page.addresses.size < PAGE_SIZE || page.next != page.addresses.last()) throw Stop(Read.Unproven)
                start = page.next
            }
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
            val hash: String,
        ) : Read

        /** Two calls answered at two blocks: read again. */
        data object Straddled : Read

        /** A call gave no verified answer, or not one a Delay gives. */
        data object Unproven : Read

        /** The engine refuses the calls on this build. */
        data object Refused : Read
    }

    /** Ends a read of the getters with [read]. */
    private class Stop(val read: Read) : Exception()

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

        /** Members asked for per page: Gnosis Pay enables one. */
        const val PAGE_SIZE = 10

        /** Far more members than a Delay has: a list longer than this is no list read here. */
        const val MAX_MEMBERS = 50

        /** Times a read of the getters is made while its calls straddle two blocks, the first included. */
        const val READ_TRIES = 3

        /** Heads another module's reading stands: about ten minutes of Gnosis Chain's blocks. */
        const val NOT_DELAY_KEPT_FOR_HEADS = 120L

        private val REFUSED = EngineReading.Unavailable(ReadUnavailable.REFUSED)
    }
}
