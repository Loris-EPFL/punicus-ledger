package eth.serotine.domain.model

import eth.serotine.domain.model.TraceReaderTest.Companion.address
import eth.serotine.domain.model.TraceReaderTest.Companion.approval
import eth.serotine.domain.model.TraceReaderTest.Companion.transfer
import kotlin.test.Test
import kotlin.test.assertEquals

/** [TraceDisagreements]: a description held against its verified trace (docs/clear-signing.md, *Phase B*). */
class TraceDisagreementsTest {

    private val user = address("aa")
    private val router = address("01")
    private val pool = address("02")
    private val sold = address("03")
    private val bought = address("04")
    private val thief = address("05")
    private val fee = address("06")
    private val weth = address("07")

    private fun swap(recipient: Address?, grantsApproval: Boolean = false, tokens: List<Address> = listOf(sold, bought)) =
        ClearSignResult.ClearSigned(
            owner = "Router DAO", attestedBy = emptyList(), intent = "Swap", interpolatedIntent = null,
            fields = tokens.map { ClearSignedField.Value("Amount", "1", "1", token = it) } +
                listOfNotNull(recipient?.let { ClearSignedField.Value("Beneficiary", it.hex, it.hex, recipient = it) }),
            otherParameters = emptyList(),
            grantsApproval = grantsApproval,
        )

    /** The user calls the router, which moves [logs] through the pool (whose code runs: it opens a frame). */
    private fun trace(vararg logs: TraceLog, error: String? = null): TraceReading {
        val poolFrame = TraceFrame(
            TraceFrame.Type.CALL, router, pool, UInt256.ZERO,
            calls = listOf(TraceFrame(TraceFrame.Type.CALL, pool, bought, UInt256.ZERO, logs = logs.toList())),
        )
        val root = TraceFrame(TraceFrame.Type.CALL, user, router, UInt256.ZERO, error = error, calls = listOf(poolFrame))
        return TraceReader.read(user, root, weth, TypedDataMeaning.PERMIT2)
    }

    private val honest = arrayOf(transfer(sold, user, pool, 10), transfer(bought, pool, user, 5))

    @Test
    fun aSwapThatDoesWhatItSaysAgrees() {
        assertEquals(emptyList(), TraceDisagreements.check(user, trace(*honest), swap(user)))
    }

    @Test
    fun aTokenTheDescriptionDoesNotNameIsADisagreement() {
        val extra = address("08")
        val found = TraceDisagreements.check(user, trace(*honest, transfer(extra, user, pool, 1)), swap(user))
        assertEquals(listOf(TraceDisagreement.TokenNotNamed(extra)), found)
    }

    @Test
    fun theUsersTokensEndingElsewhereThanTheRecipientIsADisagreement() {
        val stolen = trace(transfer(sold, user, pool, 10), transfer(bought, pool, thief, 5))
        assertEquals(
            listOf(TraceDisagreement.OtherRecipient(TracedAsset.Fungible(bought), thief)),
            TraceDisagreements.check(user, stolen, swap(user)),
        )
        // The thief named as the recipient is what the description says: no disagreement.
        assertEquals(emptyList(), TraceDisagreements.check(user, stolen, swap(thief)))
    }

    @Test
    fun aRecipientHiddenBehindACallToItIsStillOne() {
        // A call to an address with no code (it opens no frame, logs nothing) does not make it a pass-through.
        val poolFrame = TraceFrame(
            TraceFrame.Type.CALL, router, pool, UInt256.ZERO,
            calls = listOf(
                TraceFrame(TraceFrame.Type.CALL, pool, bought, UInt256.ZERO, logs = listOf(transfer(bought, pool, thief, 5))),
                TraceFrame(TraceFrame.Type.CALL, pool, thief, UInt256.ZERO),
            ),
        )
        val root = TraceFrame(TraceFrame.Type.CALL, user, router, UInt256.ZERO, calls = listOf(poolFrame))
        val reading = TraceReader.read(user, root, weth, TypedDataMeaning.PERMIT2)
        assertEquals(
            listOf(TraceDisagreement.OtherRecipient(TracedAsset.Fungible(bought), thief)),
            TraceDisagreements.check(user, reading, swap(user)),
        )
    }

    @Test
    fun aDescriptionWithoutARecipientIsNotHeldToOne() {
        val stolen = trace(transfer(sold, user, pool, 10), transfer(bought, pool, thief, 5))
        assertEquals(emptyList(), TraceDisagreements.check(user, stolen, swap(recipient = null)))
    }

    @Test
    fun aFeeTakenToAThirdAddressIsShownAsOne() {
        val withFee = trace(*honest, transfer(bought, pool, fee, 1))
        assertEquals(
            listOf(TraceDisagreement.OtherRecipient(TracedAsset.Fungible(bought), fee)),
            TraceDisagreements.check(user, withFee, swap(user)),
        )
    }

    @Test
    fun aBurnIsNoRecipient() {
        assertEquals(emptyList(), TraceDisagreements.check(user, trace(*honest, transfer(bought, pool, Address.ZERO, 1)), swap(user)))
    }

    @Test
    fun aRightTheCallIsNotFlaggedForIsADisagreement() {
        val grant = approval(sold, user, thief, 1_000)
        val reading = trace(*honest, grant)
        assertEquals(
            listOf(TraceDisagreement.RightNotFlagged(TracedRight.Allowance(sold, thief, UInt256.of(1_000)))),
            TraceDisagreements.check(user, reading, swap(user)),
        )
        assertEquals(emptyList(), TraceDisagreements.check(user, reading, swap(user, grantsApproval = true)))
    }

    @Test
    fun aNestedDescriptionsTokensAndRecipientsCount() {
        val nested = ClearSignedField.Nested(
            label = "Then", callee = router, data = "0x", grantsApproval = false,
            result = swap(user, false, listOf(bought)),
        )
        val outer = swap(recipient = null, tokens = listOf(sold)).let { it.copy(fields = it.fields + nested) }
        val stolen = trace(transfer(sold, user, pool, 10), transfer(bought, pool, thief, 5))
        assertEquals(
            listOf(TraceDisagreement.OtherRecipient(TracedAsset.Fungible(bought), thief)),
            TraceDisagreements.check(user, stolen, outer),
        )
    }

    @Test
    fun aCallNotDescribedOrRevertedHasNone() {
        val extra = address("08")
        val notCovered = ClearSignResult.NotCovered(NotCoveredReason.NoDescriptor)
        assertEquals(emptyList(), TraceDisagreements.check(user, trace(transfer(extra, user, thief, 1)), notCovered))
        assertEquals(emptyList(), TraceDisagreements.check(user, trace(transfer(extra, user, thief, 1), error = "reverted"), swap(user)))
    }
}
