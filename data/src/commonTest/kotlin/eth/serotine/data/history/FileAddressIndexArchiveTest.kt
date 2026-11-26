package eth.serotine.data.history

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexHeadGap
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexTransaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.KeptAddressIndex
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

/** [FileAddressIndexArchive] against the [eth.serotine.domain.repository.AddressIndexArchive] contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileAddressIndexArchiveTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-addr1-${Random.nextLong().toULong()}")
    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))
    private val token = Address.of("0x" + "70".repeat(20))
    private val onEthereum = AccountId(Network.ETHEREUM, alice)
    private val onGnosis = AccountId(Network.GNOSIS, alice)

    private val kept = KeptAddressIndex(
        coverage = AddressIndexCoverage(
            importer = Address.of("0x" + "1e".repeat(20)),
            headUpdate = 7,
            covered = 21_000_000L..21_200_000L,
            headGap = AddressIndexHeadGap(21_200_001, null),
            tailGap = 0L..20_999_999L,
            gaps = listOf(21_100_000L..21_100_999L),
        ),
        top = 21_200_000,
        rows = listOf(
            AddressIndexRow(
                entry = AddressIndexEntry(21_150_000, 4, setOf(AddressIndexRole.FROM, AddressIndexRole.TRACE)),
                blockHash = "0x" + "bb".repeat(32),
                final = true,
                confirmed = setOf(AddressIndexRole.FROM),
                unchecked = setOf(AddressIndexRole.TRACE),
                transaction = AddressIndexTransaction(
                    hash = "0x" + "cc".repeat(32),
                    from = alice,
                    to = token,
                    value = Wei.of("0"),
                    transfer = TokenTransfer.Fungible(token, alice, bob, UInt256.of("1000000")),
                ),
                blockTimestamp = 1_795_600_000,
            ),
            AddressIndexRow(
                entry = AddressIndexEntry(21_140_000, 2, setOf(AddressIndexRole.TO)),
                blockHash = "0x" + "dd".repeat(32),
                final = true,
                confirmed = setOf(AddressIndexRole.TO),
                unchecked = emptySet(),
                transaction = AddressIndexTransaction(
                    hash = "0x" + "ee".repeat(32),
                    from = bob,
                    to = null,
                    value = Wei.of("5"),
                    transfer = TokenTransfer.Nft(token, bob, alice, UInt256.of("42")),
                    succeeded = true,
                ),
            ),
            AddressIndexRow(
                entry = AddressIndexEntry(21_130_000, 0, setOf(AddressIndexRole.BLOCK)),
                blockHash = "0x" + "ff".repeat(32),
                final = true,
                confirmed = setOf(AddressIndexRole.BLOCK),
                unchecked = emptySet(),
                transaction = null,
            ),
        ),
        recheck = listOf(AddressIndexEntry(21_120_000, 9, setOf(AddressIndexRole.WORD))),
        unverified = listOf(AddressIndexEntry(21_119_000, 1, setOf(AddressIndexRole.AUTH))),
        contradicted = listOf(
            AddressIndexEntry(21_118_000, 2, setOf(AddressIndexRole.EMITTER, AddressIndexRole.TOPIC)),
        ),
        traceOnly = listOf(AddressIndexEntry(21_117_000, 3, setOf(AddressIndexRole.TRACE))),
        unread = listOf(21_100_000L..21_100_999L),
        next = AddressIndexCursor("7:21120000:9"),
    )

    private fun TestScope.archive() =
        FileAddressIndexArchive(directory.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun aKeptListSurvivesARestartAsItWasWritten() = runTest {
        assertNull(archive().read(onEthereum), "nothing kept yet")

        assertTrue(archive().write(onEthereum, kept))

        assertEquals(kept, archive().read(onEthereum))
    }

    @Test
    fun eachAccountKeepsItsOwnList() = runTest {
        val archive = archive()
        archive.write(onEthereum, kept)
        archive.write(onGnosis, kept.copy(top = 21_100_999, rows = emptyList(), next = null))

        assertEquals(kept, archive.read(onEthereum))
        assertEquals(21_100_999, archive.read(onGnosis)?.top)
        assertEquals(2, SystemFileSystem.list(directory).size, "one file per account")
    }

    @Test
    fun aForgottenListIsGoneAfterARestart() = runTest {
        archive().write(onEthereum, kept)

        assertTrue(archive().forget(onEthereum))

        assertNull(archive().read(onEthereum))
        assertTrue(SystemFileSystem.list(directory).isEmpty(), "no file names the address any more")
    }

    @Test
    fun forgettingAlsoDeletesADamagedCopySetAsideAndNoOtherAccountsFile() = runTest {
        val archive = archive()
        archive.write(onEthereum, kept)
        archive.write(onGnosis, kept)
        val ethereumFile = SystemFileSystem.list(directory).single { it.name.contains("-1-") }
        SystemFileSystem.sink(Path(directory, ethereumFile.name + ".damaged-1f")).buffered().use { it.writeString("{") }

        assertTrue(archive().forget(onEthereum))

        assertEquals(listOf("address-index-100-${alice.hex}.json"), SystemFileSystem.list(directory).map { it.name })
    }

    @Test
    fun aListKeptBeforeRowsCarriedTheirTimeIsReadAgainFromTheIndex() = runTest {
        archive().write(onEthereum, kept)
        val file = SystemFileSystem.list(directory).single()
        val text = SystemFileSystem.source(file).buffered().use { source -> source.readString() }
        SystemFileSystem.sink(file).buffered().use { it.writeString(text.replaceFirst("\"version\":2", "\"version\":1")) }

        assertNull(archive().read(onEthereum), "a format-1 list is read again, so that its rows get their time")
        assertEquals(1, SystemFileSystem.list(directory).size, "nothing set aside: it is no damaged file")
    }

    @Test
    fun aRowOfABlockNotFinalDamagesTheFileAndIsSetAside() = runTest {
        archive().write(onEthereum, kept)
        val file = SystemFileSystem.list(directory).single()
        val text = SystemFileSystem.source(file).buffered().use { source -> source.readString() }
        val notFinal = text.replaceFirst("\"final\":true", "\"final\":false")
        SystemFileSystem.sink(file).buffered().use { it.writeString(notFinal) }

        assertNull(archive().read(onEthereum), "a damaged file reads as nothing kept")
        assertTrue(SystemFileSystem.list(directory).single().name.contains(".damaged-"), "kept aside, never deleted")
    }
}
