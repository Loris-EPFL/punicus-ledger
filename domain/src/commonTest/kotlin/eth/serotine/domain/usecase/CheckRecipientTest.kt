package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressLookalikes
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TokenTransfers
import eth.serotine.domain.model.UInt256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [CheckRecipient] and [AddressLookalikes], on plain values. */
class CheckRecipientTest {
    private val check = CheckRecipient()
    private val bob = Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
    private val lookalike = Address.of("0x5aae" + "1".repeat(32) + "eaed")
    private val stranger = Address.of("0x1234567890123456789012345678901234567890")
    private val me = Address.of("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")
    private val usdc = Address.of("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
    private val fake = Address.of("0x00000000000000000000000000000000000fa4e0")

    private fun run(to: Address, transfers: List<TokenTransfer> = emptyList(), sentTo: Set<Address>? = setOf(bob)) =
        check.check(me, to, sentTo, setOf(me), transfers, setOf(usdc))

    private fun fungible(token: Address, from: Address, to: Address, amount: Long) =
        TokenTransfer.Fungible(token, from, to, UInt256.of(amount))

    @Test
    fun sameFirstAndLastFourButDifferentMiddleIsALookalike() {
        assertTrue(AddressLookalikes.lookAlike(bob, lookalike))
        assertEquals(RecipientCheck.Lookalike(lookalike, bob), run(lookalike))
        assertFalse(RecipientCheck.Lookalike(lookalike, bob).overridable)
    }

    @Test
    fun theKnownAddressItselfAndAnUnlikeOneAreNot() {
        assertFalse(AddressLookalikes.lookAlike(bob, bob))
        assertEquals(RecipientCheck.Familiar, run(bob))
        assertEquals(RecipientCheck.Familiar, run(me))
        assertEquals(RecipientCheck.NewRecipient(), run(stranger))
    }

    @Test
    fun threeSharedDigitsAreNotEnough() {
        assertFalse(AddressLookalikes.lookAlike(bob, Address.of("0x5aa" + "1".repeat(33) + "eaed")))
        assertFalse(AddressLookalikes.lookAlike(bob, Address.of("0x5aae" + "1".repeat(33) + "aed")))
    }

    @Test
    fun anAccountOfTheWalletIsAlsoGuardedAgainstLookalikes() {
        val ofMe = Address.of("0xabcd" + "2".repeat(32) + "abcd")
        assertEquals(RecipientCheck.Lookalike(ofMe, me), run(ofMe))
    }

    @Test
    fun aSenderOfZeroValueOrAnUnlistedTokenOrDustIsAPlantedSender() {
        assertEquals(
            RecipientCheck.PlantedSender(stranger, PlantKind.ZERO_VALUE),
            run(stranger, listOf(fungible(usdc, stranger, me, 0))),
        )
        assertEquals(
            RecipientCheck.PlantedSender(stranger, PlantKind.UNLISTED_TOKEN),
            run(stranger, listOf(fungible(fake, stranger, me, 5_000_000))),
        )
        assertEquals(
            RecipientCheck.PlantedSender(stranger, PlantKind.DUST),
            run(stranger, listOf(fungible(TokenTransfers.ETH, stranger, me, 1))),
        )
        assertTrue(RecipientCheck.PlantedSender(stranger, PlantKind.DUST).overridable)
        // A real, listed, non-trivial amount is no plant.
        assertEquals(RecipientCheck.NewRecipient(), run(stranger, listOf(fungible(usdc, stranger, me, 5_000_000))))
    }

    @Test
    fun aZeroTransferFromTheAccountToTheRecipientIsAPlantToo() {
        // transferFrom(account, lookAlike, 0) needs no allowance: logged as from the account.
        assertEquals(
            RecipientCheck.PlantedSender(stranger, PlantKind.ZERO_VALUE),
            run(stranger, listOf(fungible(usdc, me, stranger, 0))),
        )
        // An unlisted contract's claim that the account sent the recipient something.
        assertEquals(
            RecipientCheck.PlantedSender(stranger, PlantKind.UNLISTED_TOKEN),
            run(stranger, listOf(fungible(fake, me, stranger, 40_000_000))),
        )
    }

    @Test
    fun anNftSenderIsNoPlantedSender() {
        val ens = Address.of("0x57f1887a8bf19b14fc0df6fd9b2acc9af147ea85")
        val name = TokenTransfer.Nft(ens, stranger, me, UInt256.of(7))
        assertEquals(RecipientCheck.NewRecipient(), run(stranger, listOf(name)))
    }

    @Test
    fun anUnreadableHistoryOfWhatWasSentBlocksUnlessTheRecipientIsTheWalletsOwn() {
        assertEquals(RecipientCheck.SentToUnreadable, run(stranger, sentTo = null))
        assertTrue(RecipientCheck.SentToUnreadable.overridable)
        assertEquals(RecipientCheck.Familiar, run(me, sentTo = null))
        // A look-alike of the wallet's own account is still found.
        val ofMe = Address.of("0xabcd" + "2".repeat(32) + "abcd")
        assertEquals(RecipientCheck.Lookalike(ofMe, me), run(ofMe, sentTo = null))
    }

    @Test
    fun splitSaysHowManyDigitsAgree() {
        val split = AddressLookalikes.split(bob, lookalike)
        assertEquals(4, split.prefix)
        assertEquals(4, split.suffix)
    }
}
