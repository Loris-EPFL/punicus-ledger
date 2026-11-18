package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A member of a Gnosis Pay account's Delay added or removed (owner ruling, 2026-11-18): the Safe's
 * own call of its Delay, its calldata against the selectors Zodiac's `Modifier` has (`cast sig`),
 * the member before the one removed, and every refusal of the ruling.
 */
class DelayMemberChangeTest {

    private val safe = Address.of("0x" + "5a".repeat(20))
    private val delay = Address.of("0x" + "de".repeat(20))
    private val roles = Address.of("0x" + "50".repeat(20))
    private val first = Address.of("0x91f8" + "00".repeat(16) + "89af")
    private val second = Address.of("0x0cd0" + "00".repeat(16) + "39fb")
    private val newcomer = Address.of("0x30953638c67022f6d0f72a5ad86e1241c611cf45")
    private val members = listOf(first, second)

    private fun add(member: Address, current: List<Address> = members) =
        DelayMemberChange.add(safe, delay, listOf(roles, delay), current, member)

    private fun remove(member: Address, current: List<Address> = members) =
        DelayMemberChange.remove(safe, delay, current, member)

    private fun DelayMemberChangePlan.change() = (this as DelayMemberChangePlan.Planned).change

    @Test
    fun theSelectorsAreZodiacsModifiers() {
        // cast sig "enableModule(address)" / "disableModule(address,address)"
        assertEquals("610b5925", DelayMemberChange.ENABLE_MODULE.toHex())
        assertEquals("e009cfde", DelayMemberChange.DISABLE_MODULE.toHex())
    }

    @Test
    fun anAdditionIsTheSafesEnableModuleOfItsOwnDelay() {
        val change = add(newcomer).change()

        assertEquals(delay, change.to)
        assertTrue(change.value.isZero)
        assertEquals("610b5925" + "0".repeat(24) + "30953638c67022f6d0f72a5ad86e1241c611cf45", change.data.toHex())
        assertNull(change.previous)
        // The Delay's hash of it: keccak256(abi.encodePacked(to, value, data, uint8(0))).
        assertEquals(
            Digest.of(keccak256(delay.toBytes() + UInt256.ZERO.toBytes32() + change.data + byteArrayOf(0)).toHex()),
            change.hash,
        )
        // Queued like a transfer: execTransactionFromModule(delay, 0, data, CALL) sent to the Delay.
        assertEquals(
            "468721a7" + "0".repeat(24) + "de".repeat(20) + "0".repeat(64) + "0".repeat(62) + "80" + "0".repeat(64) +
                "0".repeat(62) + "24" + change.data.toHex() + "0".repeat(56),
            change.queueData().toHex(),
        )
        val (to, data) = change.queueCall(first, emptyList())
        assertEquals(delay, to)
        assertEquals(change.queueData().toHex(), data.toHex())
    }

    @Test
    fun aRemovalNamesTheMemberBeforeItTheSentinelForTheFirst() {
        val ofFirst = remove(first).change()
        assertEquals(DelayMemberChange.SENTINEL, ofFirst.previous)
        assertEquals(
            "e009cfde" + "0".repeat(63) + "1" + "0".repeat(24) + first.hex.removePrefix("0x"),
            ofFirst.data.toHex(),
        )
        val ofSecond = remove(second).change()
        assertEquals(first, ofSecond.previous)
        assertEquals(
            "e009cfde" + "0".repeat(24) + first.hex.removePrefix("0x") + "0".repeat(24) + second.hex.removePrefix("0x"),
            ofSecond.data.toHex(),
        )
        assertNotEquals(ofFirst.hash, ofSecond.hash)
    }

    @Test
    fun anAdditionIsRefusedForWhatTheRulingRefuses() {
        assertEquals(DelayMemberChangePlan.NoKeyAddress, add(Address.ZERO))
        assertEquals(DelayMemberChangePlan.NoKeyAddress, add(DelayMemberChange.SENTINEL))
        // The placeholder owner, and every address up to 0x…ff.
        assertEquals(DelayMemberChangePlan.NoKeyAddress, add(Address.of("0x" + "0".repeat(39) + "2")))
        assertEquals(DelayMemberChangePlan.NoKeyAddress, add(Address.of("0x" + "0".repeat(38) + "ff")))
        assertTrue(add(Address.of("0x" + "0".repeat(37) + "100")) is DelayMemberChangePlan.Planned)
        assertEquals(DelayMemberChangePlan.TheAccount, add(safe))
        assertEquals(DelayMemberChangePlan.TheAccount, add(delay))
        assertEquals(DelayMemberChangePlan.TheAccount, add(roles))
        assertEquals(DelayMemberChangePlan.AlreadyMember, add(second))
    }

    @Test
    fun theLastMemberIsNeverRemoved() {
        assertEquals(DelayMemberChangePlan.LastMember, remove(first, current = listOf(first)))
        assertEquals(DelayMemberChangePlan.NotAMember, remove(newcomer))
    }

    @Test
    fun aChangeIsCheckedAgainAgainstTheMembersAsReadNow() {
        val removal = remove(second).change()
        assertEquals(DelayMemberChangePlan.Planned(removal), removal.against(members))
        // A member added since goes first in the Delay's list: the member before is another now.
        val moved = removal.against(listOf(first, newcomer, second)).change()
        assertEquals(newcomer, moved.previous)
        assertNotEquals(removal, moved)
        assertEquals(DelayMemberChangePlan.LastMember, removal.against(listOf(second)))
        assertEquals(DelayMemberChangePlan.AlreadyMember, add(newcomer).change().against(members + newcomer))
    }

    @Test
    fun aRecordedChangeIsRestoredOnlyWhenItCouldHaveBeenPlanned() {
        val removal = remove(second).change()
        assertEquals(removal, DelayMemberChange.of(safe, delay, DelayMemberChange.Kind.REMOVE, second, first))
        val addition = DelayMemberChange.of(safe, delay, DelayMemberChange.Kind.ADD, newcomer, null)
        assertEquals(add(newcomer).change(), addition)
        assertNull(DelayMemberChange.of(safe, delay, DelayMemberChange.Kind.REMOVE, second, null))
        assertNull(DelayMemberChange.of(safe, delay, DelayMemberChange.Kind.ADD, newcomer, first))
        assertNull(DelayMemberChange.of(safe, delay, DelayMemberChange.Kind.ADD, DelayMemberChange.SENTINEL, null))
        assertNull(DelayMemberChange.of(safe, delay, DelayMemberChange.Kind.ADD, safe, null))
    }
}
