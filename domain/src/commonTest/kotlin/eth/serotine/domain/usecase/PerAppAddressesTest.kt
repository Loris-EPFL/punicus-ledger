package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.AppIsolation
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PerAppAccount
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappProposal
import eth.serotine.domain.repository.DappProposalId
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.repository.PerAppDerivation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The per-app choice of screen D3 and making a per-app address (issue #252), with fake ports. */
class PerAppAddressesTest {

    private fun address(last: Char) = Address.of("0x" + "0".repeat(39) + last)

    private fun account(
        network: Network,
        last: Char,
        kind: AccountKind = AccountKind.EOA,
        custody: AccountCustody = AccountCustody.InKeyStore,
        perApp: PerAppAccount? = null,
    ) = Account(network, address(last), kind, "Account $last", custody, perApp = perApp)

    private val first = account(Network.ETHEREUM, '1')
    private val further = account(Network.ETHEREUM, '2')
    private val other = account(Network.ETHEREUM, '3')
    private val watched = account(Network.ETHEREUM, '4', custody = AccountCustody.Watched)
    private val safe = account(Network.ETHEREUM, '5', kind = AccountKind.SAFE)

    private val enabled = MutableStateFlow(setOf(Network.ETHEREUM, Network.GNOSIS))
    private val networks = object : NetworkSettings {
        override fun observe(): Flow<Set<Network>> = enabled
        override suspend fun setEnabled(network: Network, on: Boolean): Boolean = error("not used")
    }

    private fun proposal(required: Set<String> = emptySet(), optional: Set<String> = emptySet()) = DappProposal(
        DappProposalId("pairing", 1),
        DappMetadata("Uniswap", "https://app.uniswap.org/swap", ""),
        required,
        optional,
        setOf("personal_sign"),
        null,
        0,
    )

    @Test
    fun phrasesAreToldApartAndNamedByTheirFirstAccountInTheList() {
        // `further` is of `first`'s phrase; `other` is of a phrase of its own; watched and Safes hold no phrase.
        val phrases = mapOf(first.address to first.address, further.address to first.address)
        val sources = phraseSources(listOf(watched, further, safe, first, other), phrases)
        assertEquals(listOf(PhraseSource(first.address, further), PhraseSource(null, other)), sources)
    }

    @Test
    fun aPerAppAccountNamesItsPhraseItself() {
        val mark = PerAppAccount(testHost("uniswap.org"), AppIsolation.pathOf(testHost("uniswap.org")), first.address)
        val perApp = account(Network.ETHEREUM, '6', perApp = mark)
        assertEquals(listOf(PhraseSource(first.address, perApp)), phraseSources(listOf(perApp, first), emptyMap()))
    }

    @Test
    fun theNewAddressGoesOnTheAskedNetworksThatAreOnOfOneKind() {
        val asking = proposal(optional = setOf("eip155:1", "eip155:100", "eip155:11155111"))
        val on = setOf(Network.ETHEREUM, Network.GNOSIS, Network.SEPOLIA)
        // Nothing chosen, nothing required: real funds, where the dApp asks for them.
        assertEquals(listOf(Network.ETHEREUM, Network.GNOSIS), perAppNetworks(asking, on, emptyList(), emptyList()))
        // A Sepolia account chosen: testnet.
        assertEquals(
            listOf(Network.SEPOLIA),
            perAppNetworks(asking, on, listOf(AccountId(Network.SEPOLIA, first.address)), emptyList()),
        )
        // Sepolia required.
        val sepolia = proposal(required = setOf("eip155:11155111"), optional = setOf("eip155:1"))
        assertEquals(listOf(Network.SEPOLIA), perAppNetworks(sepolia, on, emptyList(), emptyList()))
        // A network that is off is left out, and so is one the site has its address on already.
        val made = account(Network.ETHEREUM, '6')
        assertEquals(listOf(Network.GNOSIS), perAppNetworks(asking, on, emptyList(), listOf(made)))
        val ethereum = setOf(Network.ETHEREUM)
        assertEquals(listOf(Network.ETHEREUM), perAppNetworks(asking, ethereum, emptyList(), emptyList()))
        // Only Sepolia on: testnet, though the dApp asks for real funds too.
        assertEquals(listOf(Network.SEPOLIA), perAppNetworks(asking, setOf(Network.SEPOLIA), emptyList(), emptyList()))
    }

    @Test
    fun theChoiceNamesTheSiteItsAddressesAndWhatWasApprovedLast() = runTest {
        val site = testHost("uniswap.org")
        val mark = PerAppAccount(site, AppIsolation.pathOf(site), first.address)
        val made = account(Network.ETHEREUM, '6', perApp = mark)
        val accounts = ListAccountRepository(first, made)
        val sites = MemorySites()
        sites.remembered.value = mapOf(site to listOf(made.id))
        val keys = FakePerAppKeys(phrases = mapOf(first.address to first.address))
        val choice = ObservePerAppChoice(accounts, keys, sites, networks)("https://app.uniswap.org/swap").first()
        assertEquals(AppHostResult.Host(testHost("app.uniswap.org")), choice.host)
        assertEquals(site, choice.site)
        assertEquals(listOf(made), choice.made)
        assertEquals(listOf(made.id), choice.remembered)
        assertEquals(listOf(PhraseSource(first.address, first)), choice.sources)
        assertNull(choice.collision)
    }

    @Test
    fun aDappWithNoHostHasNoSite() = runTest {
        val choice = ObservePerAppChoice(ListAccountRepository(first), FakePerAppKeys(), MemorySites(), networks)(
            "wc://not-a-web-address",
        ).first()
        assertEquals(AppHostResult.NotAWebAddress, choice.host)
        assertNull(choice.site)
        assertTrue(choice.made.isEmpty())
    }

    @Test
    fun aPerAppAddressIsMadeOnEachNetworkNamedAndMarked() = runTest {
        val accounts = ListAccountRepository(first)
        val keys = FakePerAppKeys(phrases = mapOf(first.address to first.address))
        val make = MakePerAppAddress(keys, accounts, WalletChanges())
        val host = testHost("app.uniswap.org")
        val made = make(first.id, host, listOf(Network.ETHEREUM, Network.GNOSIS), WalletPassword("correct horse"))
        assertIs<PerAppMaking.Made>(made)
        assertEquals(listOf(first.address to host), keys.derived)
        assertEquals(listOf(Network.ETHEREUM, Network.GNOSIS), made.accounts.map { it.network })
        val added = accounts.accounts.value.drop(1)
        assertEquals(2, added.size)
        added.forEach {
            assertEquals("uniswap.org", it.label)
            assertEquals(AccountKind.EOA, it.kind)
            assertEquals(AccountCustody.InKeyStore, it.custody)
            assertEquals(PerAppAccount(testHost("uniswap.org"), AppIsolation.pathOf(host), first.address), it.perApp)
        }
    }

    @Test
    fun makingOneRefusesWhatCannotBeMadeAndChangesNothing() = runTest {
        val accounts = ListAccountRepository(first, watched, safe)
        val keys = FakePerAppKeys()
        val make = MakePerAppAddress(keys, accounts, WalletChanges())
        val host = testHost("app.uniswap.org")
        val password = WalletPassword("correct horse")
        assertEquals(PerAppMaking.NoNetwork, make(first.id, host, emptyList(), password))
        assertEquals(PerAppMaking.NoKey, make(watched.id, host, listOf(Network.ETHEREUM), password))
        assertEquals(PerAppMaking.NoKey, make(safe.id, host, listOf(Network.ETHEREUM), password))
        assertTrue(keys.derived.isEmpty())
        assertEquals(PerAppMaking.WrongPassword, make(first.id, host, listOf(Network.ETHEREUM), WalletPassword("no")))
        keys.answer = PerAppDerivation.NotSaved
        assertEquals(PerAppMaking.KeyNotSaved, make(first.id, host, listOf(Network.ETHEREUM), password))
        assertEquals(listOf(first, watched, safe), accounts.accounts.value)
        keys.answer = null
        accounts.failSaves = true
        assertEquals(PerAppMaking.ListNotSaved, make(first.id, host, listOf(Network.ETHEREUM), password))
    }

    @Test
    fun aSecondHostOfTheSiteIsNoCollision() = runTest {
        val site = testHost("uniswap.org")
        val mark = PerAppAccount(site, AppIsolation.pathOf(site), first.address)
        val made = account(Network.ETHEREUM, '6', perApp = mark)
        val accounts = ListAccountRepository(first, made)
        val keys = FakePerAppKeys(address = made.address)
        val result = MakePerAppAddress(keys, accounts, WalletChanges())(
            first.id,
            testHost("swap.uniswap.org"),
            listOf(Network.GNOSIS),
            WalletPassword("correct horse"),
        )
        assertIs<PerAppMaking.Made>(result)
        assertEquals(listOf(AccountId(Network.GNOSIS, made.address)), result.accounts)
    }
}
