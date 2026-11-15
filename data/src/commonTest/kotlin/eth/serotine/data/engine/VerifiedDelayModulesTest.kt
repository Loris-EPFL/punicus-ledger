package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * [VerifiedDelayModules] against the answers of a real Gnosis Pay account, read on Gnosis Chain on
 * 2026-11-15 (a public node's, a debugging source only): the Safe
 * `0x188416e7d22a45e1c5f69c628d9c05bee54bd4fb` and its Delay `0xfc85bcbc2ce6aec1be9d94c77c872269ebddb6c7`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedDelayModulesTest {

    private val safe = Address.of("0x188416e7d22a45e1c5f69c628d9c05bee54bd4fb")
    private val delay = Address.of("0xfc85bcbc2ce6aec1be9d94c77c872269ebddb6c7")
    private val roles = Address.of("0x5c7516e3e2581d1274d28e97cccc3eda87af796d")
    private val member = Address.of("0xa52bddbc4145f89b3740266a74f2d81c358677aa")
    private val current = Address.of("0x22d903fd45F441F51bcad198D14eBa8a75EA1ef0")
    private val retired = Address.of("0x4A97E65188A950Dd4b0f21F9b5434dAeE0BBF9f5")
    private val rolesMastercopy = Address.of("0x732B9E9f259fbA6f65A1a012DC89c20872ffBd2f")

    private val fake = FakeChain()

    /** The block hash each call answers at, by the call's number; one block by default. */
    private var hashOfCall: (Int) -> String? = { "0x" + "b1".repeat(32) }
    private var calls = 0

    /** [fake], every call labelled with block 7 and the hash [hashOfCall] gives. */
    private val chain = object : VerifiedChain by fake {
        override fun call(to: Address, data: ByteArray): CallOutcome? =
            when (val outcome = fake.call(to, data)) {
                is CallOutcome.Returned -> outcome.copy(blockNumber = 7, blockHash = hashOfCall(calls++))
                else -> outcome
            }
    }

    private fun word(hex: String) = hex.removePrefix("0x").lowercase().padStart(64, '0')

    private fun answer(data: ByteArray, hex: String, to: Address = delay) {
        fake.calls[to to data.toHex()] = CallOutcome.Returned(hex, 7, null)
    }

    /** The Delay's getters as the real account answered them; [owner] and [members] to vary them. */
    private fun gnosisPayDelay(owner: Address = safe, members: List<Address> = listOf(member)) {
        fake.codes[delay] = ProvenCode(DelayDeployments.proxyCode(current), blockNumber = 7)
        answer(VerifiedDelayModules.AVATAR, word(safe.hex))
        answer(VerifiedDelayModules.TARGET, word(safe.hex))
        answer(VerifiedDelayModules.OWNER, word(owner.hex))
        answer(VerifiedDelayModules.TX_COOLDOWN, word("15f90"))
        answer(VerifiedDelayModules.TX_EXPIRATION, word("1b3f0"))
        answer(VerifiedDelayModules.TX_NONCE, word("0"))
        answer(VerifiedDelayModules.QUEUE_NONCE, word("0"))
        answer(
            VerifiedDelayModules.getModulesPaginated(
                VerifiedDelayModules.SENTINEL,
                VerifiedDelayModules.MAX_MEMBERS + 1,
            ),
            word("40") + word("1") + word(members.size.toString(16)) + members.joinToString("") { word(it.hex) },
        )
    }

    private fun TestScope.delays() =
        VerifiedDelayModules(Network.GNOSIS, chain, StandardTestDispatcher(testScheduler), 4.seconds)

    private suspend fun VerifiedDelayModules.settled(module: Address = delay) =
        observeDelay(Network.GNOSIS, safe, module).first()

    @Test
    fun theCallsAreTheDelaysOwnGetters() {
        // cast calldata "getModulesPaginated(address,uint256)" 0x0000000000000000000000000000000000000001 51
        assertEquals(
            "cc2f8452" + word("1") + word("33"),
            VerifiedDelayModules.getModulesPaginated(VerifiedDelayModules.SENTINEL, 51).toHex(),
        )
        // cast sig "txCooldown()", "queueNonce()"
        assertEquals("dcafac09", VerifiedDelayModules.TX_COOLDOWN.toHex())
        assertEquals("de8dd91d", VerifiedDelayModules.QUEUE_NONCE.toHex())
    }

    @Test
    fun theProxyCodeIsWhatTheFactoryDeployed() {
        // eth_getCode of the real account's Delay, 2026-11-15.
        assertEquals(
            "363d3d373d3d3d363d7322d903fd45f441f51bcad198d14eba8a75ea1ef05af43d82803e903d91602b57fd5bf3",
            DelayDeployments.proxyCode(current),
        )
        assertEquals(
            mapOf(current to DelayGeneration.CURRENT, retired to DelayGeneration.RETIRED),
            DelayDeployments.of(Network.GNOSIS),
        )
        assertEquals(emptyMap(), DelayDeployments.of(Network.ETHEREUM))
    }

    @Test
    fun aGnosisPayAccountsDelayIsReadAtOneBlock() = runTest {
        fake.snapshot = running(head = 7)
        gnosisPayDelay()

        assertEquals(
            DelayReading.Verified(
                DelayFinding.Wired(
                    DelayModule(delay, DelayGeneration.CURRENT, 90_000, 111_600, 0, 0, listOf(member)),
                ),
                7,
            ),
            delays().settled(),
        )
    }

    @Test
    fun theRetiredMastercopysProxyIsRecognisedAsSuch() = runTest {
        fake.snapshot = running(head = 7)
        gnosisPayDelay()
        fake.codes[delay] = ProvenCode(DelayDeployments.proxyCode(retired), blockNumber = 7)

        val finding = (delays().settled() as DelayReading.Verified).finding as DelayFinding.Wired
        assertEquals(DelayGeneration.RETIRED, finding.module.generation)
    }

    @Test
    fun anotherModulesCodeIsNoDelay() = runTest {
        fake.snapshot = running(head = 7)
        // The account's Roles modifier: a proxy too, of another mastercopy.
        fake.codes[roles] = ProvenCode(DelayDeployments.proxyCode(rolesMastercopy), blockNumber = 7)

        assertEquals(DelayReading.Verified(DelayFinding.NotDelay, 7), delays().settled(roles))
        // A proxy of a pinned mastercopy with a byte more is no proxy the factory deploys.
        fake.codes[roles] = ProvenCode(DelayDeployments.proxyCode(current) + "00", blockNumber = 7)
        assertEquals(DelayReading.Verified(DelayFinding.NotDelay, 7), delays().settled(roles))
    }

    @Test
    fun aDelayOwnedBySomeoneElseIsNotTheSafes() = runTest {
        fake.snapshot = running(head = 7)
        gnosisPayDelay(owner = member)

        assertEquals(
            DelayReading.Verified(DelayFinding.NotWired(DelayGeneration.CURRENT), 7),
            delays().settled(),
        )
        // Asked about another Safe, the same Delay is not that one's either: the Safe is applied.
        gnosisPayDelay()
        assertEquals(
            DelayReading.Verified(DelayFinding.NotWired(DelayGeneration.CURRENT), 7),
            delays().observeDelay(Network.GNOSIS, member, delay).first(),
        )
    }

    @Test
    fun anotherSafesDelayIsToldByItsFirstThreeGetters() = runTest {
        fake.snapshot = running(head = 7)
        gnosisPayDelay(owner = member)
        // Never set up as this Safe's: its members are no list read here, and nothing past the owner is asked.
        fake.calls.remove(delay to VerifiedDelayModules.TX_COOLDOWN.toHex())

        assertEquals(
            DelayReading.Verified(DelayFinding.NotWired(DelayGeneration.CURRENT), 7),
            delays().settled(),
        )
    }

    @Test
    fun callsThatStraddleTwoBlocksAreMadeAgainThenUnproven() = runTest {
        fake.snapshot = running(head = 7)
        gnosisPayDelay()
        // The head moves once, under the first read: the second read is of one block.
        hashOfCall = { call -> if (call == 3) "0x" + "b2".repeat(32) else "0x" + "b1".repeat(32) }
        assertTrue(delays().settled() is DelayReading.Verified)

        // It moves under every read.
        calls = 0
        hashOfCall = { call -> "0x" + (call % 2).toString().repeat(64) }
        assertEquals(DelayReading.Unavailable(ReadUnavailable.UNPROVEN), delays().settled())
    }

    @Test
    fun anEngineThatNamesNoHashIsHeldToTheBlockNumber() = runTest {
        fake.snapshot = running(head = 7)
        gnosisPayDelay()
        hashOfCall = { null }

        assertTrue(delays().settled() is DelayReading.Verified)
    }

    @Test
    fun aCallWithNoAnswerIsAskedAgainAfterAPause() = runTest {
        fake.snapshot = running(head = 7)
        gnosisPayDelay()
        val avatar = requireNotNull(fake.calls.remove(delay to VerifiedDelayModules.AVATAR.toHex()))
        val delays = delays()
        val reading = async { delays.settled() }
        // The first read finds no answer; the peer is back before the next one.
        testScheduler.advanceTimeBy(500)
        fake.calls[delay to VerifiedDelayModules.AVATAR.toHex()] = avatar

        assertTrue(reading.await() is DelayReading.Verified)
    }

    @Test
    fun aListTooLongToReadIsUnproven() = runTest {
        fake.snapshot = running(head = 7)
        gnosisPayDelay(members = (1..51).map { Address.of("0x" + it.toString(16).padStart(40, '0')) })

        assertEquals(DelayReading.Unavailable(ReadUnavailable.UNPROVEN), delays().settled())
    }

    @Test
    fun anAnswerNoDelayGivesIsUnproven() = runTest {
        fake.snapshot = running(head = 7)
        gnosisPayDelay()
        answer(VerifiedDelayModules.TX_NONCE, word("1"))
        assertEquals(DelayReading.Unavailable(ReadUnavailable.UNPROVEN), delays().settled())

        gnosisPayDelay()
        fake.calls[delay to VerifiedDelayModules.OWNER.toHex()] = CallOutcome.Reverted(7)
        assertEquals(DelayReading.Unavailable(ReadUnavailable.UNPROVEN), delays().settled())

        gnosisPayDelay(members = listOf(member, member))
        assertEquals(DelayReading.Unavailable(ReadUnavailable.UNPROVEN), delays().settled())
    }

    @Test
    fun aRefusalIsSaidAndANetworkNotServedIsRefused() = runTest {
        fake.snapshot = running(head = 7)
        gnosisPayDelay()
        fake.calls[delay to VerifiedDelayModules.AVATAR.toHex()] = CallOutcome.Refused
        assertEquals(DelayReading.Unavailable(ReadUnavailable.REFUSED), delays().settled())

        assertEquals(
            DelayReading.Unavailable(ReadUnavailable.NO_ENGINE),
            delays().observeDelay(Network.ETHEREUM, safe, delay).first(),
        )
        assertEquals(
            DelayReading.Unavailable(ReadUnavailable.NO_ENGINE),
            VerifiedDelayModules(Network.GNOSIS, null, StandardTestDispatcher(testScheduler), 4.seconds)
                .observeDelay(Network.GNOSIS, safe, delay)
                .first(),
        )
    }

    /** The queue of the Delay holding [hashes] from entry 2 on, each queued at 1000 + its nonce. */
    private fun queued(vararg hashes: String, txNonce: Long = 2) {
        answer(VerifiedDelayModules.TX_NONCE, word(txNonce.toString(16)))
        answer(VerifiedDelayModules.QUEUE_NONCE, word((txNonce + hashes.size).toString(16)))
        hashes.forEachIndexed { at, hash ->
            val nonce = txNonce + at
            answer(VerifiedDelayModules.txHash(nonce), hash)
            answer(VerifiedDelayModules.txCreatedAt(nonce), word((1_000 + nonce).toString(16)))
        }
    }

    @Test
    fun theQueueIsSearchedFromItsNextEntryForTheCallsHashAtOneBlock() = runTest {
        fake.snapshot = running(head = 7, headTimestamp = 5_000)
        gnosisPayDelay()
        val first = "ab".repeat(32)
        val second = "cd".repeat(32)
        queued(first, second)

        assertEquals(
            DelayQueueReading.Read(
                DelayQueue(
                    txNonce = 2,
                    queueNonce = 4,
                    cooldownSeconds = 90_000,
                    expirationSeconds = 111_600,
                    entry = DelayEntry(3, Digest.of(second), 1_003),
                    searched = 2,
                ),
                blockNumber = 7,
                headTime = 5_000,
            ),
            delays().queue(Network.GNOSIS, delay, Digest.of(second)),
        )
        // Not there: the whole queue searched, nothing found.
        val missing = delays().queue(Network.GNOSIS, delay, Digest.of("ef".repeat(32))) as DelayQueueReading.Read
        assertEquals(null, missing.queue.entry)
        assertTrue(missing.queue.searchedAll)
        // cast calldata "txHash(uint256)" 2
        assertEquals("427e232f" + word("2"), VerifiedDelayModules.txHash(2).toHex())
    }

    @Test
    fun aQueueIsReadOnlyOfTheCurrentMastercopysProxy() = runTest {
        fake.snapshot = running(head = 7, headTimestamp = 5_000)
        gnosisPayDelay()
        val first = Digest.of("ab".repeat(32))
        queued(first.hex)
        fake.codes[delay] = ProvenCode(DelayDeployments.proxyCode(retired), blockNumber = 7)
        assertEquals(
            DelayQueueReading.Unavailable(ReadUnavailable.UNPROVEN),
            delays().queue(Network.GNOSIS, delay, first),
        )

        fake.codes[delay] = ProvenCode(DelayDeployments.proxyCode(rolesMastercopy), blockNumber = 7)
        assertEquals(
            DelayQueueReading.Unavailable(ReadUnavailable.UNPROVEN),
            delays().queue(Network.GNOSIS, delay, first),
        )

        assertEquals(
            DelayQueueReading.Unavailable(ReadUnavailable.NO_ENGINE),
            delays().queue(Network.ETHEREUM, delay, first),
        )
    }

    @Test
    fun aLongQueueIsSearchedUpToItsBound() = runTest {
        fake.snapshot = running(head = 7, headTimestamp = 5_000)
        gnosisPayDelay()
        queued(*Array(DelayModules.MAX_QUEUE_READ + 3) { "ab".repeat(32) })

        val read = delays().queue(Network.GNOSIS, delay, Digest.of("cd".repeat(32))) as DelayQueueReading.Read
        assertEquals(null, read.queue.entry)
        assertEquals(DelayModules.MAX_QUEUE_READ, read.queue.searched)
        assertEquals(false, read.queue.searchedAll)
    }

    @Test
    fun aPageOfModulesIsReadExactly() {
        // The real Delay's getModulesPaginated(0x1, 100).
        val real = "0000000000000000000000000000000000000000000000000000000000000040" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "000000000000000000000000a52bddbc4145f89b3740266a74f2d81c358677aa"
        assertEquals(Abi.AddressPage(listOf(member), VerifiedDelayModules.SENTINEL), Abi.addressPage(real))
        // A word too many, a length that does not match, an offset that is not the head's.
        assertEquals(null, Abi.addressPage(real + word("0")))
        assertEquals(null, Abi.addressPage(real.dropLast(64)))
        assertEquals(null, Abi.addressPage(word("20") + real.drop(64)))
    }
}
