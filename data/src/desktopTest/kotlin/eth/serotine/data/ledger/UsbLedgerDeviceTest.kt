package eth.serotine.data.ledger

import eth.serotine.data.keys.Secp256k1Recovery
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Eip712Hashes
import eth.serotine.domain.model.LedgerPath
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PersonalMessage
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
    fun refusesTheWrongSenderBeforeAnySigningCommand() = runTest {
        val signers: List<suspend (UsbLedgerDevice) -> LedgerResult<Signature>> = listOf(
            { it.signTransaction(LedgerPath.DEFAULT, recipient, transaction()) },
            { it.signTypedData(LedgerPath.DEFAULT, recipient, typedHashes) },
            { it.signPersonalMessage(LedgerPath.DEFAULT, recipient, assertNotNull(PersonalMessage.of(byteArrayOf(1)))) },
        )
        for (sign in signers) {
            val reports = ReportScript({ replies(it, addressResponse) })
            assertEquals(LedgerResult.Failed(LedgerProblem.WRONG_ACCOUNT), sign(UsbLedgerDevice { reports }))
            assertEquals(1, reports.writes.size)
            assertTrue(reports.closed)
        }
    }

    @Test
    fun refusesAnUnknownNetworkBeforeOpeningUsb() = runTest {
        val device = UsbLedgerDevice { error("unsupported transactions must not open USB") }
        assertEquals(
            LedgerResult.Failed(LedgerProblem.UNSUPPORTED_TRANSACTION),
            device.signTransaction(LedgerPath.DEFAULT, address, transaction(chainId = 999)),
        )
    }

    @Test
    fun streamsTheExactArbitraryCalldataTransactionAtShortApduBoundaries() = runTest {
        for ((size, lengths) in listOf(
            189 to listOf(234),
            190 to listOf(234, 1),
            442 to listOf(234, 255),
            443 to listOf(234, 255, 1),
        )) {
            val data = ByteArray(size) { (it * 177 + 36).toByte() }
            val transaction = transaction(Network.GNOSIS, data, to = Address.ZERO, value = UInt256.ZERO)
            val reports = signingReports(lengths.size, config = blindSigningOff)
            val result = UsbLedgerDevice { reports }.signTransaction(LedgerPath.DEFAULT, address, transaction)

            assertEquals(LedgerResult.Ok(Signature(R, vectors.first().third, 1)), result)
            val commands = sentApdus(reports.writes)
            assertContentEquals("e002000015$DEFAULT_PATH".hexToByteArray(), commands[0])
            assertContentEquals("e006000000".hexToByteArray(), commands[1])
            assertStream(0x04, transaction.encodeUnsigned(), DEFAULT_PATH, lengths, commands.drop(2))
            assertTrue(reports.closed)
        }
    }

    @Test
    fun intermediateChunksRequireEmptySuccessAndAreNeverResent() = runTest {
        for ((reply, problem) in listOf(
            "019000" to LedgerProblem.INVALID_RESPONSE,
            "6985" to LedgerProblem.REJECTED,
            "6401" to LedgerProblem.TIMED_OUT,
        )) {
            val reports = ReportScript(
                { replies(it, addressResponse) },
                { replies(it, blindSigningOn) },
                { replies(it, reply.hexToByteArray()) },
            )
            val tx = transaction(data = ByteArray(600))
            assertEquals(
                LedgerResult.Failed(problem),
                UsbLedgerDevice { reports }.signTransaction(LedgerPath.DEFAULT, address, tx),
            )
            assertEquals(3, sentApdus(reports.writes).size)
            assertTrue(reports.closed)
        }
    }

    @Test
    fun aCalldataRefusalOnlySuggestsBlindSigningIfTheDeviceReportedItDisabled() = runTest {
        for ((config, problem) in listOf(
            blindSigningOff to LedgerProblem.BLIND_SIGNING_DISABLED,
            blindSigningOn to LedgerProblem.INVALID_RESPONSE,
        )) {
            val reports = ReportScript(
                { replies(it, addressResponse) },
                { replies(it, config) },
                { replies(it, "6a80".hexToByteArray()) },
            )
            val tx = transaction(data = byteArrayOf(1))
            assertEquals(
                LedgerResult.Failed(problem),
                UsbLedgerDevice { reports }.signTransaction(LedgerPath.DEFAULT, address, tx),
            )
            assertEquals(3, sentApdus(reports.writes).size)
            assertTrue(reports.closed)
        }
    }

    @Test
    fun typedDataSendsV0DomainAndStructHashesAndAppliesFirmwareParity() = runTest {
        val reports = signingReports(1, config = blindSigningOn, v = 27)
        val result = UsbLedgerDevice { reports }.signTypedData(LedgerPath.DEFAULT, address, typedHashes)

        assertEquals(LedgerResult.Ok(Signature(R, vectors.first().third, 0)), result)
        val commands = sentApdus(reports.writes)
        assertEquals(3, commands.size)
        assertContentEquals("e002000015$DEFAULT_PATH".hexToByteArray(), commands[0])
        assertContentEquals("e006000000".hexToByteArray(), commands[1])
        assertContentEquals(
            ("e00c000055$DEFAULT_PATH" + typedHashes.domainSeparator.hex + typedHashes.structHash.hex).hexToByteArray(),
            commands[2],
        )
        assertTrue(reports.closed)
    }

    @Test
    fun typedDataRefusesDisabledBlindSigningBeforeSendingHashes() = runTest {
        val reports = ReportScript({ replies(it, addressResponse) }, { replies(it, blindSigningOff) })
        val result = UsbLedgerDevice { reports }.signTypedData(LedgerPath.DEFAULT, address, typedHashes)

        assertEquals(LedgerResult.Failed(LedgerProblem.BLIND_SIGNING_DISABLED), result)
        val commands = sentApdus(reports.writes)
        assertEquals(2, commands.size)
        assertContentEquals("e006000000".hexToByteArray(), commands.last())
        assertTrue(reports.closed)
    }

    @Test
    fun personalSignStreamsLiteralBytesWithBigEndianLengthOnlyInTheFirstCommand() = runTest {
        for ((size, lengths) in listOf(
            0 to listOf(0),
            230 to listOf(230),
            231 to listOf(230, 1),
            486 to listOf(230, 255, 1),
        )) {
            val bytes = ByteArray(size) { (128 + it).toByte() }
            val message = assertNotNull(PersonalMessage.of(bytes))
            val reports = signingReports(lengths.size, v = 28)
            val result = UsbLedgerDevice { reports }.signPersonalMessage(LedgerPath.DEFAULT, address, message)

            assertEquals(LedgerResult.Ok(Signature(R, vectors.first().third, 1)), result)
            val commands = sentApdus(reports.writes)
            assertContentEquals("e002000015$DEFAULT_PATH".hexToByteArray(), commands[0])
            val length = size.toString(16).padStart(8, '0')
            assertStream(0x08, bytes, DEFAULT_PATH + length, lengths, commands.drop(1))
            assertTrue(reports.closed)
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

    @Test
    fun acceptsOnlyLegacyMessageParity() {
        val response = signatureReply(vectors.first().third, v = 27)
        assertEquals(0, LedgerEthereum.parseSignature(response, vBase = 27).recoveryId)
        assertEquals(1, LedgerEthereum.parseSignature(response.copyOf().apply { this[0] = 28 }, vBase = 27).recoveryId)
        for (v in listOf(0, 1, 26, 29, 30, 255)) {
            val failure = assertFailsWith<LedgerFailure> {
                LedgerEthereum.parseSignature(response.copyOf().apply { this[0] = v.toByte() }, vBase = 27)
            }
            assertEquals(LedgerProblem.INVALID_RESPONSE, failure.problem)
        }
    }

    private fun transaction(
        network: Network = Network.ETHEREUM,
        data: ByteArray = byteArrayOf(),
        to: Address = recipient,
        value: UInt256 = UInt256.of(1_000_000_000_000_000_000),
        chainId: Long = network.chainId,
    ) = Eip1559Transaction(
        chainId = chainId,
        nonce = 785,
        maxPriorityFeePerGas = UInt256.of(2_000_000_000),
        maxFeePerGas = UInt256.of(2_000_000_000),
        gasLimit = 21_000,
        to = to,
        value = value,
        data = data,
    )

    private fun signingReports(chunks: Int, config: ByteArray? = null, v: Int = 1): ReportScript {
        val scripts = buildList<(Int) -> List<ByteArray>> {
            add { replies(it, addressResponse) }
            if (config != null) add { replies(it, config) }
            repeat(chunks - 1) { add { replies(it, "9000".hexToByteArray()) } }
            add { replies(it, signatureReply(vectors.first().third, v)) }
        }
        return ReportScript(*scripts.toTypedArray())
    }

    private fun assertStream(
        ins: Int,
        source: ByteArray,
        firstPrefix: String,
        lengths: List<Int>,
        commands: List<ByteArray>,
    ) {
        assertEquals(lengths.size, commands.size)
        var offset = 0
        for ((index, count) in lengths.withIndex()) {
            val prefix = if (index == 0) firstPrefix.hexToByteArray() else byteArrayOf()
            val header = byteArrayOf(
                0xe0.toByte(), ins.toByte(), if (index == 0) 0 else 0x80.toByte(), 0, (prefix.size + count).toByte(),
            )
            assertContentEquals(header + prefix + source.copyOfRange(offset, offset + count), commands[index])
            offset += count
        }
        assertEquals(source.size, offset)
    }

    private fun sentApdus(reports: List<ByteArray>): List<ByteArray> = buildList {
        var start = 0
        for (index in 1 until reports.size) {
            if (reports[index].uint16(4) == 0) {
                add(sentApdu(reports.subList(start, index)))
                start = index
            }
        }
        if (start < reports.size) add(sentApdu(reports.subList(start, reports.size)))
    }

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
        val blindSigningOff = "020200009000".hexToByteArray()
        val blindSigningOn = "030200009000".hexToByteArray()
        val typedHashes = Eip712Hashes(Digest.of("12".repeat(32)), Digest.of("34".repeat(32)))

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

        fun signatureReply(s: String, v: Int = 1): ByteArray =
            byteArrayOf(v.toByte()) + "$R${s}9000".hexToByteArray()
    }
}
