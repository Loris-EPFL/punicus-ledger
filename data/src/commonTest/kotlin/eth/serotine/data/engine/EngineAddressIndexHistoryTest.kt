package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexHeadGap
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexReading
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** [EngineAddressIndexHistory] against the port's contract, over a fake engine that answers as Myotis documents. */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineAddressIndexHistoryTest {

    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))

    /** A made-up importer key, a test fixture: no release pins it. */
    private val fixtureImporter = Address.of("0x" + "1e".repeat(20))

    private class FakeChain : AddressHistoryChain {
        var snapshot: EngineSnapshot = running(EngineSnapshot.Phase.SYNCED)
        var supports: Boolean? = true
        var page: String? = null
        var check: String? = null
        var snapshotThrows = false
        var supportsThrows = false
        val pagesAsked = mutableListOf<Pair<String, String>>()
        val checksAsked = mutableListOf<Pair<String, String>>()

        override fun snapshot(): EngineSnapshot = if (snapshotThrows) error("status over JNA failed") else snapshot
        override fun supportsAddressHistory(): Boolean? =
            if (supportsThrows) error("supportsAddressHistory over JNA failed") else supports
        override fun addressHistory(address: String, optionsJson: String): String? {
            pagesAsked += address to optionsJson
            return page
        }

        override fun verifyAddressHistory(address: String, entriesJson: String): String? {
            checksAsked += address to entriesJson
            return check
        }
    }

    private val chain = FakeChain()

    private fun TestScope.history(of: AddressHistoryChain? = chain) =
        EngineAddressIndexHistory(Network.ETHEREUM, of, StandardTestDispatcher(testScheduler), 1.seconds)

    private val entry = AddressIndexEntry(26_300_000, 3, setOf(AddressIndexRole.FROM, AddressIndexRole.TRACE))

    @Test
    fun theReaderIsTheEnginesAndWaitsForARunningLightClientToAsk() = runTest {
        assertFalse(history(of = null).hasReader(), "no engine on this device")
        assertTrue(history().hasReader())
        chain.supports = false
        assertFalse(history().hasReader())
        chain.supports = null
        chain.snapshot = EngineSnapshot.Down
        assertFalse(history().hasReader(), "an engine that will never run")

        chain.snapshot = EngineSnapshot.Booting
        val asked = async { history().hasReader() }
        advanceTimeBy(3_500)
        assertFalse(asked.isCompleted, "waits while the light client starts")
        chain.supports = true
        advanceTimeBy(1_500)
        assertTrue(asked.await())
    }

    @Test
    fun aFailedCallIsNoAnswerAndNeverEndsTheWaitForGood() = runTest {
        chain.supportsThrows = true
        val asked = async { history().hasReader() }
        advanceTimeBy(3_500)
        assertFalse(asked.isCompleted, "a thrown call is asked again, not answered false for good")
        chain.supportsThrows = false
        advanceTimeBy(1_500)
        assertTrue(asked.await())
    }

    @Test
    fun aStateThatCannotBeReadIsRefusedAndNeverThrown() = runTest {
        chain.snapshotThrows = true
        assertEquals(
            AddressIndexReading.Refused(AddressIndexRefusal.NoEngine),
            history().page(Network.ETHEREUM, alice, AddressIndexQuery()),
        )
        assertEquals(
            AddressIndexCheck.Refused(AddressIndexRefusal.NoEngine),
            history().verify(Network.ETHEREUM, alice, listOf(entry)),
        )
        assertTrue(chain.pagesAsked.isEmpty() && chain.checksAsked.isEmpty(), "nothing asked of it")
    }

    @Test
    fun aPageIsReadAsTheEngineDocumentsItAndEveryOptionIsSent() = runTest {
        chain.page = """
            {"importer":"${fixtureImporter.hex}","headUpdate":7,"coveredFrom":26236928,"coveredTo":26400000,
             "headGap":{"fromBlock":26400001,"toBlock":26400120},"tailGap":{"fromBlock":0,"toBlock":26236927},
             "gaps":[{"fromBlock":26300000,"toBlock":26300099}],"fromBlock":0,"toBlock":null,"roles":[],
             "entries":[{"block":26350000,"slot":4,"roles":["from","emitter"],"state":"verified","final":true},
                        {"block":26350000,"slot":0,"roles":["block"],"state":"unverified"}],
             "next":"7:26350000:0"}
        """.trimIndent()

        val reading = history().page(
            Network.ETHEREUM,
            alice,
            AddressIndexQuery(cursor = AddressIndexCursor("7:26360000:1"), limit = 20),
        )

        val page = assertIs<AddressIndexReading.Page>(reading).page
        assertEquals(fixtureImporter, page.coverage.importer)
        assertEquals(26_236_928L..26_400_000L, page.coverage.covered)
        assertEquals(AddressIndexHeadGap(26_400_001, 26_400_120), page.coverage.headGap)
        assertEquals(0L..26_236_927L, page.coverage.tailGap)
        assertEquals(listOf(26_300_000L..26_300_099L), page.coverage.gaps)
        assertEquals(
            listOf(
                AddressIndexEntry(26_350_000, 4, setOf(AddressIndexRole.FROM, AddressIndexRole.EMITTER)),
                AddressIndexEntry(26_350_000, 0, setOf(AddressIndexRole.BLOCK)),
            ),
            page.entries,
            "candidates only: a kept verdict on a page is not taken",
        )
        assertEquals(AddressIndexCursor("7:26350000:0"), page.next)
        assertEquals(alice.hex to """{"cursor":"7:26360000:1","limit":20}""", chain.pagesAsked.single())
    }

    @Test
    fun anOpenHeadGapAndAnIndexThatCoversNothingRead() = runTest {
        chain.page = """{"importer":"${fixtureImporter.hex}","headUpdate":0,"coveredFrom":1,"coveredTo":0,
            "headGap":{"fromBlock":1,"toBlock":null},"tailGap":null,"gaps":[],"entries":[],"next":null}"""

        val page = assertIs<AddressIndexReading.Page>(history().page(Network.ETHEREUM, alice, AddressIndexQuery())).page

        assertNull(page.coverage.covered)
        assertEquals(AddressIndexHeadGap(1, null), page.coverage.headGap)
        assertNull(page.next)
    }

    @Test
    fun aMalformedOrOutOfOrderPageIsRefusedNeverThrown() = runTest {
        for (answer in listOf(
            "not json",
            """{"importer":"0x12","headUpdate":0}""",
            """{"importer":"${fixtureImporter.hex}","headUpdate":0,"coveredFrom":5,"coveredTo":9,"headGap":null,
                "tailGap":null,"gaps":[],"entries":[{"block":5,"slot":1,"roles":["sender"]}],"next":null}""",
            """{"importer":"${fixtureImporter.hex}","headUpdate":0,"coveredFrom":5,"coveredTo":9,"headGap":null,
                "tailGap":null,"gaps":[],"entries":[{"block":5,"slot":1,"roles":["from"]},
                {"block":6,"slot":1,"roles":["from"]}],"next":null}""",
        )) {
            chain.page = answer
            val refused = assertIs<AddressIndexReading.Refused>(
                history().page(Network.ETHEREUM, alice, AddressIndexQuery()),
                answer,
            )
            assertIs<AddressIndexRefusal.Rejected>(refused.reason)
        }
    }

    @Test
    fun theEnginesRefusalsMapToTheReasonsAScreenStates() = runTest {
        val lasting = mapOf(
            "the address history needs the Rust engine built with its Swarm reader" to
                AddressIndexRefusal.NotInThisBuild,
            "the address history needs the Swarm reader, which this build does not include" to
                AddressIndexRefusal.NotInThisBuild,
            "the address history is not available on mobile in this version" to AddressIndexRefusal.NotInThisBuild,
            "no importer key is set for the address history (an experimental setting)" to
                AddressIndexRefusal.SettingOff,
            "the address history is not available while Tor routing is on: the Swarm reader does not route " +
                "through Tor" to AddressIndexRefusal.TorRouting,
            "checkpoint table alarm: block 9 is not the table's" to
                AddressIndexRefusal.CheckpointAlarm("checkpoint table alarm: block 9 is not the table's"),
            "invalid address (expected 20-byte hex)" to
                AddressIndexRefusal.Rejected("invalid address (expected 20-byte hex)"),
        )
        for ((message, reason) in lasting) {
            chain.page = """{"error":"$message","code":-32602}"""
            assertEquals(
                AddressIndexReading.Refused(reason),
                history().page(Network.ETHEREUM, alice, AddressIndexQuery()),
                message,
            )
        }
        chain.page = """{"error":"no page within 60s"}"""
        assertEquals(
            AddressIndexReading.Refused(AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN)),
            history().page(Network.ETHEREUM, alice, AddressIndexQuery()),
            "synced: Swarm did not deliver",
        )
        chain.snapshot = running(EngineSnapshot.Phase.CATCHING_UP)
        assertEquals(
            AddressIndexReading.Refused(AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)),
            history().page(Network.ETHEREUM, alice, AddressIndexQuery()),
        )
    }

    @Test
    fun theLightClientsStateDecidesWhatIsAskedAndOnlyEthereumIsServed() = runTest {
        assertEquals(
            AddressIndexReading.Refused(AddressIndexRefusal.NetworkNotServed(Network.GNOSIS)),
            history().page(Network.GNOSIS, alice, AddressIndexQuery()),
        )
        assertEquals(
            AddressIndexReading.Refused(AddressIndexRefusal.NoEngine),
            history(of = null).page(Network.ETHEREUM, alice, AddressIndexQuery()),
        )
        val states = mapOf(
            EngineSnapshot.Down to AddressIndexRefusal.NoEngine,
            EngineSnapshot.Stopped(off = true) to AddressIndexRefusal.NotNow(ReadUnavailable.NETWORK_OFF),
            EngineSnapshot.Booting to AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING),
            running(EngineSnapshot.Phase.STALE_ANCHOR) to AddressIndexRefusal.StaleAnchor,
        )
        for ((snapshot, reason) in states) {
            chain.snapshot = snapshot
            assertEquals(AddressIndexCheck.Refused(reason), history().verify(Network.ETHEREUM, alice, listOf(entry)))
        }
        assertTrue(chain.checksAsked.isEmpty(), "nothing asked of an engine that cannot answer")
    }

    @Test
    fun eachVerdictIsTheEnginesAndARowNeedsEveryRoleButTraceProven() = runTest {
        val hash = "0x" + "ab".repeat(32)
        val blockHash = "0x" + "bb".repeat(32)
        chain.check = """[
            {"block":26300000,"slot":3,"roles":["from","trace"],"state":"verified","final":true,
             "blockHash":"$blockHash","confirmed":["from"],"contradicted":[],"unchecked":["trace"],
             "txHash":"$hash","from":"${alice.hex}","to":"${bob.hex}","value":"0xde0b6b3a7640000",
             "transfer":{"token":"0x${"cc".repeat(20)}","from":"${alice.hex}","to":"${bob.hex}","amount":"0xf4240",
                         "tokenId":null}},
            {"block":26300000,"slot":5,"roles":["topic"],"state":"verified","final":false,
             "blockHash":"$blockHash","confirmed":[],"contradicted":[],"unchecked":["topic"],
             "txHash":"$hash","from":"${bob.hex}","to":null,"value":"0x0","transfer":null},
            {"block":26290000,"slot":2,"roles":["to"],"state":"contradicted","final":true,
             "blockHash":"$blockHash","confirmed":[],"contradicted":["to"],"unchecked":[]},
            {"block":26280000,"slot":1,"roles":["from"],"state":"unavailable",
             "reason":"the block's receipts were not served"},
            {"block":26270000,"slot":1,"roles":["from"],"state":"unverified","reason":"behind the finalized block"}
        ]"""
        val asked = listOf(
            entry,
            AddressIndexEntry(26_300_000, 5, setOf(AddressIndexRole.TOPIC)),
            AddressIndexEntry(26_290_000, 2, setOf(AddressIndexRole.TO)),
            AddressIndexEntry(26_280_000, 1, setOf(AddressIndexRole.FROM)),
            AddressIndexEntry(26_270_000, 1, setOf(AddressIndexRole.FROM)),
        )

        val verdicts = assertIs<AddressIndexCheck.Verdicts>(history().verify(Network.ETHEREUM, alice, asked)).verdicts

        val row = assertIs<AddressIndexVerdict.Verified>(verdicts[0]).row
        assertEquals(entry, row.entry)
        val tx = checkNotNull(row.transaction)
        assertEquals(hash, tx.hash)
        assertEquals(Wei.of("1000000000000000000"), tx.value)
        val token = Address.of("0x" + "cc".repeat(20))
        assertEquals(TokenTransfer.Fungible(token, alice, bob, UInt256.of(1_000_000)), tx.transfer)
        assertNull(tx.succeeded, "the engine does not report the outcome")
        assertIs<AddressIndexVerdict.Unverified>(verdicts[1], "a role but TRACE left unchecked is no row")
        assertEquals(setOf(AddressIndexRole.TO), assertIs<AddressIndexVerdict.Contradicted>(verdicts[2]).contradicted)
        val unavailable = assertIs<AddressIndexVerdict.Unavailable>(verdicts[3])
        assertEquals("the block's receipts were not served", unavailable.reason)
        assertIs<AddressIndexVerdict.Unverified>(verdicts[4])
        assertEquals(
            """[{"block":26300000,"slot":3,"roles":["from","trace"]},{"block":26300000,"slot":5,"roles":["topic"]},""" +
                """{"block":26290000,"slot":2,"roles":["to"]},{"block":26280000,"slot":1,"roles":["from"]},""" +
                """{"block":26270000,"slot":1,"roles":["from"]}]""",
            chain.checksAsked.single().second,
        )
    }

    @Test
    fun theProtocolsLogOfAnEthTransferIsNoTokensAndAnNftIsReadByItsId() = runTest {
        val blockHash = "0x" + "bb".repeat(32)
        fun verified(slot: Int, transfer: String) = """{"block":26300000,"slot":$slot,"roles":["from"],
            "state":"verified","final":true,"blockHash":"$blockHash","confirmed":["from"],"contradicted":[],
            "unchecked":[],"txHash":"0x${"ab".repeat(32)}","from":"${alice.hex}","to":"${bob.hex}","value":"0x1",
            "transfer":$transfer}"""
        chain.check = "[" + verified(
            2,
            """{"token":"0xfffffffffffffffffffffffffffffffffffffffe","from":"${alice.hex}","to":"${bob.hex}",""" +
                """"amount":"0x1","tokenId":null}""",
        ) + "," + verified(
            1,
            """{"token":"0x${"dd".repeat(20)}","from":"${alice.hex}","to":"${bob.hex}",""" +
                """"amount":null,"tokenId":"0x7"}""",
        ) + "]"

        val verdicts = assertIs<AddressIndexCheck.Verdicts>(
            history().verify(
                Network.ETHEREUM,
                alice,
                listOf(
                    AddressIndexEntry(26_300_000, 2, setOf(AddressIndexRole.FROM)),
                    AddressIndexEntry(26_300_000, 1, setOf(AddressIndexRole.FROM)),
                ),
            ),
        ).verdicts

        assertNull(assertIs<AddressIndexVerdict.Verified>(verdicts[0]).row.transaction?.transfer)
        assertEquals(
            TokenTransfer.Nft(Address.of("0x" + "dd".repeat(20)), alice, bob, UInt256.of(7)),
            assertIs<AddressIndexVerdict.Verified>(verdicts[1]).row.transaction?.transfer,
        )
    }

    @Test
    fun theCheckpointTablesAlarmInAVerdictRefusesTheWholeCheck() = runTest {
        chain.check = """[{"block":26300000,"slot":3,"roles":["from","trace"],"state":"unverified",
            "reason":"checkpoint table alarm: the table and the beacon chain disagree"}]"""

        assertEquals(
            AddressIndexCheck.Refused(
                AddressIndexRefusal.CheckpointAlarm("checkpoint table alarm: the table and the beacon chain disagree"),
            ),
            history().verify(Network.ETHEREUM, alice, listOf(entry)),
        )
    }

    @Test
    fun aCheckOfNoEntriesIsTheCallersError() = runTest {
        assertFailsWith<IllegalArgumentException> { history().verify(Network.ETHEREUM, alice, emptyList()) }
    }

    @Test
    fun theSettingsNameThePinnedKeyOrNoneAndTheSwarmNetwork() {
        assertEquals(
            """{"importer":"${fixtureImporter.hex}","swarm":{"network":"mainnet"}}""",
            addressHistoryConfigJson(fixtureImporter, AddressIndexSwarm.Mainnet),
        )
        assertEquals(
            """{"importer":null,"swarm":{"network":"dev","networkId":7,"bootnodes":["/ip4/192.0.2.1/tcp/1634"]}}""",
            addressHistoryConfigJson(null, AddressIndexSwarm.Dev(7, listOf("/ip4/192.0.2.1/tcp/1634"))),
        )
    }

    private companion object {
        fun running(phase: EngineSnapshot.Phase) = EngineSnapshot.Running(
            phase = phase,
            paused = false,
            syncStartPeriod = 0,
            syncCurrentPeriod = 0,
            syncTargetPeriod = 0,
            huntingLightClientServers = false,
            proofPeers = 1,
            headBlock = 26_400_120,
            headTimestamp = null,
            headAgeMs = 1_000,
            wsBoundPeriods = 13,
            finalizedHeader = null,
        )
    }
}
