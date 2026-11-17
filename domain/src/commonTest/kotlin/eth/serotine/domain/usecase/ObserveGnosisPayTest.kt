package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.DelayFinding
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.DelayModule
import eth.serotine.domain.repository.DelayModules
import eth.serotine.domain.repository.DelayQueueReading
import eth.serotine.domain.repository.DelayReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class ObserveGnosisPayTest {

    private val safe = Address.of("0x" + "5a".repeat(20))
    private val roles = Address.of("0x" + "70".repeat(20))
    private val delay = Address.of("0x" + "de".repeat(20))
    private val member = Address.of("0x" + "11".repeat(20))
    private val sentinelOwner = Address.of("0x" + "0".repeat(39) + "2")
    private val gnosisPay = AccountId(Network.GNOSIS, safe)

    /** A Gnosis Pay Safe as account-kit sets one up: the sentinel `0x…02` its one owner, Roles and Delay on. */
    private fun safeWith(modules: List<Address>) = KindReading.Verified(
        AddressKind.Safe(SafeState("1.3.0", l2 = false, listOf(sentinelOwner), 1, 7, modules, guard = null)),
        40,
    )

    private val kind = MutableStateFlow<KindReading>(safeWith(listOf(roles, delay)))

    /** Every other address's kind: an account with no code, unless a test says otherwise. */
    private val others = mutableMapOf<Address, MutableStateFlow<KindReading>>()
    private val kindsAsked = mutableListOf<Address>()
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> {
            if (address == safe) return kind
            kindsAsked += address
            return others.getOrPut(address) { MutableStateFlow(KindReading.Verified(AddressKind.Eoa, 40)) }
        }
    }

    /** A Safe of [threshold] owned by [owners], at block 40. */
    private fun safeOf(owners: List<Address>, threshold: Int = 1) = KindReading.Verified(
        AddressKind.Safe(SafeState("1.4.1", l2 = true, owners, threshold, 80, emptyList(), guard = null)),
        40,
    )

    private fun delayOf(generation: DelayGeneration = DelayGeneration.CURRENT) =
        DelayModule(delay, generation, 90_000, 111_600, txNonce = 2, queueNonce = 3, members = listOf(member))

    private val readings = mutableMapOf<Address, MutableStateFlow<DelayReading>>(
        roles to MutableStateFlow(DelayReading.Verified(DelayFinding.NotDelay, 41)),
        delay to MutableStateFlow(DelayReading.Verified(DelayFinding.Wired(delayOf()), 41)),
    )
    private val asked = mutableListOf<Triple<Network, Address, Address>>()
    private val delays = object : DelayModules {
        override fun observeDelay(network: Network, safe: Address, module: Address): Flow<DelayReading> {
            asked += Triple(network, safe, module)
            return readings.getValue(module)
        }

        override suspend fun queue(network: Network, safe: Address, delay: Address, hash: Digest): DelayQueueReading =
            DelayQueueReading.Unavailable(ReadUnavailable.UNPROVEN)
    }

    private fun eoa(network: Network, custody: AccountCustody = AccountCustody.InKeyStore, address: Address = member) =
        Account(network, address, AccountKind.EOA, "Card owner", custody)

    private val card = Account(Network.GNOSIS, safe, AccountKind.SAFE, "Card", AccountCustody.Watched)

    private val wallet = ListAccountRepository(card)

    private fun observe(vararg accounts: Account): ObserveGnosisPay {
        wallet.accounts.value = listOf(card) + accounts
        return ObserveGnosisPay(kinds, delays, wallet)
    }

    /**
     * The first reading past [GnosisPayReading.Checking], with who controls the Delay read: the
     * modules' readers deliver after the Safe's state, and the control's after the Delay's.
     */
    private suspend fun Flow<GnosisPayReading>.settled() = first {
        it != GnosisPayReading.Checking && (it as? GnosisPayReading.Found)?.control?.complete != false
    }

    @Test
    fun aSafesOwnDelayWithAMemberHeldHereCanSend() = runTest {
        val held = eoa(Network.GNOSIS)
        val reading = observe(held)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(delayOf(), reading.delay)
        assertEquals(41, reading.blockNumber)
        assertEquals(listOf(HeldController(held, via = emptyList())), reading.controllersHeld)
        assertEquals(GnosisPaySending.AVAILABLE, reading.sending)
        // Each module is asked about with the Safe's network and address, which the adapter applies.
        assertEquals(
            setOf(Triple(Network.GNOSIS, safe, roles), Triple(Network.GNOSIS, safe, delay)),
            asked.toSet(),
        )
        // A key held here is a key: its kind is never read.
        assertEquals(emptyList(), kindsAsked)
    }

    @Test
    fun aMemberWatchedOrNotInTheWalletSendsNothing() = runTest {
        val watched =
            observe(eoa(Network.GNOSIS, AccountCustody.Watched))(gnosisPay).settled() as GnosisPayReading.Found
        assertEquals(GnosisPaySending.NO_MEMBER_KEY, watched.sending)

        val absent = observe()(gnosisPay).settled() as GnosisPayReading.Found
        assertEquals(GnosisPaySending.NO_MEMBER_KEY, absent.sending)
    }

    @Test
    fun aMemberHeldOnlyOnAnotherNetworkIsNamedOnceAndSaysSo() = runTest {
        val onEthereum = eoa(Network.ETHEREUM)
        val reading = observe(onEthereum, eoa(Network.SEPOLIA))(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(emptyList(), reading.controllersHeld)
        assertEquals(listOf(onEthereum), reading.keysHeldElsewhere)
        assertEquals(GnosisPaySending.MEMBER_ON_ANOTHER_NETWORK, reading.sending)
    }

    @Test
    fun aMemberWatchedHereWhoseKeyIsHeldElsewhereSaysSo() = runTest {
        val watchedHere = eoa(Network.GNOSIS, AccountCustody.Watched)
        val reading = observe(watchedHere, eoa(Network.ETHEREUM))(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(listOf(watchedHere), reading.keysWatchedHere)
        assertEquals(GnosisPaySending.MEMBER_WATCHED_HERE, reading.sending)
    }

    @Test
    fun aKeyThatIsNoMembersSendsNothing() = runTest {
        val stranger = eoa(Network.GNOSIS, address = Address.of("0x" + "22".repeat(20)))
        val reading = observe(stranger)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(GnosisPaySending.NO_MEMBER_KEY, reading.sending)
    }

    @Test
    fun theRetiredMastercopyIsShownAndNeverSentThrough() = runTest {
        readings.getValue(delay).value =
            DelayReading.Verified(DelayFinding.Wired(delayOf(DelayGeneration.RETIRED)), 41)
        val reading = observe(eoa(Network.GNOSIS))(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(GnosisPaySending.RETIRED_MODULE, reading.sending)
    }

    @Test
    fun theCurrentMastercopyIsChosenOverTheRetiredOne() = runTest {
        val retired = Address.of("0x" + "dd".repeat(20))
        readings[retired] = MutableStateFlow(
            DelayReading.Verified(DelayFinding.Wired(delayOf(DelayGeneration.RETIRED).copy(address = retired)), 41),
        )
        kind.value = safeWith(listOf(retired, roles, delay))

        val reading = observe(eoa(Network.GNOSIS))(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(delay, reading.delay.address)
        assertEquals(GnosisPaySending.AVAILABLE, reading.sending)
    }

    @Test
    fun aRetiredDelayWaitsWhileAModuleThatCouldBeACurrentOneIsUnread() = runTest {
        val retired = Address.of("0x" + "dd".repeat(20))
        readings[retired] = MutableStateFlow(
            DelayReading.Verified(DelayFinding.Wired(delayOf(DelayGeneration.RETIRED).copy(address = retired)), 41),
        )
        readings.getValue(delay).value = DelayReading.Syncing
        kind.value = safeWith(listOf(retired, delay))

        val observe = observe(eoa(Network.GNOSIS))
        assertEquals(GnosisPayReading.Checking, observe(gnosisPay).first())

        readings.getValue(delay).value = DelayReading.Verified(DelayFinding.NotDelay, 41)
        val reading = observe(gnosisPay).settled() as GnosisPayReading.Found
        assertEquals(retired, reading.delay.address)
        assertEquals(GnosisPaySending.RETIRED_MODULE, reading.sending)
    }

    @Test
    fun aListThatCannotBeReadSaysSoRatherThanNoKey() = runTest {
        val observe = observe(eoa(Network.GNOSIS, AccountCustody.Watched))
        wallet.unreadable = true

        val reading = observe(gnosisPay).settled() as GnosisPayReading.Found
        assertEquals(GnosisPaySending.ACCOUNTS_UNREADABLE, reading.sending)
    }

    @Test
    fun aSafeOnAnotherNetworkIsNeverRead() = runTest {
        assertEquals(GnosisPayReading.NotGnosisPay, observe()(AccountId(Network.ETHEREUM, safe)).first())
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aDelayNotWiredToTheSafeIsNoGnosisPayAccount() = runTest {
        readings.getValue(delay).value = DelayReading.Verified(DelayFinding.NotWired(DelayGeneration.CURRENT), 41)

        assertEquals(GnosisPayReading.NotGnosisPay, observe(eoa(Network.GNOSIS))(gnosisPay).settled())
    }

    @Test
    fun aSafeWithoutModulesAndAnAddressThatIsNoSafeAreNotGnosisPay() = runTest {
        kind.value = safeWith(emptyList())
        assertEquals(GnosisPayReading.NotGnosisPay, observe()(gnosisPay).settled())

        kind.value = KindReading.Verified(AddressKind.Contract, 40)
        assertEquals(GnosisPayReading.NotGnosisPay, observe()(gnosisPay).settled())
    }

    @Test
    fun aModuleNotReadYetKeepsTheSafeCheckingAndOneUnreadableSaysWhy() = runTest {
        readings.getValue(delay).value = DelayReading.Syncing
        assertEquals(GnosisPayReading.Checking, observe()(gnosisPay).first())

        readings.getValue(delay).value = DelayReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(GnosisPayReading.Unavailable(ReadUnavailable.UNPROVEN), observe()(gnosisPay).settled())
    }

    @Test
    fun theSafesOwnStateDecidesFirst() = runTest {
        kind.value = KindReading.Syncing
        assertEquals(GnosisPayReading.Checking, observe()(gnosisPay).first())

        kind.value = KindReading.Unavailable(ReadUnavailable.NETWORK_OFF)
        assertEquals(GnosisPayReading.Unavailable(ReadUnavailable.NETWORK_OFF), observe()(gnosisPay).settled())
    }

    // Control through a member Safe (owner's request, 2026-11-17).

    private val memberSafe = member
    private val ownerKey = Address.of("0x" + "91".repeat(20))
    private val passkey = Address.of("0x" + "fd".repeat(20))

    @Test
    fun aKeyOwningAMemberSafeOfThresholdOneControlsTheAccountThroughIt() = runTest {
        others[memberSafe] = MutableStateFlow(safeOf(listOf(passkey, ownerKey)))
        others[passkey] = MutableStateFlow(KindReading.Verified(AddressKind.Contract, 40))
        val held = eoa(Network.GNOSIS, address = ownerKey)

        val reading = observe(held)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(listOf(HeldController(held, via = listOf(memberSafe))), reading.controllersHeld)
        assertEquals(GnosisPaySending.AVAILABLE, reading.sending)
        val control = assertNotNull(reading.control)
        assertEquals(
            listOf(DelayController(ownerKey, listOf(memberSafe), alone = true)),
            control.controllers,
        )
        // A passkey signer is code that is no Safe: listed, not followed.
        assertEquals(listOf(passkey), control.notFollowed)
    }

    @Test
    fun safesAreFollowedDownToTheKeyThreeDeepAndNoFurther() = runTest {
        val second = Address.of("0x" + "a2".repeat(20))
        val third = Address.of("0x" + "a3".repeat(20))
        val fourth = Address.of("0x" + "a4".repeat(20))
        others[memberSafe] = MutableStateFlow(safeOf(listOf(second)))
        others[second] = MutableStateFlow(safeOf(listOf(third)))
        others[third] = MutableStateFlow(safeOf(listOf(ownerKey, fourth)))
        others[fourth] = MutableStateFlow(safeOf(listOf(Address.of("0x" + "44".repeat(20)))))
        val held = eoa(Network.GNOSIS, address = ownerKey)

        val reading = observe(held)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(listOf(memberSafe, second, third), reading.controllersHeld.single().via)
        assertEquals(listOf(fourth), reading.control?.notFollowed)
        assertTrue(Address.of("0x" + "44".repeat(20)) !in kindsAsked, "nothing past the third Safe is read")
    }

    @Test
    fun aSafeOnTheWayThatNeedsMoreSignaturesMakesTheHeldKeyACoSignerOnly() = runTest {
        others[memberSafe] = MutableStateFlow(safeOf(listOf(passkey, ownerKey), threshold = 2))
        others[passkey] = MutableStateFlow(KindReading.Verified(AddressKind.Contract, 40))
        val held = eoa(Network.GNOSIS, address = ownerKey)

        val reading = observe(held)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(emptyList(), reading.controllersHeld)
        assertEquals(listOf(held), reading.keysCoSigning)
        assertEquals(GnosisPaySending.CO_SIGNERS_NEEDED, reading.sending)
        assertEquals(listOf(DelayController(ownerKey, listOf(memberSafe), alone = false)), reading.control?.controllers)
    }

    @Test
    fun aCoSigningKeyHeldOnlyOnAnotherNetworkIsStillACoSigner() = runTest {
        others[memberSafe] = MutableStateFlow(safeOf(listOf(passkey, ownerKey), threshold = 2))
        others[passkey] = MutableStateFlow(KindReading.Verified(AddressKind.Contract, 40))
        val onEthereum = eoa(Network.ETHEREUM, address = ownerKey)

        val reading = observe(onEthereum)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(listOf(onEthereum), reading.keysCoSigning)
        assertEquals(GnosisPaySending.CO_SIGNERS_NEEDED, reading.sending)
    }

    @Test
    fun anOwnerNotReadYetSaysControlIsUnreadNeverThatNoKeyIsHere() = runTest {
        val unread = MutableStateFlow<KindReading>(KindReading.Unavailable(ReadUnavailable.UNPROVEN))
        others[memberSafe] = unread
        val observe = observe(eoa(Network.GNOSIS, address = ownerKey))

        // Said at once, before any read lands, and then with the reason the read gave.
        val first = observe(gnosisPay).first { it is GnosisPayReading.Found } as GnosisPayReading.Found
        assertEquals(GnosisPaySending.CONTROL_UNREAD, first.sending)
        val unproven = listOf(ControlNode.Unread(memberSafe, ReadUnavailable.UNPROVEN))
        val failed = observe(gnosisPay).first { (it as? GnosisPayReading.Found)?.control?.members == unproven }
        assertEquals(GnosisPaySending.CONTROL_UNREAD, (failed as GnosisPayReading.Found).sending)

        unread.value = safeOf(listOf(ownerKey))
        assertEquals(GnosisPaySending.AVAILABLE, (observe(gnosisPay).settled() as GnosisPayReading.Found).sending)
    }

    @Test
    fun aKeyThatIsAMemberAndOwnsAMemberSafeSendsStraightToTheDelay() = runTest {
        val direct = Address.of("0x" + "12".repeat(20))
        readings.getValue(delay).value = DelayReading.Verified(
            DelayFinding.Wired(delayOf().copy(members = listOf(memberSafe, direct))),
            41,
        )
        others[memberSafe] = MutableStateFlow(safeOf(listOf(direct)))
        val held = eoa(Network.GNOSIS, address = direct)

        val reading = observe(held)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(listOf(HeldController(held, via = emptyList())), reading.controllersHeld)
        assertEquals(2, reading.control?.controllers?.size)
    }

    @Test
    fun aKeyOwningAMemberSafeHeldOnlyOnAnotherNetworkSaysSo() = runTest {
        others[memberSafe] = MutableStateFlow(safeOf(listOf(ownerKey)))
        val onEthereum = eoa(Network.ETHEREUM, address = ownerKey)

        val reading = observe(onEthereum)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(listOf(onEthereum), reading.keysHeldElsewhere)
        assertEquals(GnosisPaySending.MEMBER_ON_ANOTHER_NETWORK, reading.sending)
    }

    @Test
    fun aSafeThatOwnsItselfIsNotFollowedTwice() = runTest {
        others[memberSafe] = MutableStateFlow(safeOf(listOf(memberSafe, ownerKey)))
        val held = eoa(Network.GNOSIS, address = ownerKey)

        val reading = observe(held)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(listOf(memberSafe), reading.controllersHeld.single().via)
        assertEquals(listOf(memberSafe), reading.control?.notFollowed)
    }

    @Test
    fun aMemberSafeOlderThan130IsNotFollowed() = runTest {
        // Before 1.3.0 a Safe's execTransaction returns false where a newer one reverts: a run through
        // it would not prove the queueing.
        others[memberSafe] = MutableStateFlow(
            KindReading.Verified(
                AddressKind.Safe(SafeState("1.1.1", l2 = false, listOf(ownerKey), 1, 3, emptyList(), guard = null)),
                40,
            ),
        )
        val reading = observe(eoa(Network.GNOSIS, address = ownerKey))(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(listOf(memberSafe), reading.control?.notFollowed)
        assertEquals(GnosisPaySending.NO_MEMBER_KEY, reading.sending)
        assertTrue(ownerKey !in kindsAsked)
    }

    @Test
    fun aKeyImportedWhileShownTurnsTheAccountControlledWithoutCheckingItAgain() = runTest {
        others[memberSafe] = MutableStateFlow(safeOf(listOf(ownerKey)))
        val observe = observe()
        val seen = mutableListOf<GnosisPayReading>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { observe(gnosisPay).collect { seen += it } }
        observe(gnosisPay).settled()

        wallet.accounts.value = wallet.accounts.value + eoa(Network.GNOSIS, address = ownerKey)
        observe(gnosisPay).first { (it as? GnosisPayReading.Found)?.sending == GnosisPaySending.AVAILABLE }
        job.cancel()

        // Once found, never back to checking for the key that came: the members' last answer stands meanwhile.
        val afterFound = seen.dropWhile { it !is GnosisPayReading.Found }
        assertTrue(afterFound.none { it == GnosisPayReading.Checking }, afterFound.toString())
        assertEquals(GnosisPaySending.AVAILABLE, (afterFound.last() as GnosisPayReading.Found).sending)
    }

    @Test
    fun aWideTreeIsReadWithinItsBudgetAndAHeldKeyIsStillFound() = runTest {
        // A member Safe of 40 owners: more than one Delay's reads allow. A held key needs none.
        val owners = (1..40).map { Address.of("0x" + it.toString(16).padStart(2, '0').repeat(20)) }
        others[memberSafe] = MutableStateFlow(safeOf(owners + ownerKey))
        val held = eoa(Network.GNOSIS, address = ownerKey)

        val reading = observe(held)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(GnosisPaySending.AVAILABLE, reading.sending)
        assertTrue(kindsAsked.size <= DelayControl.MAX_ADDRESSES, "${kindsAsked.size} reads")
        assertEquals(owners, reading.control?.notFollowed)

        // Without the key: never "no key here", only that the tree is too wide to tell.
        kindsAsked.clear()
        val none = observe()(gnosisPay).settled() as GnosisPayReading.Found
        assertEquals(GnosisPaySending.CONTROL_TOO_WIDE, none.sending)
        assertTrue(kindsAsked.size <= DelayControl.MAX_ADDRESSES, "${kindsAsked.size} reads")
    }

    @Test
    fun theSentinelOwnerIsNeverReadNorAController() = runTest {
        others[memberSafe] = MutableStateFlow(safeOf(listOf(sentinelOwner)))

        val reading = observe()(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(emptyList(), reading.control?.controllers)
        assertTrue(sentinelOwner !in kindsAsked)
        assertEquals(GnosisPaySending.NO_MEMBER_KEY, reading.sending)
    }

    @Test
    fun theListReadsEveryGnosisChainSafeInTheWalletAndNoOther() = runTest {
        val vault = Account(Network.ETHEREUM, safe, AccountKind.SAFE, "Vault", AccountCustody.Watched)
        val observe = observe(eoa(Network.GNOSIS), vault)

        val readings = ObserveGnosisPays(wallet, observe)().first { it[gnosisPay]?.controlledHere == true }

        assertEquals(setOf(gnosisPay), readings.keys)
        assertTrue(asked.all { it.first == Network.GNOSIS })
    }

    @Test
    fun aDelaysQueueIsWithinItsNonces() {
        assertEquals(1, delayOf().queued)
        assertFailsWith<IllegalArgumentException> { delayOf().copy(txNonce = 4) }
        assertFailsWith<IllegalArgumentException> { delayOf().copy(cooldownSeconds = -1) }
    }
}
