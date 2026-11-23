package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Chequebook
import eth.serotine.domain.model.PostageBatch
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BatchFinding
import eth.serotine.domain.repository.ChequebookFinding
import eth.serotine.domain.repository.SwarmReading
import eth.serotine.domain.usecase.SwarmItem
import eth.serotine.domain.usecase.SwarmNodeReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a Swarm node's row on its account's screen says ([swarmEntry]). */
class SwarmEntryTest {

    private val node = Address.of("0x" + "0b".repeat(20))
    private val chequebook = Address.of("0x" + "cb".repeat(20))
    private val one = UInt256.of(1)

    private fun batch(id: String): SwarmItem<BatchId, BatchFinding> {
        val batchId = BatchId.of(id.repeat(32))
        val found = BatchFinding.Node(PostageBatch(batchId, node, 22, 16, false, one, one))
        return SwarmItem(batchId, SwarmReading.Verified(found, 1))
    }

    @Test
    fun theRowCountsWhatIsProvenTheNodes() {
        val read = SwarmNodeReading.Read(
            listOf(
                SwarmItem(
                    chequebook,
                    SwarmReading.Verified(ChequebookFinding.Node(Chequebook(chequebook, node, one, one, one, false)), 1),
                ),
            ),
            listOf(batch("ab"), batch("cd"), SwarmItem(BatchId.of("ef".repeat(32)), null)),
        )
        assertEquals("Swarm node: a chequebook and 2 postage batches", swarmEntry(read))
        assertEquals("Swarm node: a postage batch", swarmEntry(SwarmNodeReading.Read(emptyList(), listOf(batch("ab")))))
    }

    @Test
    fun nothingProvenIsNoRow() {
        assertNull(swarmEntry(SwarmNodeReading.Read(emptyList(), listOf(SwarmItem(BatchId.of("ab".repeat(32)), null)))))
        assertNull(swarmEntry(SwarmNodeReading.NotASwarmAccount))
    }
}
