package eth.serotine.data.swarm

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.SwarmCandidate
import eth.serotine.domain.repository.SwarmCandidateChange
import eth.serotine.domain.repository.SwarmCandidateList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

@OptIn(ExperimentalCoroutinesApi::class)
class FileSwarmCandidatesTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-swarm-${Random.nextLong().toULong()}")
    private val file = Path(directory, "swarm.json")
    private val node = AccountId(Network.GNOSIS, Address.of("0x0b522dfb53807fd9a9a974ee19d2637205970086"))
    private val chequebook = Address.of("0x614f90324e7ac11a08c29f3313de9f4c7e047b77")
    private val batch = BatchId.of("af132f8c833af8d2da19979a31603c6507435ea4ce1194adf14b45a54cc78db2")

    private fun TestScope.candidates() = FileSwarmCandidates(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun candidatesSurviveARestartPerNode() = runTest {
        val first = candidates()
        assertEquals(SwarmCandidateChange.CHANGED, first.add(node, SwarmCandidate.ChequebookAt(chequebook)))
        assertEquals(SwarmCandidateChange.ALREADY_PRESENT, first.add(node, SwarmCandidate.ChequebookAt(chequebook)))
        assertEquals(SwarmCandidateChange.CHANGED, first.add(node, SwarmCandidate.Batch(batch)))

        assertEquals(SwarmCandidateList.Loaded(listOf(chequebook), listOf(batch)), candidates().observe(node).first())
        // The same address on another network is another account, with nothing kept.
        assertEquals(
            SwarmCandidateList.Loaded(emptyList(), emptyList()),
            candidates().observe(node.copy(network = Network.ETHEREUM)).first(),
        )
    }

    @Test
    fun aRemovedCandidateIsGoneAndANodeWithNoneLeavesTheFile() = runTest {
        val store = candidates()
        store.add(node, SwarmCandidate.Batch(batch))
        assertEquals(SwarmCandidateChange.NOT_FOUND, store.remove(node, SwarmCandidate.ChequebookAt(chequebook)))
        assertEquals(SwarmCandidateChange.CHANGED, store.remove(node, SwarmCandidate.Batch(batch)))
        assertEquals(SwarmCandidateChange.NOT_FOUND, store.remove(node, SwarmCandidate.Batch(batch)))
        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        assertTrue(node.address.hex.removePrefix("0x").lowercase() !in text.lowercase(), text)
    }

    @Test
    fun aDamagedFileIsSetAsideAndSaysSo() = runTest {
        SystemFileSystem.createDirectories(directory)
        val damaged = "{\"version\":1,\"nodes\":[{\"chain\":100,\"address\":\"nope\"}]}"
        SystemFileSystem.sink(file).buffered().use { it.writeString(damaged) }
        assertEquals(
            SwarmCandidateList.Loaded(emptyList(), emptyList(), setAside = true),
            candidates().observe(node).first(),
        )
    }

    @Test
    fun aNodeOnAnUnknownNetworkIsPassedOverAndKeptAside() = runTest {
        SystemFileSystem.createDirectories(directory)
        val address = node.address.hex
        SystemFileSystem.sink(file).buffered().use {
            it.writeString(
                """{"version":1,"nodes":[""" +
                    """{"chain":100,"address":"$address","batches":["${batch.hex}"]},""" +
                    """{"chain":999999,"address":"$address","batches":["${batch.hex}"]}]}""",
            )
        }
        // Read as far as this version can, and said to be: the whole file is kept aside as it was.
        assertEquals(
            SwarmCandidateList.Loaded(emptyList(), listOf(batch), setAside = true),
            candidates().observe(node).first(),
        )
        assertEquals(1, SystemFileSystem.list(directory).count { it.name.startsWith("swarm.json.damaged-") })
    }

    @Test
    fun aNewerFormatIsUnreadableAndNothingChanges() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("{\"version\":2,\"nodes\":[]}") }
        assertEquals(SwarmCandidateList.Unreadable, candidates().observe(node).first())
        assertEquals(SwarmCandidateChange.NOT_SAVED, candidates().add(node, SwarmCandidate.Batch(batch)))
    }
}
