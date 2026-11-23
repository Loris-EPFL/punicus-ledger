package eth.serotine.ui.activity

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchId
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Activity's rows of a Swarm node's postage batch calls (docs/swarm.md, *History*). */
class SwarmActivityTest {

    private val stamp = Address.of("0x45a1502382541cd610cc9068e88727426b696293")
    private val node = Address.of("0x" + "0b".repeat(20))
    private val payer = Address.of("0x" + "a1".repeat(20))
    private val id = BatchId.of("a1b2c3d4".repeat(8))
    private val bzz =
        Token(Address.of("0xdbf3ea6f5bee45c02255b2c26a16f300502f68da"), "BZZ", "BZZ on xDai", 16, icon = "bzz")

    private fun entry(from: Address, action: SentAction, nonce: Long = 1) = HistoryEntry(
        SentTransfer(
            Network.GNOSIS,
            from,
            nonce,
            SignedTransfer(byteArrayOf(nonce.toByte())),
            action,
            UInt256.of(1),
            1_800_000_000,
        ),
    )

    private fun rows(of: Address) = ActivityRows(of, Network.GNOSIS, formatTime = { "<$it>" })

    @Test
    fun aPurchaseSaysWhomItWasForAndWhoPaid() {
        val bought = entry(payer, SentAction.BatchPurchase(bzz, stamp, node, id, 22, UInt256.of(1), true))
        val paid = rows(payer).row(bought, emptyMap())
        assertEquals("Bought a postage batch", paid.title)
        assertEquals(true, paid.detail.startsWith("For "))
        val forTheNode = rows(node).row(bought, emptyMap())
        assertEquals("Postage batch bought", forTheNode.title)
        assertEquals(true, forTheNode.detail.startsWith("Paid by "))
    }

    @Test
    fun aTopUpAndADilutionNameTheBatch() {
        val topUp = entry(payer, SentAction.BatchTopUp(bzz, stamp, node, id, 22, UInt256.of(1)))
        val paid = rows(payer).row(topUp, emptyMap())
        assertEquals("Topped up batch 0xa1b2c3d4…c3d4", paid.title)
        assertEquals(true, paid.detail.startsWith("For "))
        val forTheNode = rows(node).row(topUp, emptyMap())
        assertEquals("Batch 0xa1b2c3d4…c3d4 topped up", forTheNode.title)
        assertEquals(true, forTheNode.detail.startsWith("Paid by "))
        assertEquals(
            "Diluted batch 0xa1b2c3d4…c3d4 to depth 23",
            rows(node).row(entry(node, SentAction.BatchDilution(stamp, id, 23)), emptyMap()).title,
        )
    }
}
