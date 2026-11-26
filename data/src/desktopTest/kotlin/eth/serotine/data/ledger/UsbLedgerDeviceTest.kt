package eth.serotine.data.ledger

import eth.serotine.data.keys.Secp256k1Recovery
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.LedgerProblem
import eth.serotine.domain.repository.LedgerResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UsbLedgerDeviceTest {
    @Test
    fun enrollmentRequestsDeviceConfirmationWithoutChainCode() = runTest {
        val reports = ReportScript({ replies(it, addressResponse) })
        val path = assertNotNull(LedgerPath.parse("m/44'/60'/2147483647'/2147483647/0"))

        assertEquals(LedgerResult.Ok(address), UsbLedgerDevice { reports }.confirmAddress(path))

        assertContentEquals(
            "e002010015058000002c8000003cffffffff7fffffff00000000".hexToByteArray(),
            sentApdu(reports.writes),
        )
        assertTrue(reports.closed)
    }

    @Test
    fun signsTheEthereumAndSepoliaVectorsUsingTheSameHandleAsTheSenderCheck() = runTest {
        for ((network, unsigned, s) in vectors) {
            val signatureReply = signatureReply(s)
            val reports = ReportScript({ replies(it, addressResponse) }, { replies(it, signatureReply) })
            var opens = 0
            val device = UsbLedgerDevice { opens++; reports }

            val transaction = transaction(network)
            val result = device.signTransaction(LedgerPath.DEFAULT, address, transaction)

            val signature = Signature(R, s, 1)
            assertEquals(LedgerResult.Ok(signature), result)
            assertEquals(address, Secp256k1Recovery().recover(transaction.signingDigest(), signature))
            assertEquals(1, opens)
            assertEquals(3, reports.writes.size)
            assertContentEquals("e002000015$DEFAULT_PATH".hexToByteArray(), sentApdu(reports.writes.take(1)))
            val expected = unsigned.hexToByteArray()
            assertContentEquals(
                byteArrayOf(0xe0.toByte(), 0x04, 0, 0, (21 + expected.size).toByte()) +
                    DEFAULT_PATH.hexToByteArray() + expected,
                sentApdu(reports.writes.drop(1)),
            )
            assertTrue(reports.closed)
        }
    }

    @Test
    fun refusesTheWrongSenderBeforeAskingToSign() = runTest {
        val reports = ReportScript({ replies(it, addressResponse) })
        val result = UsbLedgerDevice { reports }.signTransaction(LedgerPath.DEFAULT, recipient, transaction())
        assertEquals(LedgerResult.Failed(LedgerProblem.WRONG_ACCOUNT), result)
        assertEquals(1, reports.writes.size)
        assertTrue(reports.closed)
    }

    @Test
    fun refusesUnsupportedTransactionsBeforeOpeningUsb() = runTest {
        val device = UsbLedgerDevice { error("unsupported transactions must not open USB") }
        val unsupported = listOf(
            transaction(Network.GNOSIS),
            transaction(data = byteArrayOf(1)),
            transaction(value = UInt256.of(0)),
            transaction(to = Address.ZERO),
        )
        for (transaction in unsupported) {
            assertEquals(
                LedgerResult.Failed(LedgerProblem.UNSUPPORTED_TRANSACTION),
                device.signTransaction(LedgerPath.DEFAULT, address, transaction),
            )
        }
    }

    @Test
    fun statusWordsAreTypedAndNeverRetried() = runTest {
        val rejects = mapOf(
            "6985" to LedgerProblem.REJECTED,
            "5515" to LedgerProblem.OPEN_ETHEREUM,
            "6d00" to LedgerProblem.OPEN_ETHEREUM,
            "6e00" to LedgerProblem.OPEN_ETHEREUM,
            "6401" to LedgerProblem.TIMED_OUT,
            "9000" to LedgerProblem.INVALID_RESPONSE,
            "006985" to LedgerProblem.INVALID_RESPONSE,
            "6a80" to LedgerProblem.INVALID_RESPONSE,
        )
        for ((status, problem) in rejects) {
            val reports = ReportScript({ replies(it, status.hexToByteArray()) })
            assertEquals(LedgerResult.Failed(problem), UsbLedgerDevice { reports }.confirmAddress(LedgerPath.DEFAULT))
            assertEquals(1, reports.writes.size)
            assertTrue(reports.closed)
        }
    }

    @Test
    fun signingRejectionDoesNotTryAnotherCommand() = runTest {
        val reports = ReportScript({ replies(it, addressResponse) }, { replies(it, "6985".hexToByteArray()) })
        val result = UsbLedgerDevice { reports }.signTransaction(LedgerPath.DEFAULT, address, transaction())
        assertEquals(LedgerResult.Failed(LedgerProblem.REJECTED), result)
        assertEquals(3, reports.writes.size)
        assertTrue(reports.closed)
    }

    @Test
    fun reportsUnavailableNativeLibrary() = runTest {
        val device = UsbLedgerDevice { throw UnsatisfiedLinkError("missing HIDAPI") }
        assertEquals(LedgerResult.Failed(LedgerProblem.UNAVAILABLE), device.confirmAddress(LedgerPath.DEFAULT))
    }

    @Test
    fun aCancelledReadDoesNotReturnApprovalAndAlwaysClosesUsb() = runTest {
        lateinit var attempt: Deferred<LedgerResult<Address>>
        val reports = ReportScript({
            attempt.cancel()
            replies(it, addressResponse)
        })
        attempt = async(start = CoroutineStart.LAZY) { UsbLedgerDevice { reports }.confirmAddress(LedgerPath.DEFAULT) }
        attempt.join()

        assertTrue(attempt.isCancelled)
        assertTrue(reports.closed)
        assertEquals(1, reports.reads)
    }

    @Test
    fun rejectsInvalidPublicKeyAddressLengthAndAddressMismatch() {
        val invalidReplies = listOf(
            addressResponse.copyOf().apply { this[0] = 64 },
            addressResponse.copyOf().apply { this[1] = 2 },
            addressResponse.copyOf().apply { fill(0, 2, 66) },
            addressResponse.copyOf().apply { this[66] = 39 },
            addressResponse.copyOf().apply { this[67] = 'z'.code.toByte() },
            addressResponse.copyOf().apply { this[67] = '0'.code.toByte() },
            addressResponse.copyOfRange(1, addressResponse.size),
        )
        for (reply in invalidReplies) {
            assertEquals(LedgerProblem.INVALID_RESPONSE, assertFailsWith<LedgerFailure> {
                LedgerEthereum.parseAddress(reply)
            }.problem)
        }
    }

    @Test
    fun acceptsOnlyTypedParityAndValidEthereumScalars() {
        val response = signatureReply(vectors.first().third)
        assertEquals(1, LedgerEthereum.parseSignature(response).recoveryId)
        assertEquals(0, LedgerEthereum.parseSignature(response.copyOf().apply { this[0] = 0 }).recoveryId)
        val invalidReplies = listOf(
            response.copyOf().apply { this[0] = 27 },
            response.copyOf().apply { this[0] = 2 },
            response.copyOf().apply { fill(0, 1, 33) },
            response.copyOf().apply { fill(0xff.toByte(), 1, 33) },
            response.copyOf().apply { fill(0x80.toByte(), 33, 65) },
            response.copyOfRange(1, response.size),
        )
        for (reply in invalidReplies) {
            assertEquals(LedgerProblem.INVALID_RESPONSE, assertFailsWith<LedgerFailure> {
                LedgerEthereum.parseSignature(reply)
            }.problem)
        }
    }

    private fun transaction(
        network: Network = Network.ETHEREUM,
        data: ByteArray = byteArrayOf(),
        to: Address = recipient,
        value: UInt256 = UInt256.of(1_000_000_000_000_000_000),
    ) = Eip1559Transaction(
        chainId = network.chainId,
        nonce = 785,
        maxPriorityFeePerGas = UInt256.of(2_000_000_000),
        maxFeePerGas = UInt256.of(2_000_000_000),
        gasLimit = 21_000,
        to = to,
        value = value,
        data = data,
    )

    private fun sentApdu(reports: List<ByteArray>): ByteArray {
        val first = reports.first()
        val length = first.uint16(6)
        val bytes = ByteArray(length)
        var copied = 0
        for ((index, report) in reports.withIndex()) {
            assertEquals(first.uint16(1), report.uint16(1))
            assertEquals(index, report.uint16(4))
            val offset = if (index == 0) 8 else 6
            val count = minOf(length - copied, report.size - offset)
            report.copyInto(bytes, copied, offset, offset + count)
            copied += count
        }
        assertEquals(length, copied)
        return bytes
    }

    private companion object {
        const val DEFAULT_PATH = "058000002c8000003c800000000000000000000000"
        // Synthetic account with public key equal to the SEC secp256k1 generator. No real funds or seed.
        const val R = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        const val Y = "483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8"
        val address = Address.of("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf")
        val recipient = Address.of("0x70997970c51812dc3a010c7d01b50e0d17dc79c8")
        val addressResponse = "4104$R${Y}28".hexToByteArray() +
            "7E5F4552091A69125d5DFcB7b8C2659029395Bdf".encodeToByteArray() + "9000".hexToByteArray()

        // Independent typed RLP and Keccak-256 (OpenSSL), with ECDSA r=G.x and s=lowS(hash + r).
        // High s in each unnormalized signature flips the parity of the even-y point G to 1.
        val vectors = listOf(
            Triple(
                Network.ETHEREUM,
                "02f101820311847735940084773594008252089470997970c51812dc3a010c7d01b50e0d17dc79c8880de0b6b3a764000080c0",
                "2c8aadec3e7632312c0e46cf330574981b264c573f699c2bbc6472414a43fae3",
            ),
            Triple(
                Network.SEPOLIA,
                "02f483aa36a7820311847735940084773594008252089470997970c51812dc3a010c7d01b50e0d17dc79c8880de0b6b3a764000080c0",
                "4db023b6d8c83b384320d5dba81c6c28c89420347fcb5b4a26d39a69483577a1",
            ),
        )

        fun signatureReply(s: String): ByteArray = "01$R${s}9000".hexToByteArray()
    }
}
