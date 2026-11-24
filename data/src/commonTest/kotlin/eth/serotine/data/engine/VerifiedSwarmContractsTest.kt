package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.data.swarm.PinnedSwarm
import eth.serotine.data.swarm.SwarmDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Chequebook
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.PostageState
import eth.serotine.domain.model.SwarmDeployment
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.ChequebookReading
import eth.serotine.domain.repository.PostageReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SwarmReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
    private val masterCode = "6004600055"
    private val bzzProxyCode = "6005600055"
    private val bzzImplementationCode = "6006600055"
    private val pins = PinnedSwarm(
        bzz = gnosis.bzz,
        bzzProxyCodeHash = codeHash(bzzProxyCode),
        bzzImplementation = gnosis.bzzImplementation,
        bzzImplementationCodeHash = codeHash(bzzImplementationCode),
        postageStamp = gnosis.postageStamp,
        postageStampCodeHash = codeHash(stampCode),
        chequebookFactory = gnosis.chequebookFactory,
        chequebookFactoryCodeHash = codeHash(factoryCode),
        chequebookMaster = gnosis.chequebookMaster,
        chequebookMasterCodeHash = codeHash(masterCode),
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
        fake.codes[pins.chequebookMaster] = ProvenCode(masterCode, blockNumber = 7)
        val deployedAnswer = word(if (deployed) "1" else "0")
        answer(pins.chequebookFactory, VerifiedSwarmContracts.deployedContracts(chequebook), deployedAnswer)
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
    fun aChequebookIsReadFreshForAWithdrawal() = runTest {
        theNodesChequebook()
        val balance = UInt256.of(12_000_000_000_000_000)
        thePostageStamp()
        val contracts = contracts()
        assertEquals(
            ChequebookReading.Read(
                ChequebookFinding.Node(Chequebook(chequebook, node, balance, balance, UInt256.of(0), bounced = false)),
                7,
            ),
            contracts.chequebook(Network.GNOSIS, node, chequebook),
        )
        // Read again at each ask: a cash-out since is seen at once, never a reading kept.
        answer(chequebook, VerifiedSwarmContracts.LIQUID_BALANCE, word("0"))
        val again = assertIs<ChequebookFinding.Node>(
            assertIs<ChequebookReading.Read>(contracts.chequebook(Network.GNOSIS, node, chequebook)).finding,
        )
        assertEquals(UInt256.of(0), again.chequebook.liquidBalance)
        assertEquals(
            ChequebookReading.Unavailable(ReadUnavailable.NO_ENGINE),
            contracts.chequebook(Network.ETHEREUM, node, chequebook),
        )
        assertEquals(ChequebookReading.NotPinned, contracts(pinned = null).chequebook(Network.GNOSIS, node, chequebook))
    }

    @Test
    fun aWithdrawalReadsNothingOnceXbzzIsNotThePinnedOne() = runTest {
        theNodesChequebook()
        thePostageStamp(implementation = Address.of("0x" + "99".repeat(20)))
        assertEquals(ChequebookReading.BzzChanged, contracts().chequebook(Network.GNOSIS, node, chequebook))
        // Upgraded between the slot's two reads: never passes unseen.
        thePostageStamp()
        var slotReads = 0
        val upgrading = object : VerifiedChain by chain {
            override fun storageAt(address: Address, slot: ByteArray): String? =
                if (slotReads++ == 0) chain.storageAt(address, slot) else word("99".repeat(20))
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val contracts = VerifiedSwarmContracts(Network.GNOSIS, upgrading, dispatcher, 4.seconds, pins)
        assertEquals(ChequebookReading.BzzChanged, contracts.chequebook(Network.GNOSIS, node, chequebook))
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
        // Only the code differs now: no call is made, the code alone says it is no chequebook.
        theNodesChequebook()
        fake.codes[chequebook] = ProvenCode(proxy + "00", blockNumber = 7)
        calls = 0
        assertEquals(
            SwarmReading.Verified(ChequebookFinding.NotAChequebook, 7),
            contracts().observeChequebook(Network.GNOSIS, node, chequebook).first(),
        )
        assertEquals(0, calls)
    }

    @Test
    fun aMatchOfOnePinNeverStandsInForAnother() = runTest {
        theNodesChequebook()
        val adapter = contracts()
        assertIs<ChequebookFinding.Node>(
            assertIs<SwarmReading.Verified<ChequebookFinding>>(
                adapter.observeChequebook(Network.GNOSIS, node, chequebook).first(),
            ).finding,
        )
        // The factory's code matched its own pin above; asked about as a chequebook, it is none.
        assertEquals(
            SwarmReading.Verified(ChequebookFinding.NotAChequebook, 7),
            adapter.observeChequebook(Network.GNOSIS, node, pins.chequebookFactory).first(),
        )
    }

    @Test
    fun aMatchIsRememberedAndNotProvenAgain() = runTest {
        theNodesChequebook()
        val adapter = contracts()
        adapter.observeChequebook(Network.GNOSIS, node, chequebook).first()
        val proofs = fake.codeCalls
        theBatch(owner = node)
        adapter.observeBatch(Network.GNOSIS, node, batch).first()
        adapter.observeChequebook(Network.GNOSIS, buyer, chequebook).first()
        assertEquals(proofs + 1, fake.codeCalls, "only the PostageStamp's code is proven anew")
    }

    @Test
    fun aGetterThatRevertsIsUnprovenAndNotReadAgain() = runTest {
        theNodesChequebook()
        fake.calls[chequebook to VerifiedSwarmContracts.ISSUER.toHex()] = CallOutcome.Reverted(7)
        calls = 0
        assertEquals(
            SwarmReading.Unavailable(ReadUnavailable.UNPROVEN),
            contracts().observeChequebook(Network.GNOSIS, node, chequebook).first(),
        )
        assertEquals(2, calls, "deployedContracts and token, once")
    }

    @Test
    fun aCallWithoutAnAnswerIsReadAgainAfterAPause() = runTest {
        theBatch()
        // No answer for remainingBalance in the first read: asked held to its block twice, then at
        // the head. The second read's first ask answers.
        val key = pins.postageStamp to VerifiedSwarmContracts.remainingBalance(batch).toHex()
        val answer = checkNotNull(fake.calls.remove(key))
        var reads = 0
        val adapter = VerifiedSwarmContracts(
            Network.GNOSIS,
            object : VerifiedChain by chain {
                override fun call(to: Address, data: ByteArray): CallOutcome? {
                    if (to to data.toHex() == key && ++reads == 4) fake.calls[key] = answer
                    return chain.call(to, data)
                }

                override fun callAt(to: Address, data: ByteArray, block: Long): CallOutcome? =
                    when (val outcome = call(to, data)) {
                        is CallOutcome.Returned -> outcome.copy(blockNumber = block)
                        else -> outcome
                    }
            },
            StandardTestDispatcher(testScheduler),
            4.seconds,
            pins,
        )
        val started = testScheduler.currentTime
        assertIs<SwarmReading.Verified<BatchFinding>>(adapter.observeBatch(Network.GNOSIS, buyer, batch).first())
        assertTrue(testScheduler.currentTime - started >= VerifiedSwarmContracts.RETRY_PAUSE.inWholeMilliseconds)
    }

    @Test
    fun answersNoPinnedContractGivesAreUnproven() = runTest {
        theNodesChequebook()
        answer(chequebook, VerifiedSwarmContracts.LIQUID_BALANCE, word("2aa1efb94e0001"))
        assertEquals(
            SwarmReading.Unavailable(ReadUnavailable.UNPROVEN),
            contracts().observeChequebook(Network.GNOSIS, node, chequebook).first(),
        )
        theBatch()
        // A bucket deeper than its batch.
        answer(
            pins.postageStamp,
            VerifiedSwarmContracts.batches(batch),
            word(buyer.hex) + word("10") + word("16") + word("1") + word("e802246f67") + word("2f221d9"),
        )
        assertEquals(
            SwarmReading.Unavailable(ReadUnavailable.UNPROVEN),
            contracts().observeBatch(Network.GNOSIS, buyer, batch).first(),
        )
    }

    @Test
    fun noBatchYetIsReadAgainAtTheNextHead() = runTest {
        theBatch(owner = Address.of("0x" + "0".repeat(40)))
        val adapter = contracts()
        assertEquals(
            SwarmReading.Verified(BatchFinding.Gone, 7),
            adapter.observeBatch(Network.GNOSIS, buyer, batch).first(),
        )
        // Its createBatch is mined: the next head finds it, however recently "none" was read.
        theBatch()
        fake.snapshot = running(head = 8)
        val found = adapter.observeBatch(Network.GNOSIS, buyer, batch).first()
        assertIs<BatchFinding.Node>(assertIs<SwarmReading.Verified<BatchFinding>>(found).finding)
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
    fun aMasterCopyOtherThanThePinnedOneProvesNothing() = runTest {
        theNodesChequebook()
        fake.codes[pins.chequebookMaster] = ProvenCode("6003600055", blockNumber = 7)
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
                    PostageBatch(
                        batch,
                        buyer,
                        depth = 22,
                        bucketDepth = 16,
                        immutable = true,
                        remainingPerChunk = UInt256.of(5_730_943_641),
                        lastPrice = UInt256.of(206_979),
                    ),
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
        assertEquals(
            SwarmReading.Verified(BatchFinding.Gone, 7),
            contracts().observeBatch(Network.GNOSIS, buyer, batch).first(),
        )
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

    /** The PostageStamp, xBZZ's proxy and the implementation its EIP-1967 slot names, as pinned. */
    private fun thePostageStamp(paused: Boolean = false, implementation: Address = pins.bzzImplementation) {
        fake.snapshot = running(head = 7)
        fake.codes[pins.postageStamp] = ProvenCode(stampCode, blockNumber = 7)
        fake.codes[pins.bzz] = ProvenCode(bzzProxyCode, blockNumber = 7)
        fake.codes[implementation] = ProvenCode(bzzImplementationCode, blockNumber = 7)
        fake.storage[pins.bzz to VerifiedSwarmContracts.EIP1967_IMPLEMENTATION.toHex()] = word(implementation.hex)
        answer(pins.postageStamp, VerifiedSwarmContracts.PAUSED, word(if (paused) "1" else "0"))
        answer(pins.postageStamp, VerifiedSwarmContracts.LAST_PRICE, word("328d6"))
        answer(pins.postageStamp, VerifiedSwarmContracts.MINIMUM_PER_CHUNK, word("d544f200"))
    }

    @Test
    fun thePostageStampsStateIsReadFreshAtOneBlock() = runTest {
        // minimumInitialBalancePerChunk()'s selector is proven on the real chain by MyotisHostGnosisTest.
        thePostageStamp()
        assertEquals(
            PostageReading.Read(PostageState(false, UInt256.of(207_062), UInt256.of(3_578_065_408)), 7),
            contracts().postage(Network.GNOSIS),
        )
        thePostageStamp(paused = true)
        assertEquals(true, (contracts().postage(Network.GNOSIS) as PostageReading.Read).state.paused)
    }

    @Test
    fun aBatchIsReadFreshWithThePostageStampsStateAtOneBlock() = runTest {
        thePostageStamp()
        theBatch()
        val read = contracts().postage(Network.GNOSIS, buyer, batch) as PostageReading.Read
        assertEquals(7, read.blockNumber)
        val found = assertIs<BatchFinding.Node>(read.batch)
        assertEquals(22, found.batch.depth)
        // Another owner's is said to be another's, at the same block.
        val others = contracts().postage(Network.GNOSIS, node, batch) as PostageReading.Read
        assertEquals(BatchFinding.OthersOwner(buyer), others.batch)
    }

    @Test
    fun anotherXbzzImplementationApprovesNothing() = runTest {
        thePostageStamp(implementation = Address.of("0x" + "99".repeat(20)))
        assertEquals(PostageReading.BzzChanged, contracts().postage(Network.GNOSIS))
        thePostageStamp()
        fake.codes[pins.bzz] = ProvenCode("6007600055", blockNumber = 7)
        assertEquals(PostageReading.BzzChanged, contracts().postage(Network.GNOSIS))
    }

    @Test
    fun anUpgradeOfXbzzBetweenTheTwoSlotReadsApprovesNothing() = runTest {
        thePostageStamp()
        val upgraded = word("99".repeat(20))
        var slotReads = 0
        // The slot names the pinned implementation before the state is read, another one after it.
        val upgrading = object : VerifiedChain by chain {
            override fun storageAt(address: Address, slot: ByteArray): String? =
                if (slotReads++ == 0) chain.storageAt(address, slot) else upgraded
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val contracts = VerifiedSwarmContracts(Network.GNOSIS, upgrading, dispatcher, 4.seconds, pins)
        assertEquals(PostageReading.BzzChanged, contracts.postage(Network.GNOSIS))
        assertEquals(2, slotReads)
    }

    @Test
    fun thePostageStampOfAnotherNetworkIsRefused() = runTest {
        assertEquals(PostageReading.Unavailable(ReadUnavailable.NO_ENGINE), contracts().postage(Network.ETHEREUM))
        assertEquals(PostageReading.NotPinned, contracts(Network.ETHEREUM, pinned = null).postage(Network.ETHEREUM))
    }

    @Test
    fun theDeploymentIsThePinnedOnesOfTheNetworkServed() = runTest {
        val deployment = contracts().deployment(Network.GNOSIS)
        assertEquals(pins.bzz, deployment?.bzz?.address)
        assertEquals("BZZ" to 16, deployment?.bzz?.let { it.symbol to it.decimals })
        assertEquals(pins.postageStamp, deployment?.postageStamp)
        assertEquals(null, contracts().deployment(Network.ETHEREUM))
        assertEquals(null, contracts(Network.ETHEREUM, pinned = null).deployment(Network.ETHEREUM))
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
