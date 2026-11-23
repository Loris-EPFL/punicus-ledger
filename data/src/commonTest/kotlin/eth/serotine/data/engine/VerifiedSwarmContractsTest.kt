package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.data.swarm.PinnedSwarm
import eth.serotine.data.swarm.SwarmDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Chequebook
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SwarmReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * [VerifiedSwarmContracts] against what Bee's contracts on Gnosis Chain answered on 2026-11-23 (a
 * public node's, a debugging source only): the chequebook `0x614f…7b77` of the node `0x0b52…0086`,
 * and the batch `0xaf13…8db2` of `0x369e…0c3e`. The PostageStamp's and the factory's code stand in
 * as short stand-ins, pinned by their own hash here; the chequebook's is the real proxy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedSwarmContractsTest {

    private val gnosis = checkNotNull(SwarmDeployments.of(Network.GNOSIS))
    private val stampCode = "6001600055"
    private val factoryCode = "6002600055"
    private val pins = PinnedSwarm(
        bzz = gnosis.bzz,
        bzzProxyCodeHash = gnosis.bzzProxyCodeHash,
        bzzImplementation = gnosis.bzzImplementation,
        bzzImplementationCodeHash = gnosis.bzzImplementationCodeHash,
        postageStamp = gnosis.postageStamp,
        postageStampCodeHash = codeHash(stampCode),
        chequebookFactory = gnosis.chequebookFactory,
        chequebookFactoryCodeHash = codeHash(factoryCode),
        chequebookCodeHash = gnosis.chequebookCodeHash,
    )

    private val node = Address.of("0x0b522dfb53807fd9a9a974ee19d2637205970086")
    private val chequebook = Address.of("0x614f90324e7ac11a08c29f3313de9f4c7e047b77")
    private val proxy = "363d3d373d3d3d363d733b6a7f0bdb6663007f95dbd7fddef815f75967155af43d82803e903d91602b57fd5bf3"
    private val buyer = Address.of("0x369e38fb83148fb269f74863006e0e6210fe0c3e")
    private val batch = BatchId.of("af132f8c833af8d2da19979a31603c6507435ea4ce1194adf14b45a54cc78db2")

    private val fake = FakeChain()

    /** The block hash each call answers at, by the call's number; one block by default. */
    private var hashOfCall: (Int) -> String? = { "0x" + "b1".repeat(32) }
    private var calls = 0

    /** [fake], every call labelled with block 7 and the hash [hashOfCall] gives; a held call at the block asked. */
    private val chain = object : VerifiedChain by fake {
        override fun call(to: Address, data: ByteArray): CallOutcome? =
            when (val outcome = fake.call(to, data)) {
                is CallOutcome.Returned -> outcome.copy(blockNumber = 7, blockHash = hashOfCall(calls++))
                else -> outcome
            }

        override fun callAt(to: Address, data: ByteArray, block: Long): CallOutcome? =
            when (val outcome = call(to, data)) {
                is CallOutcome.Returned -> outcome.copy(blockNumber = block)
                else -> outcome
            }
    }

    private fun word(hex: String) = hex.removePrefix("0x").lowercase().padStart(64, '0')

    private fun answer(to: Address, data: ByteArray, hex: String) {
        fake.calls[to to data.toHex()] = CallOutcome.Returned(hex, 7, null)
    }

    private fun theNodesChequebook(issuer: Address = node, deployed: Boolean = true) {
        fake.snapshot = running(head = 7)
        fake.codes[chequebook] = ProvenCode(proxy, blockNumber = 7)
        fake.codes[pins.chequebookFactory] = ProvenCode(factoryCode, blockNumber = 7)
        answer(pins.chequebookFactory, VerifiedSwarmContracts.deployedContracts(chequebook), word(if (deployed) "1" else "0"))
        answer(chequebook, VerifiedSwarmContracts.TOKEN, word(pins.bzz.hex))
        answer(chequebook, VerifiedSwarmContracts.ISSUER, word(issuer.hex))
        answer(chequebook, VerifiedSwarmContracts.BALANCE, word("2aa1efb94e0000"))
        answer(chequebook, VerifiedSwarmContracts.LIQUID_BALANCE, word("2aa1efb94e0000"))
        answer(chequebook, VerifiedSwarmContracts.TOTAL_PAID_OUT, word("0"))
        answer(chequebook, VerifiedSwarmContracts.BOUNCED, word("0"))
    }

    /** `batches(id)` of the real batch: owner, depth 22, bucket depth 16, immutable, its balance and last update. */
    private fun theBatch(owner: Address = buyer) {
        fake.snapshot = running(head = 7)
        fake.codes[pins.postageStamp] = ProvenCode(stampCode, blockNumber = 7)
        answer(
            pins.postageStamp,
            VerifiedSwarmContracts.batches(batch),
            word(owner.hex) + word("16") + word("10") + word("1") + word("e802246f67") + word("2f221d9"),
        )
        answer(pins.postageStamp, VerifiedSwarmContracts.remainingBalance(batch), word("155974299"))
        answer(pins.postageStamp, VerifiedSwarmContracts.LAST_PRICE, word("32883"))
    }

    private fun TestScope.contracts(served: Network = Network.GNOSIS, pinned: PinnedSwarm? = pins) =
        VerifiedSwarmContracts(served, chain, StandardTestDispatcher(testScheduler), 4.seconds, pinned)

    @Test
    fun theCallsAreTheContractsOwnGetters() {
        // cast sig "issuer()", "liquidBalance()", "bounced()", "lastPrice()"
        assertEquals("1d143848", VerifiedSwarmContracts.ISSUER.toHex())
        assertEquals("b7ec1a33", VerifiedSwarmContracts.LIQUID_BALANCE.toHex())
        assertEquals("b648b417", VerifiedSwarmContracts.BOUNCED.toHex())
        assertEquals("053f14da", VerifiedSwarmContracts.LAST_PRICE.toHex())
        // cast calldata "batches(bytes32)" / "deployedContracts(address)"
        assertEquals("c81e25ab" + batch.hex, VerifiedSwarmContracts.batches(batch).toHex())
        assertEquals("c70242ad" + word(chequebook.hex), VerifiedSwarmContracts.deployedContracts(chequebook).toHex())
    }

    @Test
    fun theNodesChequebookIsReadAtOneBlock() = runTest {
        theNodesChequebook()
        val balance = UInt256.of(12_000_000_000_000_000)
        assertEquals(
            SwarmReading.Verified(
                ChequebookFinding.Node(Chequebook(chequebook, node, balance, balance, UInt256.of(0), bounced = false)),
                7,
            ),
            contracts().observeChequebook(Network.GNOSIS, node, chequebook).first(),
        )
    }

    @Test
    fun anotherIssuersChequebookIsNotTheNodes() = runTest {
        theNodesChequebook(issuer = buyer)
        assertEquals(
            SwarmReading.Verified(ChequebookFinding.OthersIssuer(buyer), 7),
            contracts().observeChequebook(Network.GNOSIS, node, chequebook).first(),
        )
    }

    @Test
    fun otherCodeOrOneTheFactoryDidNotDeployIsNoChequebook() = runTest {
        theNodesChequebook(deployed = false)
        assertEquals(
            SwarmReading.Verified(ChequebookFinding.NotAChequebook, 7),
            contracts().observeChequebook(Network.GNOSIS, node, chequebook).first(),
        )
        fake.codes[chequebook] = ProvenCode(proxy + "00", blockNumber = 7)
        assertEquals(
            SwarmReading.Verified(ChequebookFinding.NotAChequebook, 7),
            contracts().observeChequebook(Network.GNOSIS, node, chequebook).first(),
        )
    }

    @Test
    fun aFactoryOtherThanThePinnedOneProvesNothing() = runTest {
        theNodesChequebook()
        fake.codes[pins.chequebookFactory] = ProvenCode("6003600055", blockNumber = 7)
        assertEquals(
            SwarmReading.Unavailable(ReadUnavailable.UNPROVEN),
            contracts().observeChequebook(Network.GNOSIS, node, chequebook).first(),
        )
    }

    @Test
    fun theNodesBatchIsReadWithThePriceAtTheSameBlock() = runTest {
        theBatch()
        assertEquals(
            SwarmReading.Verified(
                BatchFinding.Node(
                    PostageBatch(batch, buyer, 22, 16, immutable = true, UInt256.of(5_730_943_641), UInt256.of(206_979)),
                ),
                7,
            ),
            contracts().observeBatch(Network.GNOSIS, buyer, batch).first(),
        )
    }

    @Test
    fun anotherOwnersBatchAndNoBatchAreSaidAsSuch() = runTest {
        theBatch()
        assertEquals(
            SwarmReading.Verified(BatchFinding.OthersOwner(buyer), 7),
            contracts().observeBatch(Network.GNOSIS, node, batch).first(),
        )
        theBatch(owner = Address.of("0x" + "0".repeat(40)))
        assertEquals(SwarmReading.Verified(BatchFinding.Gone, 7), contracts().observeBatch(Network.GNOSIS, buyer, batch).first())
    }

    @Test
    fun aPostageStampOtherThanThePinnedOneIsNotRead() = runTest {
        theBatch()
        fake.codes[pins.postageStamp] = ProvenCode("6003600055", blockNumber = 7)
        assertEquals(
            SwarmReading.Unavailable(ReadUnavailable.UNPROVEN),
            contracts().observeBatch(Network.GNOSIS, buyer, batch).first(),
        )
    }

    @Test
    fun callsThatStraddleTwoBlocksAreMadeAgain() = runTest {
        theBatch()
        // The second call of the first read answers at another block: that read is thrown away.
        hashOfCall = { if (it == 1) "0x" + "b2".repeat(32) else "0x" + "b1".repeat(32) }
        val reading = contracts().observeBatch(Network.GNOSIS, buyer, batch).first()
        assertEquals(7, (reading as SwarmReading.Verified).blockNumber)
        assertEquals(5, calls, "the first read ends at its second call, the second makes all three")
    }

    @Test
    fun aRefusedCallIsARefusal() = runTest {
        theBatch()
        fake.calls[pins.postageStamp to VerifiedSwarmContracts.batches(batch).toHex()] = CallOutcome.Refused
        assertEquals(
            SwarmReading.Unavailable(ReadUnavailable.REFUSED),
            contracts().observeBatch(Network.GNOSIS, buyer, batch).first(),
        )
    }

    @Test
    fun aNetworkWithoutPinsHasNothingToReadAndAnotherIsRefused() = runTest {
        assertEquals(
            SwarmReading.NotPinned,
            contracts(Network.ETHEREUM, pinned = null).observeBatch(Network.ETHEREUM, buyer, batch).first(),
        )
        assertEquals(
            SwarmReading.Unavailable(ReadUnavailable.NO_ENGINE),
            contracts().observeChequebook(Network.ETHEREUM, node, chequebook).first(),
        )
    }
}
