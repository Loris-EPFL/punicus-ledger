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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = kind
    }

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

    /** The first reading past [GnosisPayReading.Checking]: the modules' readers deliver after the Safe's state. */
    private suspend fun Flow<GnosisPayReading>.settled() = first { it != GnosisPayReading.Checking }

    @Test
    fun aSafesOwnDelayWithAMemberHeldHereCanSend() = runTest {
        val held = eoa(Network.GNOSIS)
        val reading = observe(held)(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(delayOf(), reading.delay)
        assertEquals(41, reading.blockNumber)
        assertEquals(listOf(held), reading.membersHeld)
        assertEquals(GnosisPaySending.AVAILABLE, reading.sending)
        // Each module is asked about with the Safe's network and address, which the adapter applies.
        assertEquals(
            setOf(Triple(Network.GNOSIS, safe, roles), Triple(Network.GNOSIS, safe, delay)),
            asked.toSet(),
        )
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

        assertEquals(emptyList(), reading.membersHeld)
        assertEquals(listOf(onEthereum), reading.membersHeldElsewhere)
        assertEquals(GnosisPaySending.MEMBER_ON_ANOTHER_NETWORK, reading.sending)
    }

    @Test
    fun aMemberWatchedHereWhoseKeyIsHeldElsewhereSaysSo() = runTest {
        val watchedHere = eoa(Network.GNOSIS, AccountCustody.Watched)
        val reading = observe(watchedHere, eoa(Network.ETHEREUM))(gnosisPay).settled() as GnosisPayReading.Found

        assertEquals(listOf(watchedHere), reading.membersWatchedHere)
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

    @Test
    fun aDelaysQueueIsWithinItsNonces() {
        assertEquals(1, delayOf().queued)
        assertFailsWith<IllegalArgumentException> { delayOf().copy(txNonce = 4) }
        assertFailsWith<IllegalArgumentException> { delayOf().copy(cooldownSeconds = -1) }
    }
}
