package eth.serotine.ui.activity

import eth.serotine.ui.pinnedEverywhere
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddressIndexChoice
import eth.serotine.domain.repository.AddressIndexArchive
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.usecase.KeepAddressIndex
import eth.serotine.domain.usecase.LoadAddressIndexPage
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.domain.usecase.ObserveAccountCowOrders
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAddressIndexStatus
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveOutbox
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveSentTo
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.ObserveWaitingSwaps
import eth.serotine.ui.FIXTURE_IMPORTER
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeAddressIndexHistory
import eth.serotine.ui.FakeLogIndex
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.ListCatalog
import eth.serotine.ui.MemoryAddressIndexArchive
import eth.serotine.ui.MemoryAddressIndexSetting
import eth.serotine.ui.MemoryCowOrders
import eth.serotine.ui.MemoryHistoryStarts
import eth.serotine.ui.MemoryOutbox
import eth.serotine.ui.MemoryTransactionHistory
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.MemoryWaitingSwaps
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.indexRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import eth.serotine.domain.usecase.AddressIndexProgress
import kotlin.test.assertFalse
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexHeadGap
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An account's Activity with the addr1 index's verified rows (owner ruling, 2026-11-21, #334). */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressIndexActivityTest {

    private val alice = Account(
        Network.ETHEREUM,
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Alice",
        AccountCustody.InKeyStore,
    )
    private val bob = Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
    private val stranger = Address.of("0x" + "5a".repeat(20))
    private val usdc = Token(Address.of("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"), "USDC", "USDC", 6, "usdc")
    private val history = MemoryTransactionHistory()
    private val accounts = FakeAccounts(alice)
    private val index = FakeAddressIndexHistory()
    private val setting = MemoryAddressIndexSetting(on = FIXTURE_IMPORTER)
    private val more = MutableStateFlow(0)

    private fun TestScope.activity(
        pinned: Address? = FIXTURE_IMPORTER,
        archive: AddressIndexArchive? = null,
    ): StateFlow<ActivityUiState> =
        AccountActivity(
            ObserveAccountHistory(history),
            ObserveAccounts(accounts),
            ObserveSafeHistory(FakeLogIndex(), MemoryHistoryStarts()),
            ObserveTokenTransfers(FakeLogIndex(), MemoryHistoryStarts()),
            ObserveKnownTokens(ListCatalog(usdc, network = Network.ETHEREUM), MemoryUserTokens(), FakeMetadata()),
            ObserveSentTo(history),
            ObserveAccountCowOrders(MemoryCowOrders()),
            ObserveWaitingSwaps(MemoryWaitingSwaps()),
            ObserveOutbox(MemoryOutbox()),
            addressIndex = pinned?.let {
                AddressIndexActivity(
                    ObserveAddressIndexStatus(index, setting, pinnedEverywhere(it)),
                    LoadAddressIndexPage(index, pinnedEverywhere(it)),
                    keep = archive?.let { kept ->
                        KeepAddressIndex(kept, accounts, WalletChanges(), pinnedEverywhere(it))
                    },
                )
            },
            now = { 600 },
        ) { "at $it" }
            .of(AccountId(Network.ETHEREUM, alice.address), flowOf(false), more)
            .stateIn(
                backgroundScope + UnconfinedTestDispatcher(testScheduler),
                SharingStarted.Eagerly,
                ActivityUiState.Loading,
            )
            .also { runCurrent() }

    private fun TestScope.shown(activity: StateFlow<ActivityUiState>): ActivityUiState.Shown {
        runCurrent()
        return assertIs<ActivityUiState.Shown>(activity.value)
    }

    @Test
    fun eachEntryIsAGhostInItsPlaceUntilItsCheckAnswersAndItsRowTakesItsKey() = runTest {
        val newer = indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000")
        val older = indexRow(9_200_000, 2, alice.address, bob, wei = "100000000000000000")
        val unverified = AddressIndexEntry(9_250_000, 1, setOf(AddressIndexRole.FROM))
        index.page(null, listOf(newer, older), next = null, others = listOf(unverified))
        // Each block's check waits until the test lets it answer.
        val gates = mutableMapOf<Long, CompletableDeferred<Unit>>()
        fun gate(block: Long) = gates.getOrPut(block) { CompletableDeferred() }
        index.checkGate = { entries -> gate(entries.first().block).await() }
        val activity = activity()

        val read = shown(activity)
        val keys = listOf("index:9300000:3", "index:9250000:1", "index:9200000:2")
        assertEquals(keys, read.rows.map { it.key }, "each entry in its place, newest first")
        assertTrue(read.rows.all { it.pending }, "nothing of an entry is said before its check")
        assertTrue(read.rows.all { it.title.isEmpty() && it.about.isEmpty() })
        val checking = assertIs<IndexFooter.Checking>(read.index?.footer)
        assertEquals("Checking 3 entries of the addr1 index against the chain…", checking.text)
        assertEquals(listOf(listOf(newer.entry)), index.checked, "one block at a time, newest first")

        gate(9_300_000).complete(Unit)
        val one = shown(activity)
        assertEquals(keys, one.rows.map { it.key }, "the row takes its ghost's place")
        assertEquals(listOf(false, true, true), one.rows.map { it.pending })
        assertEquals("Sent ETH", one.rows.first().headline)

        gate(9_250_000).complete(Unit)
        val two = shown(activity)
        assertEquals(listOf("index:9300000:3", "index:9200000:2"), two.rows.map { it.key }, "an unverified one goes")
        assertEquals(listOf(false, true), two.rows.map { it.pending })

        gate(9_200_000).complete(Unit)
        val done = shown(activity)
        assertEquals(listOf(false, false), done.rows.map { it.pending })
        assertIs<IndexFooter.End>(done.index?.footer)
        assertEquals(
            "1 entry of the index is left out: it could not be verified.",
            done.index?.leftOut,
        )
    }

    @Test
    fun aPageReadIsInFlightUntilItsLoadEndsAndAnEntryListedAlreadyGetsNoGhost() {
        val account = AccountId(Network.ETHEREUM, alice.address)
        val listed = indexRow(9_300_000, 3, alice.address, bob)
        val again = listed.entry
        val newer = AddressIndexEntry(9_200_000, 2, setOf(AddressIndexRole.FROM))
        val first = IndexPaging(FIXTURE_IMPORTER).copy(rows = listOf(listed), loading = true)

        // The next page repeats the entry it starts below, which is listed already: no ghost for it, nor twice.
        val read = first.after(AddressIndexProgress.Read(account, coverage(), null, listOf(again, newer, newer)))
        assertEquals(listOf(newer), read.pending)
        assertTrue(read.checking)

        // Its last check answered, its load not ended yet: still in flight, so nothing more is asked meanwhile.
        val answered = read.after(AddressIndexProgress.Checked(newer, AddressIndexVerdict.Unverified(newer)))
        assertEquals(emptyList(), answered.pending)
        assertTrue(answered.checking)
        assertFalse(answered.canAsk)
        assertIs<IndexFooter.Checking>(indexSection(answered, Network.ETHEREUM, 0).footer)
    }

    /** What a page of the fake index covers, as [FakeAddressIndexHistory.page] makes it. */
    private fun coverage() = AddressIndexCoverage(
        importer = FIXTURE_IMPORTER,
        headUpdate = 3,
        covered = 9_000_000L..9_400_000L,
        headGap = AddressIndexHeadGap(9_400_001, 9_400_120),
        tailGap = 0L until 9_000_000L,
        gaps = emptyList(),
    )

    @Test
    fun theCoverageIsSaidOnceThePageIsReadBeforeAnyRowOfIt() = runTest {
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = null)
        index.checkGate = { awaitCancellation() }
        val read = shown(activity())

        assertEquals(listOf(true), read.rows.map { it.pending })
        val lines = checkNotNull(read.index).lines
        assertTrue(lines.any { it.contains("blocks 9,000,000 to 9,400,000") }, lines.toString())
    }

    @Test
    fun aLastingRefusalWithdrawsThisPagesRowsAloneNeverAnEarlierPagesRow() = runTest {
        val first = indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000")
        index.page(null, listOf(first), next = "p2")
        // The next page repeats the first page's row at its edge, verifies a row of its own, then Tor is turned on.
        val own = indexRow(9_200_000, 2, alice.address, bob, wei = "100000000000000000")
        val refusedEntry = AddressIndexEntry(9_100_000, 1, setOf(AddressIndexRole.FROM))
        index.page("p2", listOf(first, own), next = null, others = listOf(refusedEntry))
        index.checkGate = { entries ->
            if (entries.first().block == 9_100_000L) index.checkRefusal = AddressIndexRefusal.TorRouting
        }
        val activity = activity()
        assertEquals(listOf("index:9300000:3"), shown(activity).rows.map { it.key })

        more.value = 1
        val refused = shown(activity)

        assertEquals(listOf("index:9300000:3"), refused.rows.map { it.key }, "this page's row goes, the first stays")
        assertIs<IndexFooter.Refused>(refused.index?.footer)
    }

    @Test
    fun aPageWhoseOnlyVerifiedRowRepeatsTheOneBeforePausesTheEndlessList() = runTest {
        val first = indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000")
        index.page(null, listOf(first), next = "p2")
        // The next page repeats the first page's row at its edge; its other entry cannot be verified.
        index.page(
            "p2",
            listOf(first),
            next = "p3",
            others = listOf(AddressIndexEntry(9_200_000, 2, setOf(AddressIndexRole.FROM))),
        )
        val activity = activity()
        shown(activity)

        more.value = 1
        val second = shown(activity)

        assertEquals(listOf("index:9300000:3"), second.rows.map { it.key })
        assertIs<IndexFooter.Paused>(second.index?.footer, "a page that added no row reads on only on a tap")
    }

    @Test
    fun aPageNoneOfWhoseEntriesCouldBeCheckedNowOffersTryAgainWhichReadsItAgain() = runTest {
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = "p2")
        index.checkRefusal = AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)
        val activity = activity()
        // Past the checks' retries and their pauses.
        advanceTimeBy(1.minutes)

        val refused = shown(activity)
        val footer = assertIs<IndexFooter.Refused>(refused.index?.footer)
        assertTrue(footer.retry, "a passing refusal offers Try again")
        assertEquals(emptyList<ActivityRow>(), refused.rows)

        // The light client caught up: Try again reads the same page, not the one below it.
        index.checkRefusal = null
        more.value = 1
        val again = shown(activity)

        assertEquals(listOf<AddressIndexCursor?>(null, null), index.asked)
        assertEquals(listOf("index:9300000:3"), again.rows.map { it.key })
    }

    @Test
    fun aNextPageRefusedBeforeItIsReadTakesNothingOfThePageBefore() = runTest {
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000")), next = "p2")
        val activity = activity()
        assertEquals(1, shown(activity).rows.size)

        // Swarm does not deliver the next page.
        index.pageRefusal = AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN)
        more.value = 1
        val refused = shown(activity)

        assertEquals(listOf("index:9300000:3"), refused.rows.map { it.key }, "the first page's row stays")
        assertIs<IndexFooter.Refused>(refused.index?.footer)
    }

    @Test
    fun offItAddsNothingAndReadsNothing() = runTest {
        setting.choice.value = AddressIndexChoice.Chosen(importers = null)

        val shown = shown(activity())

        assertNull(shown.index)
        assertTrue(index.asked.isEmpty(), "nothing read while it is off")
    }

    @Test
    fun itsVerifiedRowsAreListedNewestFirstNamingTheImporterAndTheNextPageLoadsAsAsked() = runTest {
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000")), next = "p2")
        index.page(
            "p2",
            listOf(indexRow(9_100_000, 1, bob, alice.address, wei = "1000000000000000000")),
            next = null,
            covered = 9_000_000L..9_400_000L,
        )
        val activity = activity()

        val first = shown(activity)
        assertEquals(listOf("Sent 0.25 ETH"), first.rows.map { it.title })
        val section = checkNotNull(first.index)
        assertEquals(IndexFooter.More, section.footer)
        assertTrue(section.lines.first().contains(Eip55.format(FIXTURE_IMPORTER)), "the importer named in full")
        assertTrue(section.lines.first().contains("entries may be missing"))
        assertTrue(section.lines.any { it.contains("blocks 9,000,000 to 9,400,000") }, section.lines.toString())
        assertTrue(section.lines.any { it.contains("has not reached the newest blocks yet, blocks 9,400,001") })
        assertEquals("Finalized in block 9,300,000 · checked against the chain", first.rows.single().status)

        more.value = 1
        val second = shown(activity)

        assertEquals(listOf("Sent 0.25 ETH", "Received 1 ETH"), second.rows.map { it.title })
        assertEquals(listOf(null, AddressIndexCursor("p2")), index.asked)
        val end = assertIs<IndexFooter.End>(second.index?.footer)
        assertEquals("The addr1 index starts at block 9,000,000: nothing older comes from it.", end.text)
        assertEquals(ActivityState.CAUTION, second.rows[1].state, "from an address never sent to")

        more.value = 2
        runCurrent()
        assertEquals(2, index.asked.size, "nothing is asked past the end of its coverage")
    }

    @Test
    fun aPageThatAddsNoRowStopsTheListAndATapReadsExactlyOneMore() = runTest {
        val unverified = { block: Long -> AddressIndexEntry(block, 1, setOf(AddressIndexRole.TOPIC)) }
        index.page(null, emptyList(), next = "p2", others = listOf(unverified(9_390_000), unverified(9_380_000)))
        index.page("p2", emptyList(), next = "p3", others = listOf(unverified(9_370_000)))
        index.page("p3", listOf(indexRow(9_300_000, 3, alice.address, bob)), next = "p4")
        index.page("p4", listOf(indexRow(9_200_000, 3, alice.address, bob)), next = null)
        val activity = activity()

        val paused = assertIs<IndexFooter.Paused>(shown(activity).index?.footer, "no row added: no page by itself")
        assertEquals(
            "The last page of the addr1 index added no row: 2 entries could not be verified. The next is read " +
                "only when you ask, since each page fetches old blocks from archive peers.",
            paused.text,
        )
        assertEquals(1, index.asked.size)

        more.value = 1
        val stillPaused = shown(activity)
        assertEquals(2, index.asked.size, "a tap reads exactly one page")
        assertIs<IndexFooter.Paused>(stillPaused.index?.footer)
        assertTrue(stillPaused.rows.isEmpty())

        more.value = 2
        val resumed = shown(activity)
        assertEquals(3, index.asked.size)
        assertEquals(listOf("Sent a transaction"), resumed.rows.map { it.title })
        assertEquals(IndexFooter.More, resumed.index?.footer, "a page that added a row reads on by itself again")
    }

    @Test
    fun aPageWhoseRowsAreAllListedAlreadyAddsNoRowEither() = runTest {
        val sent = SentTransfer(
            network = Network.ETHEREUM,
            account = alice.address,
            nonce = 7,
            signed = SignedTransfer(byteArrayOf(2, 7)),
            action = SentAction.Transfer(bob, TransferAsset.Native, UInt256.of("250000000000000000")),
            maxCost = UInt256.of(1_000_000),
            sentAt = 300,
        )
        history.entries.value = listOf(HistoryEntry(sent, SentOutcome.Finalized(9_300_000, succeeded = true)))
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob, hash = sent.hash)), next = "p2")

        val footer = assertIs<IndexFooter.Paused>(shown(activity()).index?.footer)

        assertTrue(footer.text.contains("1 entry was listed already or is not listed"), footer.text)
    }

    @Test
    fun aTransactionSentFromHereShowsOnce() = runTest {
        val sent = SentTransfer(
            network = Network.ETHEREUM,
            account = alice.address,
            nonce = 7,
            signed = SignedTransfer(byteArrayOf(2, 7)),
            action = SentAction.Transfer(bob, TransferAsset.Native, UInt256.of("250000000000000000")),
            maxCost = UInt256.of(1_000_000),
            sentAt = 300,
        )
        history.entries.value = listOf(HistoryEntry(sent, SentOutcome.Finalized(9_300_000, succeeded = true)))
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob, hash = sent.hash)), next = null)

        val rows = shown(activity()).rows

        assertEquals(listOf("Sent 0.25 ETH"), rows.map { it.title })
        assertEquals(null, rows.single().opens.index, "the row sent from here, not the index's")
    }

    @Test
    fun whatItLeftOutIsCountedAndAContradictionWarnsAgainstTheImporter() = runTest {
        val contradicted = AddressIndexEntry(9_200_000, 2, setOf(AddressIndexRole.FROM))
        val unverified = AddressIndexEntry(9_150_000, 4, setOf(AddressIndexRole.TOPIC))
        val trace = AddressIndexEntry(9_140_000, 5, setOf(AddressIndexRole.TRACE))
        index.page(null, emptyList(), next = null, others = listOf(contradicted, unverified, trace))
        index.verdictOf = { entry ->
            if (entry == contradicted) {
                AddressIndexVerdict.Contradicted(entry, "0x" + "bb".repeat(32), true, setOf(AddressIndexRole.FROM))
            } else {
                AddressIndexVerdict.Unverified(entry)
            }
        }

        val shown = shown(activity())

        assertTrue(shown.rows.isEmpty(), "verified rows only")
        val section = checkNotNull(shown.index)
        assertEquals("2 entries of the index are left out: they could not be verified.", section.leftOut)
        val warning = checkNotNull(section.contradicted)
        assertTrue(warning.startsWith("Warning: the importer's index contradicts the chain in 1 entry"), warning)
    }

    @Test
    fun aRefusalIsSaidNeverAnEmptyListAndAPassingOneIsAskedAgain() = runTest {
        index.pageRefusal = AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)
        val activity = activity()

        val refused = assertIs<IndexFooter.Refused>(shown(activity).index?.footer)
        assertEquals(true, refused.retry)
        assertTrue(refused.text.contains("still catching up"))

        index.pageRefusal = null
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = null)
        more.value = 1

        assertEquals(listOf("Sent a transaction"), shown(activity).rows.map { it.title })
    }

    @Test
    fun aLastingRefusalSaysWhyAndOffersNoRetry() = runTest {
        index.pageRefusal = AddressIndexRefusal.NotInThisBuild

        val refused = assertIs<IndexFooter.Refused>(shown(activity()).index?.footer)

        assertEquals(false, refused.retry)
        assertEquals(
            "This build's engine has no Swarm reader, so nothing comes from the addr1 index here.",
            refused.text,
        )
    }

    @Test
    fun theCheckpointAlarmWithdrawsTheRowsAlreadyShown() = runTest {
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = "p2")
        val older = AddressIndexEntry(9_100_000, 1, setOf(AddressIndexRole.TO))
        index.page("p2", emptyList(), next = null, others = listOf(older))
        val activity = activity()
        assertEquals(1, shown(activity).rows.size)

        index.checkRefusal = AddressIndexRefusal.CheckpointAlarm("checkpoint table alarm: root mismatch at 9,000,960")
        more.value = 1
        val shown = shown(activity)

        assertTrue(shown.rows.isEmpty(), "nothing resting on the table stays")
        assertEquals(1, shown.index?.lines?.size, "nor what the index said of its coverage")
        val footer = assertIs<IndexFooter.Refused>(shown.index?.footer)
        assertTrue(footer.warning)
    }

    @Test
    fun aTransferOfNothingFromAStrangerIsNotListedAndAnUnlistedTokensIsItsClaim() = runTest {
        val spam = Address.of("0x8617e340b3d01fa5f11f306f4090fd50e238070d")
        index.page(
            null,
            listOf(
                indexRow(
                    9_300_000,
                    3,
                    stranger,
                    usdc.address,
                    transfer = TokenTransfer.Fungible(usdc.address, alice.address, stranger, UInt256.ZERO),
                    roles = setOf(AddressIndexRole.TOPIC),
                ),
                indexRow(
                    9_200_000,
                    2,
                    stranger,
                    spam,
                    transfer = TokenTransfer.Fungible(spam, stranger, alice.address, UInt256.of(5)),
                    roles = setOf(AddressIndexRole.TOPIC),
                ),
                indexRow(
                    9_100_000,
                    1,
                    bob,
                    usdc.address,
                    transfer = TokenTransfer.Fungible(usdc.address, bob, alice.address, UInt256.of(1_000_000)),
                    roles = setOf(AddressIndexRole.TOPIC),
                ),
            ),
            next = null,
        )

        val rows = shown(activity()).rows

        assertEquals(
            listOf("An unlisted token logged a transfer to it", "Received 1 USDC"),
            rows.map { it.title },
            "a transfer of nothing plants a look-alike: it is left out",
        )
        assertEquals(ActivityState.UNKNOWN, rows[0].state)
        assertEquals(IndexEntryKey(9_100_000, 1, setOf(AddressIndexRole.TOPIC)), rows[1].opens.index)
    }

    @Test
    fun withNoKeyPinnedNothingIsRead() = runTest {
        val shown = shown(activity(pinned = null))

        assertNull(shown.index)
        assertTrue(index.asked.isEmpty())
    }

    @Test
    fun aTransactionWhoseOutcomeIsNotReportedClaimsNoValueAndARevertedOneFailed() = runTest {
        index.page(
            null,
            listOf(
                indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000", succeeded = null),
                indexRow(9_200_000, 2, alice.address, bob, wei = "250000000000000000", succeeded = false),
                indexRow(9_100_000, 1, alice.address, null, succeeded = null),
            ),
            next = null,
        )

        val rows = shown(activity()).rows

        assertEquals(
            listOf("Named in a transaction · sender", "Failed on chain", "Named in a transaction · sender"),
            rows.map { it.title },
        )
        assertEquals(ActivityState.FAILED, rows[1].state)
        assertEquals("Reverted in block 9,200,000 · checked against the chain", rows[1].status)
        assertEquals("It creates a contract", rows[2].detail)
        // The list says its value at a glance (owner's request, 2026-11-23), saying the outcome is not checked; a
        // reverted one moved nothing, and a contract creation names no recipient.
        assertEquals(listOf("\u22120.25 ETH", null, null), rows.map { it.amount })
        assertEquals(listOf("Sent ETH · outcome not checked", null, null), rows.map { it.headline })
        assertEquals(ActivityDirection.OUT, rows[0].direction)
        assertEquals(ActivityDirection.NONE, rows[1].direction)
        assertEquals(listOf(true, false, false), rows.map { it.unchecked }, "not proven to have moved: muted")
    }

    @Test
    fun aTransferToItselfReadsAsTheLogIndexSaysIt() = runTest {
        index.page(
            null,
            listOf(
                indexRow(
                    9_300_000,
                    3,
                    alice.address,
                    usdc.address,
                    transfer = TokenTransfer.Fungible(usdc.address, alice.address, alice.address, UInt256.ONE),
                    roles = setOf(AddressIndexRole.FROM, AddressIndexRole.TOPIC),
                ),
            ),
            next = null,
        )

        assertEquals(listOf("Moved 0.000001 USDC to itself"), shown(activity()).rows.map { it.title })
    }

    @Test
    fun aListReadIsKeptAndTheNextOpeningShowsItAtOnceAndPrependsOnlyWhatIsNewer() = runTest {
        val archive = MemoryAddressIndexArchive()
        val account = AccountId(Network.ETHEREUM, alice.address)
        val old = indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000")
        index.page(null, listOf(old), next = "p2")
        shown(activity(archive = archive))
        val kept = checkNotNull(archive.kept[account])
        assertEquals(listOf(old), kept.rows)
        assertEquals(9_400_000, kept.top, "read through the newest block the index covered")

        // The next opening, while Swarm delivers nothing: the kept rows are there at once.
        index.pageRefusal = AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN)
        val offline = shown(activity(archive = archive))
        assertEquals(listOf("Sent 0.25 ETH"), offline.rows.map { it.title })
        assertEquals(listOf(old), checkNotNull(archive.kept[account]).rows, "a refusal keeps what is kept")

        // And once it does, only the entry newer than the kept top is checked, and prepended.
        index.pageRefusal = null
        index.checked.clear()
        val new = indexRow(9_450_000, 1, bob, alice.address, wei = "1000000000000000000")
        index.page(null, listOf(new, old), next = "p2", covered = 9_000_000L..9_500_000L)
        val reopened = shown(activity(archive = archive))

        assertEquals(listOf("Received 1 ETH", "Sent 0.25 ETH"), reopened.rows.map { it.title })
        assertEquals(listOf(listOf(new.entry)), index.checked, "the kept row is not checked again")
        assertEquals(IndexFooter.More, reopened.index?.footer, "the older pages go on as the list nears its end")
        val after = checkNotNull(archive.kept[account])
        assertEquals(listOf(new, old), after.rows)
        assertEquals(9_500_000, after.top)
        assertEquals(AddressIndexCursor("p2"), after.next, "the older pages still go on below the kept ones")
    }

    @Test
    fun theOlderPagesGoOnWhereTheKeptListEnded() = runTest {
        val archive = MemoryAddressIndexArchive()
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = "p2")
        shown(activity(archive = archive))
        index.page("p2", listOf(indexRow(9_100_000, 1, bob, alice.address)), next = null)
        index.asked.clear()

        val reopened = activity(archive = archive)
        shown(reopened)
        more.value = 1
        val shown = shown(reopened)

        // The newer entries first, then the kept continuation (the first opening, still collected, asks it too).
        assertEquals(null, index.asked.first())
        assertEquals(setOf(null, AddressIndexCursor("p2")), index.asked.toSet())
        assertEquals(2, shown.rows.size)
        assertIs<IndexFooter.End>(shown.index?.footer)
    }

    @Test
    fun aRowOfABlockNotFinalYetIsNotKeptButItsBlockIsReadAgainFromTheIndex() = runTest {
        val archive = MemoryAddressIndexArchive()
        val account = AccountId(Network.ETHEREUM, alice.address)
        val recent = indexRow(9_390_000, 2, alice.address, bob, final = false)
        index.page(null, listOf(recent), next = null)
        assertEquals(1, shown(activity(archive = archive)).rows.size, "shown while the app runs")
        val kept = checkNotNull(archive.kept[account])
        assertEquals(emptyList(), kept.rows)
        assertEquals(9_389_999, kept.top, "below the block whose verdict may still be undone")

        val final = recent.copy(final = true)
        index.verifiedRows[recent.entry] = final
        index.checked.clear()
        val reopened = shown(activity(archive = archive))

        assertEquals(listOf(listOf(recent.entry)), index.checked, "read again from the index, and checked")
        assertEquals(1, reopened.rows.size)
        assertEquals(listOf(final), checkNotNull(archive.kept[account]).rows)
        assertEquals(9_400_000, checkNotNull(archive.kept[account]).top)
    }

    @Test
    fun aWaitingEntryNoCheckAnswersLeavesTheListAsItWasAndATapReadsNoWrongPage() = runTest {
        val archive = MemoryAddressIndexArchive()
        val account = AccountId(Network.ETHEREUM, alice.address)
        val unread = AddressIndexEntry(9_200_000, 4, setOf(AddressIndexRole.TO))
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = null, others = listOf(unread))
        index.verdictOf = { entry ->
            index.verifiedRows[entry]?.let { AddressIndexVerdict.Verified(it) }
                ?: AddressIndexVerdict.Unavailable(entry, "no peer served the receipts")
        }
        shown(activity(archive = archive))
        // Past the retries of the entry no peer serves.
        advanceTimeBy(1.minutes)
        assertEquals(listOf(unread), checkNotNull(archive.kept[account]).recheck)

        index.checkRefusal = AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)
        index.asked.clear()
        val reopened = activity(archive = archive)
        advanceTimeBy(1.minutes)
        val shown = shown(reopened)

        assertEquals(1, shown.rows.size)
        assertIs<IndexFooter.End>(shown.index?.footer, "nothing refused: the entry waits for the next opening")
        assertEquals(listOf(unread), checkNotNull(archive.kept[account]).recheck)
        val asked = index.asked.size
        more.value = 1
        shown(reopened)
        assertEquals(asked, index.asked.size, "a list read to its end reads no page more")
    }

    @Test
    fun aRangeAReadCouldNotGetIsNamedAndReadAgainAtTheNextOpening() = runTest {
        val archive = MemoryAddressIndexArchive()
        val account = AccountId(Network.ETHEREUM, alice.address)
        val gap = 9_100_000L..9_100_999L
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = null, gaps = listOf(gap))
        val first = shown(activity(archive = archive))
        assertTrue(first.index?.lines.orEmpty().any { it.contains("blocks 9,100,000 to 9,100,999") })
        assertEquals(listOf(gap), checkNotNull(archive.kept[account]).unread)

        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = null)
        index.page(null, listOf(indexRow(9_100_500, 1, bob, alice.address)), next = null, range = gap)
        val reopened = shown(activity(archive = archive))

        assertEquals(2, reopened.rows.size, "the entry the read missed before")
        assertTrue(reopened.index?.lines.orEmpty().none { it.contains("9,100,000") }, "no longer a gap")
        assertEquals(emptyList(), checkNotNull(archive.kept[account]).unread)
    }

    @Test
    fun theCheckpointAlarmForgetsWhatIsKept() = runTest {
        val archive = MemoryAddressIndexArchive()
        val account = AccountId(Network.ETHEREUM, alice.address)
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = null)
        shown(activity(archive = archive))
        index.page(
            null,
            listOf(indexRow(9_450_000, 1, bob, alice.address), indexRow(9_300_000, 3, alice.address, bob)),
            next = null,
            covered = 9_000_000L..9_500_000L,
        )

        index.checkRefusal = AddressIndexRefusal.CheckpointAlarm("checkpoint table alarm: root mismatch")
        val shown = shown(activity(archive = archive))

        assertTrue(shown.rows.isEmpty(), "nothing resting on the table stays")
        assertNull(archive.kept[account], "nor is it kept")
    }

    @Test
    fun aRetryAfterTheNewerEntriesWereRefusedReadsThemNotAnOlderPage() = runTest {
        val archive = MemoryAddressIndexArchive()
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = "p2")
        shown(activity(archive = archive))
        index.page("p2", listOf(indexRow(9_100_000, 1, bob, alice.address)), next = null)
        index.pageRefusal = AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN)
        val reopened = activity(archive = archive)
        assertIs<IndexFooter.Refused>(shown(reopened).index?.footer)

        index.pageRefusal = null
        index.asked.clear()
        val new = indexRow(9_450_000, 1, bob, alice.address, wei = "1000000000000000000")
        index.page(null, listOf(new), next = "p2", covered = 9_000_000L..9_500_000L)
        more.value = 1
        val retried = shown(reopened)

        // The newer entries are read again (the first opening, still collected, asks for its own next page too).
        assertTrue(null in index.asked, "the newer entries, not only the page below the kept list")
        assertEquals("Received 1 ETH", retried.rows.first().title)
    }

    @Test
    fun aFirstPageRefusedThenReadIsKeptAllTheSame() = runTest {
        val archive = MemoryAddressIndexArchive()
        val account = AccountId(Network.ETHEREUM, alice.address)
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = "p2")
        index.pageRefusal = AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN)
        val activity = activity(archive = archive)
        assertIs<IndexFooter.Refused>(shown(activity).index?.footer)

        index.pageRefusal = null
        more.value = 1
        shown(activity)

        assertEquals(9_400_000, checkNotNull(archive.kept[account]).top)
    }

    @Test
    fun aRowWhoseBlockTimeTheEngineReportsIsDatedAndTimedByIt() = runTest {
        val dated = indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000", blockTimestamp = 500)
        val undated = indexRow(9_200_000, 2, bob, alice.address, wei = "100000000000000000")
        index.page(null, listOf(dated, undated), next = null)

        val shown = shown(activity())

        val row = shown.rows.single { it.key == "index:9300000:3" }
        assertEquals("at 500", row.time, "its block's time, as a dated event's")
        assertTrue(row.day != null && !row.day.startsWith("Date not"), "a day of its own: ${row.day}")
        assertEquals(
            "Block 9,200,000",
            shown.rows.single { it.key == "index:9200000:2" }.time,
            "an engine that reports no time: by its block, as before",
        )
    }
}
