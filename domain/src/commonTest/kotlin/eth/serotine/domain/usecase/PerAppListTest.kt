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
import eth.serotine.domain.repository.NetworkSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Screen D9's list of per-app addresses and their restore (issue #252, part 4), with fake ports. */
class PerAppListTest {

    private val aliases = MemoryAliases()

    private fun address(last: Char) = Address.of("0x" + "0".repeat(39) + last)

    private fun account(
        network: Network,
        last: Char,
        custody: AccountCustody = AccountCustody.InKeyStore,
        perApp: PerAppAccount? = null,
    ) = Account(network, address(last), AccountKind.EOA, "Account $last", custody, perApp = perApp)

    private val first = account(Network.ETHEREUM, '1')
    private val further = account(Network.ETHEREUM, '2')
    private val uniswap = testHost("uniswap.org")
    private val mark = PerAppAccount(uniswap, AppIsolation.pathOf(uniswap), first.address)
    private val perAppOnEthereum = account(Network.ETHEREUM, '6', perApp = mark)
    private val perAppOnGnosis = account(Network.GNOSIS, '6', perApp = mark)

    private val enabled = MutableStateFlow(setOf(Network.ETHEREUM, Network.GNOSIS))
    private val networks = object : NetworkSettings {
        override fun observe(): Flow<Set<Network>> = enabled
        override suspend fun setEnabled(network: Network, on: Boolean): Boolean = error("not used")
    }

    private fun restoreWith(keys: FakePerAppKeys) =
        RestorePerAppAddress(MakePerAppAddress(keys, ListAccountRepository(first), WalletChanges()), networks)

    @Test
    fun anAddressOnTwoNetworksIsListedOnceWithBoth() {
        val entries = perAppEntries(listOf(first, perAppOnEthereum, further, perAppOnGnosis))
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(address('6'), entry.address)
        assertEquals(uniswap, entry.site)
        assertEquals(first.address, entry.phrase)
        assertEquals(listOf(perAppOnEthereum, perAppOnGnosis), entry.accounts)
    }

    @Test
    fun aPhraseIsKnownByItsFirstAccountWhileItIsInTheWallet() {
        // The further account is listed first, but the phrase's first account names it.
        val phrases = mapOf(further.address to first.address, first.address to first.address)
        val listed = listOf(further, first)
        assertEquals(mapOf(first.address to first.address), phraseAccounts(listed, phraseSources(listed, phrases)))
    }

    @Test
    fun withoutItsFirstAccountAPhraseIsKnownByItsAccountListedFirst() {
        val phrases = mapOf(further.address to first.address)
        val listed = listOf(further)
        assertEquals(mapOf(first.address to further.address), phraseAccounts(listed, phraseSources(listed, phrases)))
    }

    @Test
    fun aPhraseWhoseNameIsNotKnownIsKnownByNone() {
        // An older envelope's further account names no phrase: it stands for one of its own, known by no account.
        val listed = listOf(further)
        assertEquals(emptyMap(), phraseAccounts(listed, phraseSources(listed, emptyMap())))
    }

    @Test
    fun aPerAppAddressIsNeverTheAccountItsPhraseIsKnownBy() {
        // Listed before the phrase's further account: the further account names the phrase all the same.
        val phrases = mapOf(further.address to first.address)
        val listed = listOf(perAppOnEthereum, further)
        val sources = phraseSources(listed, phrases)
        assertEquals(listOf(further), sources.map { it.from })
        assertEquals(mapOf(first.address to further.address), phraseAccounts(listed, sources))
        // Held by per-app addresses alone, the phrase can still be restored from, but no account has its tab.
        val alone = listOf(perAppOnEthereum)
        assertEquals(listOf(perAppOnEthereum), phraseSources(alone, emptyMap()).map { it.from })
        assertEquals(emptyMap(), phraseAccounts(alone, phraseSources(alone, emptyMap())))
    }

    @Test
    fun theOverviewFollowsTheListThePhrasesAndTheNetworks() = runTest {
        val accounts = ListAccountRepository(first, perAppOnEthereum)
        val keys = FakePerAppKeys(phrases = mapOf(first.address to first.address))
        val overview = ObservePerAppAddresses(accounts, keys, networks, aliases)().first()
        assertEquals(listOf(address('6')), overview.entries.map { it.address })
        assertEquals(listOf(first), overview.sources.map { it.from })
        assertEquals(mapOf(first.address to first.address), overview.knownBy)
        assertEquals(setOf(Network.ETHEREUM, Network.GNOSIS), overview.enabled)
    }

