package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BatchId] and [PostageBatch] against a real batch: `0xaf13…8db2`, created on Gnosis Chain at
 * block 49,422,809 by `0x369e…0c3e` (transaction `0x5483…02f6`), read on 2026-11-23 from a
 * public node, a debugging source only.
 */
class SwarmTest {

    private val buyer = Address.of("0x369e38fb83148fb269f74863006e0e6210fe0c3e")
    private val id = BatchId.of("0xaf132f8c833af8d2da19979a31603c6507435ea4ce1194adf14b45a54cc78db2")

    @Test
    fun aBatchIdIsTheHashOfItsBuyerAndNonce() {
        // The createBatch call's `_nonce`, and the id its BatchCreated log names.
        val nonce = "51542bc3bb30c150f1e90b5cc3ec6bdd0f8daee9deabf92b338f4bd98f5ce0d6".hexToBytes()
        assertEquals(id, BatchId.of(buyer, nonce))
    }

    @Test
    fun aBatchIdParsesAsBeeShowsIt() {
        assertEquals(id, BatchId.parse("  AF132F8C833AF8D2DA19979A31603C6507435EA4CE1194ADF14B45A54CC78DB2 "))
        assertNull(BatchId.parse("0xaf13"))
        assertNull(BatchId.parse("0x" + "g".repeat(64)))
        assertFailsWith<IllegalArgumentException> { BatchId.of(buyer, ByteArray(31)) }
    }

    @Test
    fun theTimeToLiveIsTheBalanceLeftOverThePrice() {
        // remainingBalance 0x155974299 and lastPrice 0x32883 at the same block.
        val batch = batch(remaining = UInt256.of(5_730_943_641), price = UInt256.of(206_979))
        assertEquals(UInt256.of(27_688), batch.blocksLeft)
        assertEquals(138_440L, batch.secondsLeft)
        assertFalse(batch.expired)
        assertEquals(UInt256.of(4_194_304), batch.theoreticalChunks)
    }

    @Test
    fun noBalanceLeftHasExpiredAndNoPriceTellsNoTime() {
        assertTrue(batch(remaining = UInt256.of(0), price = UInt256.of(206_979)).expired)
        assertNull(batch(remaining = UInt256.of(10), price = UInt256.of(0)).secondsLeft)
    }

    @Test
    fun aBucketIsNoDeeperThanItsBatch() {
        assertFailsWith<IllegalArgumentException> {
            PostageBatch(id, buyer, 16, 17, false, UInt256.of(1), UInt256.of(1))
        }
    }

    @Test
    fun aChequebookCannotFreeMoreThanItHolds() {
        val address = Address.of("0x614f90324e7ac11a08c29f3313de9f4c7e047b77")
        assertFailsWith<IllegalArgumentException> {
            Chequebook(address, buyer, UInt256.of(1), UInt256.of(2), UInt256.of(0), bounced = false)
        }
    }

    private fun batch(remaining: UInt256, price: UInt256) =
        PostageBatch(id, buyer, depth = 22, bucketDepth = 16, immutable = true, remaining, price)
}
