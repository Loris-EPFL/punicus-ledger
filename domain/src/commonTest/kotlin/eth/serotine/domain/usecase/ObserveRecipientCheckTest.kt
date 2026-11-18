package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenTransfers
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TokenSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [ObserveRecipientCheck] over fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveRecipientCheckTest {

    private val me = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
    private val stranger = Address.of("0x" + "33".repeat(20))
    private val usdc = Token(Address.of("0x" + "7c".repeat(20)), "USDC", "USD Coin", 6, null)
    private val added = Token(Address.of("0x" + "ad".repeat(20)), "MINE", "Mine", 18, null)
    private val history = MemoryTransactionHistory()
    private val accounts = ListAccountRepository(
        Account(Network.SEPOLIA, me, AccountKind.EOA, "Me", AccountCustody.InKeyStore),
        // The same address on another network is another account, and no address book entry here.
        Account(Network.ETHEREUM, bob, AccountKind.EOA, "Bob elsewhere", AccountCustody.Watched),
    )
    private val index = RecordingLogIndex()
    private val starts = MemoryHistoryStarts(me to HistoryStart.Fixed(9_000))
    private val userTokens = MemoryUserTokens()

    private fun observe() = ObserveRecipientCheck(
        ObserveSentTo(history),
        ObserveAccounts(accounts),
        ObserveTokenTransfers(index, starts),
        ObserveKnownTokens(ListCatalog(usdc), userTokens, FakeMetadata()),
    )

    private fun topic(address: Address) = "0x" + "00".repeat(12) + address.hex.removePrefix("0x")

    private fun log(token: Address, from: Address, to: Address, amount: Long, index: Int = 0) = IndexedLog(
        ReceiptLog(
            token,
            listOf(TokenTransfers.TRANSFER, topic(from), topic(to)),
            "0x" + amount.toString(16).padStart(64, '0'),
        ),
        9_100,
        "0x" + "bb".repeat(32),
        "0x" + "cc".repeat(32),
        index,
    )

    /** The index holds [logs] for the account, at both topic positions as the engine answers. */
    private fun indexed(vararg logs: IndexedLog) {
        index.answer = { query ->
            LogsReading.Logs(
                logs.filter { log ->
                    log.log.topics.getOrNull(query.position!!) == topic(me)
                },
            )
        }
        index.coverage.value = LogCoverageReading.Read(mapOf(LogWatchKey.Mention(me) to LogCoverage(9_000, 9_000L..9_200L)))
    }

    private suspend fun TestScope.latest(to: Address): RecipientCheck {
        var last: RecipientCheck? = null
        val job = backgroundScope.launch { observe()(me.onSepolia, to).collect { last = it } }
        runCurrent()
        job.cancel()
        return last ?: observe()(me.onSepolia, to).first()
    }

    private fun sentToBob() = SentTransfer(
        network = Network.SEPOLIA,
        account = me,
        nonce = 1,
        signed = SignedTransfer(byteArrayOf(2, 1)),
        action = SentAction.Transfer(bob, TransferAsset.Native, UInt256.of(1)),
        maxCost = UInt256.of(1_000_000),
        sentAt = 100,
    )

    @Test
    fun aLookalikeOfAnAddressSentToFromThisDeviceIsFound() = runTest {
        history.record(sentToBob())
        val lookalike = Address.of("0x5aae" + "1".repeat(32) + "eaed")
        assertEquals(RecipientCheck.Lookalike(lookalike, bob), latest(lookalike))
        assertEquals(RecipientCheck.Familiar, latest(bob))
    }

    @Test
    fun onlyTheAccountsNetworkCountsAsTheWalletsOwn() = runTest {
        // Bob is an account on Ethereum only: on Sepolia he is no one this wallet knows.
        assertEquals(RecipientCheck.NewRecipient(historyRead = false), latest(bob))
    }

    @Test
    fun anUnreadableHistoryOfWhatWasSentBlocks() = runTest {
        history.readable.value = false
        assertEquals(RecipientCheck.SentToUnreadable, latest(stranger))
        assertEquals(RecipientCheck.Familiar, latest(me))
    }

    @Test
    fun untilTheLogIndexIsReadTheNoticeSaysSo() = runTest {
        assertEquals(RecipientCheck.NewRecipient(historyRead = false), latest(stranger))
        index.coverage.value = LogCoverageReading.Unavailable
        assertEquals(RecipientCheck.NewRecipient(historyRead = false), latest(stranger))
    }

    @Test
    fun aZeroTransferEitherWayIsAPlant() = runTest {
        indexed(log(usdc.address, me, stranger, 0))
        assertEquals(RecipientCheck.PlantedSender(stranger, PlantKind.ZERO_VALUE), latest(stranger))
        indexed(log(usdc.address, stranger, me, 0))
        assertEquals(RecipientCheck.PlantedSender(stranger, PlantKind.ZERO_VALUE), latest(stranger))
    }

    @Test
    fun aBuiltInOrAddedTokenIsListedAndAnyOtherIsNot() = runTest {
        userTokens.settings.value = TokenSettings.Loaded(listOf(added), emptySet())
        indexed(log(usdc.address, stranger, me, 5_000_000), log(added.address, stranger, me, 7, index = 1))
        assertEquals(RecipientCheck.NewRecipient(), latest(stranger))

        val unlisted = Address.of("0x" + "ee".repeat(20))
        indexed(log(unlisted, stranger, me, 5_000_000))
        assertEquals(RecipientCheck.PlantedSender(stranger, PlantKind.UNLISTED_TOKEN), latest(stranger))
    }

    @Test
    fun anAddedTokenThatTakesABuiltInSymbolIsNotListed() = runTest {
        val impostor = Token(Address.of("0x" + "ee".repeat(20)), "USDC", "USD Coin", 6, null)
        userTokens.settings.value = TokenSettings.Loaded(listOf(impostor), emptySet())
        indexed(log(impostor.address, stranger, me, 5_000_000))
        assertEquals(RecipientCheck.PlantedSender(stranger, PlantKind.UNLISTED_TOKEN), latest(stranger))
    }
}