    @Test
    fun aRestoredAddressGoesOnTheNetworksThatAreOnOfOneKind() {
        assertEquals(listOf(Network.ETHEREUM, Network.GNOSIS), restoreNetworks(setOf(Network.ETHEREUM, Network.GNOSIS)))
        // Real funds first: Sepolia beside them is left out, as one session never mixes the two.
        assertEquals(listOf(Network.GNOSIS), restoreNetworks(setOf(Network.GNOSIS, Network.SEPOLIA)))
        assertEquals(listOf(Network.SEPOLIA), restoreNetworks(setOf(Network.SEPOLIA)))
        assertEquals(emptyList(), restoreNetworks(emptySet()))
    }

    @Test
    fun restoringMakesTheSitesAddressFromTheHostTyped() = runTest {
        val accounts = ListAccountRepository(first)
        val keys = FakePerAppKeys(address = address('6'))
        val restore = RestorePerAppAddress(MakePerAppAddress(keys, accounts, WalletChanges()), networks)
        val restored = restore(first.id, "  https://app.uniswap.org/swap ", WalletPassword("correct horse"))
        assertIs<PerAppRestoring.Done>(restored)
        assertEquals(uniswap, restored.site)
        val made = assertIs<PerAppMaking.Made>(restored.making)
        assertEquals(
            listOf(AccountId(Network.ETHEREUM, address('6')), AccountId(Network.GNOSIS, address('6'))),
            made.accounts,
        )
        assertEquals(listOf(first.address to testHost("app.uniswap.org")), keys.derived)
        assertEquals(uniswap, accounts.accounts.value.last().perApp?.site)
    }

    @Test
    fun aSiteTypedAloneIsRestoredToo() = runTest {
        val keys = FakePerAppKeys(address = address('6'))
        val restore = restoreWith(keys)
        val restored = assertIs<PerAppRestoring.Done>(restore(first.id, "uniswap.org", WalletPassword("correct horse")))
        assertEquals(uniswap, restored.site)
        assertIs<PerAppMaking.Made>(restored.making)
        for (typed in listOf("App.Uniswap.org/swap", "uniswap.org:443", "app.uniswap.org?utm=x", "uniswap.org#a")) {
            val restoredAgain = restore(first.id, typed, WalletPassword("correct horse"))
            assertEquals(uniswap, assertIs<PerAppRestoring.Done>(restoredAgain).site, typed)
        }
    }

    @Test
    fun whatNamesNoHostIsRefusedBeforeAnythingIsDerived() = runTest {
        val keys = FakePerAppKeys()
        val restore = restoreWith(keys)
        val password = WalletPassword("correct horse")
        val ftp = restore(first.id, "ftp://x.example", password)
        assertEquals(PerAppRestoring.NoHost(AppHostResult.NotAWebAddress), ftp)
        assertIs<PerAppRestoring.NoHost>(restore(first.id, "", password))
        assertIs<PerAppRestoring.NoHost>(restore(first.id, "not a host", password))
        assertIs<PerAppRestoring.NoHost>(restore(first.id, "bücher.example", password))
        assertEquals(emptyList(), keys.derived)
    }

    @Test
    fun withNoNetworkOnNothingIsMade() = runTest {
        enabled.value = emptySet()
        val keys = FakePerAppKeys()
        val restore = restoreWith(keys)
        val restored = assertIs<PerAppRestoring.Done>(restore(first.id, "uniswap.org", WalletPassword("correct horse")))
        assertEquals(PerAppMaking.NoNetwork, restored.making)
        assertEquals(emptyList(), keys.derived)
    }

    @Test
    fun anEntryNamesTheSitesLinkedToIt() {
        val app = testHost("uniswap.app")
        val linked = mapOf(app to address('6'), uniswap to address('6'))
        val entries = perAppEntries(listOf(first, perAppOnEthereum), linked)
        // Its own site is no alias of it.
        assertEquals(listOf(app), entries.single().aliases)
    }

    @Test
    fun aSiteIsLinkedOnlyToAnotherSitesPerAppAddress() = runTest {
        val accounts = ListAccountRepository(first, perAppOnEthereum)
        val link = LinkPerAppAlias(accounts, aliases)
        assertEquals(AliasLinking.NotPerApp, link(testHost("uniswap.app"), first.address), "an ordinary account")
        assertEquals(AliasLinking.NotPerApp, link(testHost("app.uniswap.org"), address('6')), "its own site")
        assertEquals(emptyMap(), aliases.linked.value)
        assertEquals(AliasLinking.Linked, link(testHost("www.uniswap.app"), address('6')))
        assertEquals(mapOf(testHost("uniswap.app") to address('6')), aliases.linked.value, "kept by its site")
        aliases.failSaves = true
        assertEquals(AliasLinking.NotSaved, link(testHost("cow.fi"), address('6')))
        aliases.failSaves = false
        assertEquals(true, UnlinkPerAppAlias(aliases)(testHost("app.uniswap.app")))
        assertEquals(emptyMap(), aliases.linked.value)
    }
}
