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

    private fun check(reading: TraceReading, description: ClearSignResult) =
        TraceDisagreements.check(user, reading, TraceClaims.of(description))

    private val honest = arrayOf(transfer(sold, user, pool, 10), transfer(bought, pool, user, 5))

    @Test
    fun aSwapThatDoesWhatItSaysAgrees() {
        assertEquals(emptyList(), check(trace(*honest), swap(user)))
    }

    @Test
    fun aTokenTheDescriptionDoesNotNameIsADisagreement() {
        val extra = address("08")
        val found = check(trace(*honest, transfer(extra, user, pool, 1)), swap(user))
        assertEquals(listOf(TraceDisagreement.TokenNotNamed(extra)), found)
    }

    @Test
    fun theUsersTokensEndingElsewhereThanTheRecipientIsADisagreement() {
        val stolen = trace(transfer(sold, user, pool, 10), transfer(bought, pool, thief, 5))
        // The pool pays nothing on to the user: what it was paid is no swap's either.
        assertEquals(
            listOf(
                TraceDisagreement.OtherRecipient(TracedAsset.Fungible(sold), pool),
                TraceDisagreement.OtherRecipient(TracedAsset.Fungible(bought), thief),
            ),
            check(stolen, swap(user)),
        )
        // The thief named as the recipient is what the description says: no disagreement.
        assertEquals(emptyList(), check(stolen, swap(thief)))
    }

    @Test
    fun aContractThatRunsCodeIsNoRecipientForThat() {
        // A thief's contract that is called and logs, but pays nothing on to the user, is still where tokens end.
        val poolFrame = TraceFrame(
            TraceFrame.Type.CALL, router, pool, UInt256.ZERO,
            calls = listOf(
                TraceFrame(TraceFrame.Type.CALL, pool, bought, UInt256.ZERO, logs = listOf(transfer(bought, pool, thief, 5))),
                TraceFrame(TraceFrame.Type.CALL, pool, thief, UInt256.ZERO, logs = listOf(TraceLog(thief, listOf("00".repeat(32)), ""))),
            ),
        )
        val root = TraceFrame(TraceFrame.Type.CALL, user, router, UInt256.ZERO, calls = listOf(poolFrame))
        val reading = TraceReader.read(user, root, weth, TypedDataMeaning.PERMIT2)
        assertEquals(
            listOf(TraceDisagreement.OtherRecipient(TracedAsset.Fungible(bought), thief)),
            check(reading, swap(user)),
        )
    }

    @Test
    fun aDescriptionWithoutARecipientIsHeldToTheAccount() {
        val stolen = trace(transfer(sold, user, pool, 10), transfer(bought, pool, thief, 5))
        assertEquals(TraceDisagreement.OtherRecipient(TracedAsset.Fungible(bought), thief), check(stolen, swap(recipient = null)).last())
        assertEquals(emptyList(), check(trace(*honest), swap(recipient = null)))
    }

    @Test
    fun aJunkTokensLoggedMoveMakesNoThiefAPayer() {
        // The input goes to a thief, and a token anyone can deploy logs a move from the thief to the user.
        val junk = address("0e")
        val stolen = trace(transfer(sold, user, thief, 10), transfer(junk, thief, user, 1))
        assertEquals(
            TraceDisagreement.OtherRecipient(TracedAsset.Fungible(sold), thief),
            check(stolen, swap(user)).first { it is TraceDisagreement.OtherRecipient },
        )
    }

    @Test
    fun aFeeTakenToAThirdAddressIsShownAsOne() {
        val withFee = trace(*honest, transfer(bought, pool, fee, 1))
        assertEquals(
            listOf(TraceDisagreement.OtherRecipient(TracedAsset.Fungible(bought), fee)),
            check(withFee, swap(user)),
        )
    }

    @Test
    fun aMultiHopSwapsPoolsPayOnThroughEachOther() {
        val pool2 = address("09")
        val middle = address("0a")
        val hops = trace(
            transfer(sold, user, pool, 10), transfer(middle, pool, pool2, 7), transfer(bought, pool2, user, 5),
        )
        assertEquals(emptyList(), check(hops, swap(user)))
    }

    @Test
    fun theNetworksCurrencyIsHeldToTheRecipientToo() {
        // Tokens for ETH, the ETH sent to a thief: the description shows an amount of the currency.
        val ethOut = ClearSignedField.Value("Minimum", "1 ETH", "1", nativeCurrency = true)
        val described = swap(user, tokens = listOf(sold)).let { it.copy(fields = it.fields + ethOut) }
        val poolFrame = TraceFrame(
            TraceFrame.Type.CALL, router, pool, UInt256.ZERO,
            logs = listOf(transfer(sold, user, pool, 10)),
            calls = listOf(TraceFrame(TraceFrame.Type.CALL, pool, thief, UInt256.of(3))),
        )
        val root = TraceFrame(TraceFrame.Type.CALL, user, router, UInt256.ZERO, calls = listOf(poolFrame))
        val found = check(TraceReader.read(user, root, weth, TypedDataMeaning.PERMIT2), described)
        assertEquals(TraceDisagreement.OtherRecipient(TracedAsset.Currency, thief), found.last())
    }

    @Test
    fun currencyPaidForASwapEndsWithItsCounterparty() {
        // ETH for a token: the router wraps the ETH, the pool pays the token to the user.
        val wrap = TraceFrame(
            TraceFrame.Type.CALL, router, weth, UInt256.of(4),
            logs = listOf(TraceLog(weth, listOf(TraceReaderTest.DEPOSIT, TraceReaderTest.word(router)), TraceReaderTest.uint(4))),
        )
        val poolFrame = TraceFrame(
            TraceFrame.Type.CALL, router, pool, UInt256.ZERO,
            logs = listOf(transfer(weth, router, pool, 4), transfer(bought, pool, user, 5)),
        )
        val root = TraceFrame(TraceFrame.Type.CALL, user, router, UInt256.of(4), calls = listOf(wrap, poolFrame))
        val reading = TraceReader.read(user, root, weth, TypedDataMeaning.PERMIT2)
        assertEquals(emptyList(), check(reading, swap(user, tokens = listOf(weth, bought))))
    }

    @Test
    fun aLendingPoolsSupplyAndWithdrawalAgree() {
        val aToken = address("0b")
        val lendingPool = address("0c")
        val described = ClearSignResult.ClearSigned(
            owner = "Aave", attestedBy = emptyList(), intent = "Supply", interpolatedIntent = null,
            fields = listOf(
                ClearSignedField.Value("Amount", "10", "10", token = sold),
                ClearSignedField.Value("For", user.hex, user.hex, recipient = user),
            ),
            otherParameters = emptyList(), grantsApproval = false,
        )
        fun pool(vararg logs: TraceLog): TraceReading {
            val frame = TraceFrame(TraceFrame.Type.CALL, lendingPool, aToken, UInt256.ZERO, logs = logs.toList())
            return TraceReader.read(user, TraceFrame(TraceFrame.Type.CALL, user, lendingPool, UInt256.ZERO, calls = listOf(frame)), weth, TypedDataMeaning.PERMIT2)
        }
        // Supply: the underlying to the aToken, which mints its shares to the user.
        assertEquals(emptyList(), check(pool(transfer(sold, user, aToken, 10), transfer(aToken, Address.ZERO, user, 10)), described))
        // Withdraw: the shares burnt, the underlying back; the aToken is named by no tokenAmount.
        assertEquals(emptyList(), check(pool(transfer(aToken, user, Address.ZERO, 10), transfer(sold, aToken, user, 10)), described))
        // A burn with nothing in return is a token sent.
        assertEquals(listOf(TraceDisagreement.TokenNotNamed(aToken)), check(pool(transfer(aToken, user, Address.ZERO, 10)), described))
    }

    @Test
    fun aHandDecodedTransferIsHeldToItsRecipient() {
        val claims = TraceClaims.ofMeaning(DappCallMeaning.TokenTransfer(sold, other(), UInt256.of(10)), sold)
        val root = TraceFrame(TraceFrame.Type.CALL, user, sold, UInt256.ZERO, logs = listOf(transfer(sold, user, thief, 10)))
        val reading = TraceReader.read(user, root, weth, TypedDataMeaning.PERMIT2)
        assertEquals(listOf(TraceDisagreement.OtherRecipient(TracedAsset.Fungible(sold), thief)), TraceDisagreements.check(user, reading, claims))
        assertEquals(emptyList(), TraceDisagreements.check(user, reading, TraceClaims.ofMeaning(DappCallMeaning.TokenTransfer(sold, thief, UInt256.of(10)), sold)))
        assertEquals(null, TraceClaims.ofMeaning(DappCallMeaning.NotDecoded("0x12345678"), sold))
    }

    private fun other() = address("0d")

    @Test
    fun aBurnIsNoRecipient() {
        assertEquals(emptyList(), check(trace(*honest, transfer(bought, pool, Address.ZERO, 1)), swap(user)))
    }

    @Test
    fun aRightTheCallIsNotFlaggedForIsADisagreement() {
        val grant = approval(sold, user, thief, 1_000)
        val reading = trace(*honest, grant)
        assertEquals(
            listOf(TraceDisagreement.RightNotFlagged(TracedRight.Allowance(sold, thief, UInt256.of(1_000)))),
            check(reading, swap(user)),
        )
        // Flagged as granting a right, but not this one: the thief is no address the call shows.
        assertEquals(1, check(reading, swap(user, grantsApproval = true)).size)
        assertEquals(emptyList(), check(reading, swap(thief, grantsApproval = true)))
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
            TraceDisagreement.OtherRecipient(TracedAsset.Fungible(bought), thief),
            check(stolen, outer).last(),
        )
    }

    @Test
    fun aCallNotDescribedOrRevertedHasNone() {
        val extra = address("08")
        val notCovered = ClearSignResult.NotCovered(NotCoveredReason.NoDescriptor)
        assertEquals(emptyList(), check(trace(transfer(extra, user, thief, 1)), notCovered))
        assertEquals(emptyList(), check(trace(transfer(extra, user, thief, 1), error = "reverted"), swap(user)))
    }
}
