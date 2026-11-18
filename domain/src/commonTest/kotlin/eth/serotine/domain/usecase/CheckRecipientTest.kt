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

class CheckRecipientTest {
    private val check = CheckRecipient()
    private val bob = Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
    private val lookalike = Address.of("0x5aae" + "1".repeat(32) + "eaed")
    private val stranger = Address.of("0x1234567890123456789012345678901234567890")
    private val me = Address.of("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")
    private val usdc = Address.of("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
    private val fake = Address.of("0x00000000000000000000000000000000000fa4e0")

    private fun run(to: Address, incoming: List<TokenTransfer> = emptyList()) =
        check.check(to, setOf(bob), setOf(me), incoming, setOf(usdc))

    @Test
    fun sameFirstAndLastFourButDifferentMiddleIsALookalike() {
        assertTrue(AddressLookalikes.lookAlike(bob, lookalike))
        assertEquals(RecipientCheck.Lookalike(lookalike, bob), run(lookalike))
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
        val near = Address.of("0x5aa" + "1".repeat(33) + "eaed")
        assertFalse(AddressLookalikes.lookAlike(bob, near))
        val shortEnd = Address.of("0x5aae" + "1".repeat(33) + "aed")
        assertFalse(AddressLookalikes.lookAlike(bob, shortEnd))
    }

    @Test
    fun anAccountOfTheWalletIsAlsoGuardedAgainstLookalikes() {
        val ofMe = Address.of("0xabcd" + "2".repeat(32) + "abcd")
        assertEquals(RecipientCheck.Lookalike(ofMe, me), run(ofMe))
    }

    @Test
    fun aSenderOfZeroValueOrAnUnlistedTokenOrDustIsBlocked() {
        fun fungible(token: Address, amount: Long) =
            TokenTransfer.Fungible(token, stranger, me, UInt256.of(amount))
        assertEquals(
            RecipientCheck.PlantedSender(stranger, PlantKind.ZERO_VALUE),
            run(stranger, listOf(fungible(usdc, 0))),
        )
        assertEquals(
            RecipientCheck.PlantedSender(stranger, PlantKind.UNLISTED_TOKEN),
            run(stranger, listOf(fungible(fake, 5_000_000))),
        )
        assertEquals(
            RecipientCheck.PlantedSender(stranger, PlantKind.DUST),
            run(stranger, listOf(fungible(TokenTransfers.ETH, 1))),
        )
        // A real, listed, non-trivial amount is no plant.
        assertEquals(RecipientCheck.NewRecipient(), run(stranger, listOf(fungible(usdc, 5_000_000))))
    }

    @Test
    fun splitSaysHowManyDigitsAgree() {
        val split = AddressLookalikes.split(bob, lookalike)
        assertEquals(4, split.prefix)
        assertEquals(4, split.suffix)
    }
}
