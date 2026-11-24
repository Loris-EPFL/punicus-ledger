package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [SwarmCalls] against the PostageStamp's own calls, as read on Gnosis Chain on 2026-11-23. */
class SwarmCallsTest {

    @Test
    fun theSelectorsAreThePostageStamps() {
        // cast sig "createBatch(address,uint256,uint8,uint8,bytes32,bool)", "topUp(bytes32,uint256)",
        // "increaseDepth(bytes32,uint8)": as Bee's ABI module and the deployed dispatcher name them.
        assertEquals("5239af71", SwarmCalls.CREATE_BATCH.toHex())
        assertEquals("b67644b9", SwarmCalls.TOP_UP.toHex())
        assertEquals("47aab79b", SwarmCalls.INCREASE_DEPTH.toHex())
    }

    @Test
    fun aPurchaseIsEncodedAsARealOneWas() {
        // The input of the createBatch of batch 0xaf13…8db2 (transaction 0x5483…02f6).
        val input = "5239af71" +
            "000000000000000000000000369e38fb83148fb269f74863006e0e6210fe0c3e" +
            "000000000000000000000000000000000000000000000000000000015a11c000" +
            "0000000000000000000000000000000000000000000000000000000000000016" +
            "0000000000000000000000000000000000000000000000000000000000000010" +
            "51542bc3bb30c150f1e90b5cc3ec6bdd0f8daee9deabf92b338f4bd98f5ce0d6" +
            "0000000000000000000000000000000000000000000000000000000000000001"
        val data = SwarmCalls.createBatch(
            Address.of("0x369e38fb83148fb269f74863006e0e6210fe0c3e"),
            UInt256.of(5_806_080_000),
            depth = 22,
            nonce = "51542bc3bb30c150f1e90b5cc3ec6bdd0f8daee9deabf92b338f4bd98f5ce0d6".hexToBytes(),
            immutable = true,
        )
        assertEquals(input, data.toHex())
    }

    @Test
    fun aTopUpAndADilutionNameTheBatch() {
        val id = BatchId.of("ab".repeat(32))
        val topUp = "b67644b9" + "ab".repeat(32) + "0".repeat(61) + "3e8"
        assertEquals(topUp, SwarmCalls.topUp(id, UInt256.of(1_000)).toHex())
        assertEquals("47aab79b" + "ab".repeat(32) + "0".repeat(62) + "17", SwarmCalls.increaseDepth(id, 23).toHex())
    }

    @Test
    fun theFiguresAreThePostageStamps() {
        // The batch's total: 2^22 chunks.
        assertEquals(UInt256.of(5_806_080_000L * 4_194_304), SwarmCalls.total(UInt256.of(5_806_080_000), 22))
        // A day at the price read: 17,280 blocks, the PostageStamp's minimum.
        assertEquals(UInt256.of(17_280L * 207_062), SwarmCalls.perChunkFor(86_400, UInt256.of(207_062)))
        // A part of a block is a whole one.
        assertEquals(UInt256.of(2 * 10), SwarmCalls.perChunkFor(6, UInt256.of(10)))
        // Halved with each step of depth.
        assertEquals(UInt256.of(25), SwarmCalls.remainingAfterDilution(UInt256.of(100), 22, 24))
    }

    @Test
    fun onlyTheDepthsOfferedAreBought() {
        assertFailsWith<IllegalArgumentException> {
            SwarmCalls.createBatch(Address.of("0x" + "11".repeat(20)), UInt256.of(1), 16, ByteArray(32), false)
        }
        assertFailsWith<IllegalArgumentException> {
            SwarmCalls.createBatch(Address.of("0x" + "11".repeat(20)), UInt256.of(1), 17, ByteArray(31), false)
        }
    }
}
